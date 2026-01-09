package com.manga.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LlmClient(context: Context) {
    private val settingsStore = SettingsStore(context)

    fun isConfigured(): Boolean {
        return settingsStore.load().isValid()
    }

    suspend fun translate(text: String): String? = withContext(Dispatchers.IO) {
        val settings = settingsStore.load()
        if (!settings.isValid()) return@withContext null
        val endpoint = buildEndpoint(settings.apiUrl)
        val payload = buildPayload(text, settings.modelName)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
        }
        return@withContext try {
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
                parseResponse(body)
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

    private fun buildPayload(text: String, modelName: String): JSONObject {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", SYSTEM_PROMPT)
        )
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", USER_PROMPT_PREFIX + text)
        )
        return JSONObject()
            .put("model", modelName)
            .put("temperature", 0.3)
            .put("messages", messages)
    }

    private fun parseResponse(body: String): String? {
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

    companion object {
        private const val TIMEOUT_MS = 30_000
        private const val SYSTEM_PROMPT =
            "You are a professional manga translator. Translate Japanese dialogue into natural, concise Simplified Chinese. Preserve tone, speaker intent, and onomatopoeia. Return only the translated text."
        private const val USER_PROMPT_PREFIX = "请将以下日文翻译为简体中文，只输出译文：\n"
    }
}
