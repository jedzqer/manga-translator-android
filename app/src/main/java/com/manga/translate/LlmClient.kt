package com.manga.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LlmClient(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val promptCache = mutableMapOf<String, LlmPromptConfig>()

    fun isConfigured(): Boolean {
        return settingsStore.load().isValid()
    }

    suspend fun translate(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String = PROMPT_CONFIG_ASSET
    ): LlmTranslationResult? =
        withContext(Dispatchers.IO) {
            requestContent(text, glossary, promptAsset, useJsonPayload = true)
                ?.let { parseTranslationContent(it) }
    }

    suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        requestContent(text, glossary, promptAsset, useJsonPayload = true)
            ?.let { parseGlossaryContent(it) }
    }

    private fun requestContent(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String,
        useJsonPayload: Boolean
    ): String? {
        val settings = settingsStore.load()
        if (!settings.isValid()) return null
        val endpoint = buildEndpoint(settings.apiUrl)
        val payload = buildPayload(text, glossary, settings.modelName, promptAsset, useJsonPayload)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
        }
        return try {
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                AppLogger.log("LlmClient", "HTTP $code: $body")
                null
            } else {
                parseResponseContent(body)
            }
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Request failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun buildEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/v1/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun buildPayload(
        text: String,
        glossary: Map<String, String>,
        modelName: String,
        promptAsset: String,
        useJsonPayload: Boolean
    ): JSONObject {
        val config = getPromptConfig(promptAsset)
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", config.systemPrompt)
        )
        for (message in config.exampleMessages) {
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    config.userPromptPrefix + if (useJsonPayload) {
                        buildUserPayload(text, glossary)
                    } else {
                        text
                    }
                )
        )
        return JSONObject()
            .put("model", modelName)
            .put("temperature", 0.3)
            .put("messages", messages)
    }

    private fun parseResponseContent(body: String): String? {
        return try {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null
            val message = first.optJSONObject("message") ?: return null
            message.optString("content")?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTranslationContent(content: String): LlmTranslationResult? {
        return try {
            val cleaned = stripCodeFence(content)
            val json = JSONObject(cleaned)
            val translation = json.optString("translation")?.trim().orEmpty()
            if (translation.isBlank()) {
                AppLogger.log("LlmClient", "Missing translation field in response")
                return null
            }
            val glossary = mutableMapOf<String, String>()
            val glossaryJson = json.optJSONObject("glossary_used")
            if (glossaryJson != null) {
                for (key in glossaryJson.keys()) {
                    val value = glossaryJson.optString(key).trim()
                    if (key.isNotBlank() && value.isNotBlank()) {
                        glossary[key] = value
                    }
                }
            }
            LlmTranslationResult(translation, glossary)
        } catch (e: Exception) {
            LlmTranslationResult(content, emptyMap())
        }
    }

    private fun parseGlossaryContent(content: String): Map<String, String> {
        return try {
            val cleaned = stripCodeFence(content)
            val json = JSONObject(cleaned)
            val glossaryJson = json.optJSONObject("glossary_used") ?: return emptyMap()
            val glossary = mutableMapOf<String, String>()
            for (key in glossaryJson.keys()) {
                val value = glossaryJson.optString(key).trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    glossary[key] = value
                }
            }
            glossary
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Glossary parse failed", e)
            emptyMap()
        }
    }

    private fun getPromptConfig(name: String): LlmPromptConfig {
        return promptCache.getOrPut(name) { loadPromptConfig(name) }
    }

    private fun loadPromptConfig(name: String): LlmPromptConfig {
        val json = JSONObject(readAsset(name))
        val systemPrompt = json.optString("system_prompt")
        val userPromptPrefix = json.optString("user_prompt_prefix")
        val examplesJson = json.optJSONArray("example_messages") ?: JSONArray()
        val examples = ArrayList<PromptMessage>(examplesJson.length())
        for (i in 0 until examplesJson.length()) {
            val messageObj = examplesJson.optJSONObject(i) ?: continue
            val role = messageObj.optString("role")
            val content = messageObj.optString("content")
            if (role.isNotBlank() && content.isNotBlank()) {
                examples.add(PromptMessage(role, content))
            }
        }
        return LlmPromptConfig(systemPrompt, userPromptPrefix, examples)
    }

    private fun readAsset(name: String): String {
        return appContext.assets.open(name).bufferedReader().use { it.readText() }
    }

    private fun buildUserPayload(text: String, glossary: Map<String, String>): String {
        val glossaryJson = JSONObject()
        for ((key, value) in glossary) {
            glossaryJson.put(key, value)
        }
        return JSONObject()
            .put("text", text)
            .put("glossary", glossaryJson)
            .toString()
    }

    private fun stripCodeFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
            return trimmed
        }
        var inner = trimmed.removePrefix("```").removeSuffix("```").trim()
        if (inner.startsWith("json", ignoreCase = true)) {
            inner = inner.removePrefix("json").trim()
        }
        return inner
    }

    companion object {
        private const val TIMEOUT_MS = 30_000
        private const val PROMPT_CONFIG_ASSET = "llm_prompts.json"
    }
}

data class LlmTranslationResult(
    val translation: String,
    val glossaryUsed: Map<String, String>
)

private data class LlmPromptConfig(
    val systemPrompt: String,
    val userPromptPrefix: String,
    val exampleMessages: List<PromptMessage>
)

private data class PromptMessage(
    val role: String,
    val content: String
)
