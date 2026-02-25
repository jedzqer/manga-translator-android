package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

class LlmClient(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val promptCache = mutableMapOf<String, LlmPromptConfig>()

    fun isConfigured(): Boolean {
        return settingsStore.load().isValid()
    }

    fun isOcrConfigured(): Boolean {
        return settingsStore.loadOcrApiSettings().isValid()
    }

    suspend fun translate(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String = PROMPT_CONFIG_ASSET
    ): LlmTranslationResult? =
        withContext(Dispatchers.IO) {
            val content = requestContent(text, glossary, promptAsset, useJsonPayload = true)
                ?: return@withContext null
            parseTranslationContent(content)
    }

    suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        requestContent(text, glossary, promptAsset, useJsonPayload = true)
            ?.let { parseGlossaryContent(it) }
    }

    suspend fun fetchModelList(
        apiUrl: String,
        apiKey: String
    ): List<String> = withContext(Dispatchers.IO) {
        requestModelList(apiUrl, apiKey)
    }

    suspend fun recognizeImageText(image: Bitmap): String? = withContext(Dispatchers.IO) {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        if (!ocrSettings.isValid() || ocrSettings.useLocalOcr) {
            return@withContext null
        }
        val endpoint = buildEndpoint(ocrSettings.apiUrl)
        val payload = buildImageOcrPayload(ocrSettings.modelName, image)
        val timeoutMs = ocrSettings.timeoutSeconds * 1000
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..RETRY_COUNT) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${ocrSettings.apiKey}")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
            }
            val result = try {
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    AppLogger.log("LlmClient", "OCR HTTP $code on $endpoint: ${summarizeBody(body)}")
                    lastErrorCode = "HTTP $code"
                    lastErrorBody = body
                    null
                } else {
                    parseResponseContent(body)?.trim()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("LlmClient", "OCR request failed on $endpoint (attempt $attempt)", e)
                lastErrorCode = "NETWORK_ERROR"
                null
            } finally {
                connection.disconnect()
            }
            if (result != null || attempt == RETRY_COUNT) {
                if (result != null) return@withContext result
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "OCR request failed on $endpoint: $lastErrorCode, body=${summarizeBody(lastErrorBody)}"
                    )
                }
                return@withContext null
            }
        }
        null
    }

    private suspend fun requestContent(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String,
        useJsonPayload: Boolean
    ): String? {
        val settings = settingsStore.load()
        if (!settings.isValid()) return null
        val endpoint = buildEndpoint(settings.apiUrl)
        val selectedModel = selectModelForRequest(settings.modelName)
        val payload = buildPayload(text, glossary, selectedModel, promptAsset, useJsonPayload)
        val logModelIo = settingsStore.loadModelIoLogging()
        if (logModelIo) {
            AppLogger.log("LlmClient", "Model input ($promptAsset): $payload")
            AppLogger.log("LlmClient", "Selected model: $selectedModel")
        }
        val timeoutMs = settingsStore.loadApiTimeoutMs()
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..RETRY_COUNT) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
            }
            val result = try {
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
                    AppLogger.log(
                        "LlmClient",
                        "HTTP $code on $endpoint: ${summarizeBody(body)}"
                    )
                    lastErrorCode = "HTTP $code"
                    lastErrorBody = body
                    null
                } else {
                    val content = parseResponseContent(body)
                    if (content == null) {
                        AppLogger.log(
                            "LlmClient",
                            "Empty or invalid response content from $endpoint"
                        )
                        lastErrorCode = "INVALID_RESPONSE"
                        lastErrorBody = body
                    } else if (logModelIo) {
                        AppLogger.log("LlmClient", "Model output: $content")
                    }
                    content
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("LlmClient", "Request failed on $endpoint (attempt $attempt)", e)
                lastErrorCode = "NETWORK_ERROR"
                null
            } finally {
                connection.disconnect()
            }
            if (result != null || attempt == RETRY_COUNT) {
                if (result != null) {
                    return result
                }
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "Request failed on $endpoint: $lastErrorCode, body=${summarizeBody(lastErrorBody)}"
                    )
                    throw LlmRequestException(lastErrorCode, lastErrorBody)
                }
                return null
            }
        }
        return null
    }

    private fun buildEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/v1/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun buildModelsEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return when {
            trimmed.endsWith("/v1/models") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/models"
            else -> "$trimmed/v1/models"
        }
    }

    private fun buildPayload(
        text: String,
        glossary: Map<String, String>,
        modelName: String,
        promptAsset: String,
        useJsonPayload: Boolean
    ): JSONObject {
        val llmParams = settingsStore.loadLlmParameters()
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
        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", messages)
        llmParams.temperature?.let { payload.put("temperature", it) }
        llmParams.topP?.let { payload.put("top_p", it) }
        llmParams.topK?.let { payload.put("top_k", it) }
        llmParams.maxOutputTokens?.let { payload.put("max_output_tokens", it) }
        llmParams.frequencyPenalty?.let { payload.put("frequency_penalty", it) }
        llmParams.presencePenalty?.let { payload.put("presence_penalty", it) }
        return payload
    }

    private fun selectModelForRequest(modelConfig: String): String {
        val models = parseModelCandidates(modelConfig)
        if (models.isEmpty()) return modelConfig.trim()
        val index = requestCounter.getAndIncrement().mod(models.size.toLong()).toInt()
        return models[index]
    }

    private fun parseModelCandidates(modelConfig: String): List<String> {
        return modelConfig.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseResponseContent(body: String): String? {
        return try {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null
            val message = first.optJSONObject("message") ?: return null
            val rawContent = message.opt("content")
            when (rawContent) {
                is String -> rawContent.trim().ifBlank { null }
                is JSONArray -> {
                    val parts = ArrayList<String>(rawContent.length())
                    for (i in 0 until rawContent.length()) {
                        val item = rawContent.opt(i)
                        when (item) {
                            is String -> if (item.isNotBlank()) parts.add(item.trim())
                            is JSONObject -> {
                                val text = item.optString("text").trim()
                                if (text.isNotBlank()) parts.add(text)
                            }
                        }
                    }
                    parts.joinToString("\n").trim().ifBlank { null }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildImageOcrPayload(modelName: String, image: Bitmap): JSONObject {
        val config = getPromptConfig(OCR_PROMPT_CONFIG_ASSET)
        val imageBase64 = encodeBitmapToBase64(image)
        val userInstruction = config.userPromptPrefix.ifBlank { DEFAULT_OCR_USER_PROMPT }
        val userContent = JSONArray()
            .put(
                JSONObject()
                    .put("type", "text")
                    .put("text", userInstruction)
            )
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
            )
        val messages = JSONArray()
        if (config.systemPrompt.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", config.systemPrompt)
            )
        }
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
                .put("content", userContent)
        )
        return JSONObject()
            .put("model", modelName)
            .put("messages", messages)
    }

    private fun encodeBitmapToBase64(image: Bitmap): String {
        val buffer = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 90, buffer)
        return Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseTranslationContent(content: String): LlmTranslationResult {
        val cleaned = stripCodeFence(content)
        return try {
            val json = JSONObject(cleaned)
            val translation = json.optString("translation")?.trim().orEmpty()
            if (translation.isBlank()) {
                AppLogger.log("LlmClient", "Missing translation field in response")
                throw LlmResponseException("MISSING_TRANSLATION", content)
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
        } catch (e: LlmResponseException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(
                "LlmClient",
                "Invalid translation response format: ${summarizeBody(content)}",
                e
            )
            throw LlmResponseException("INVALID_FORMAT", content, e)
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

    private suspend fun requestModelList(apiUrl: String, apiKey: String): List<String> {
        if (apiUrl.isBlank()) {
            throw LlmRequestException("MISSING_URL")
        }
        val endpoint = buildModelsEndpoint(apiUrl)
        val timeoutMs = settingsStore.loadApiTimeoutMs()
        var lastErrorCode: String? = null
        var lastErrorBody: String? = null
        for (attempt in 1..RETRY_COUNT) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            val result = try {
                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    AppLogger.log(
                        "LlmClient",
                        "Model list HTTP $code on $endpoint: ${summarizeBody(body)}"
                    )
                    lastErrorCode = "HTTP $code"
                    lastErrorBody = body
                    null
                } else {
                    val models = parseModelList(body)
                    if (models.isEmpty()) {
                        lastErrorCode = "EMPTY_RESPONSE"
                        lastErrorBody = body
                    }
                    models
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log(
                    "LlmClient",
                    "Model list request failed on $endpoint (attempt $attempt)",
                    e
                )
                lastErrorCode = "NETWORK_ERROR"
                null
            } finally {
                connection.disconnect()
            }
            if (!result.isNullOrEmpty() || attempt == RETRY_COUNT) {
                if (!result.isNullOrEmpty()) {
                    return result
                }
                if (lastErrorCode != null) {
                    AppLogger.log(
                        "LlmClient",
                        "Model list failed on $endpoint: $lastErrorCode, body=${summarizeBody(lastErrorBody)}"
                    )
                    throw LlmRequestException(lastErrorCode, lastErrorBody)
                }
                return emptyList()
            }
        }
        return emptyList()
    }

    private fun parseModelList(body: String): List<String> {
        return try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return emptyList()
            val models = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                if (id.isNotBlank()) {
                    models.add(id)
                }
            }
            models
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Model list parse failed", e)
            emptyList()
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

    private fun summarizeBody(body: String?, limit: Int = 600): String {
        if (body.isNullOrBlank()) return "(empty)"
        val normalized = body.replace("\n", " ").replace("\r", " ").trim()
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "...(truncated)"
    }

    companion object {
        private const val PROMPT_CONFIG_ASSET = "llm_prompts.json"
        private const val OCR_PROMPT_CONFIG_ASSET = "ocr_prompts.json"
        private const val DEFAULT_OCR_USER_PROMPT =
            "<image>\nExtract only visible text from this image. Do not describe objects, people, or scene. If no text is visible, return None."
        private const val RETRY_COUNT = 3
        private val requestCounter = AtomicLong(0)
    }
}

class LlmRequestException(
    val errorCode: String,
    val responseBody: String? = null
) : Exception("LLM request failed: $errorCode")

class LlmResponseException(
    val errorCode: String,
    val responseContent: String,
    cause: Throwable? = null
) : Exception("LLM response invalid: $errorCode", cause)

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
