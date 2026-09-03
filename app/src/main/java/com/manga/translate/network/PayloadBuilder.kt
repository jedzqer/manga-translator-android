package com.manga.translate.network

import android.content.Context
import android.graphics.Bitmap
import com.manga.translate.model.ApiFormat
import com.manga.translate.platform.ImageEncodingUtils
import com.manga.translate.platform.PromptAssetResolver
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.LlmParameterSettings
import com.manga.translate.settings.OCR_PROVIDER_ID
import com.manga.translate.settings.OcrApiSettings
import com.manga.translate.settings.PRIMARY_PROVIDER_ID
import com.manga.translate.settings.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 请求体组装：OpenAI Chat Completions / OpenAI Responses / Gemini 三套协议，
 * 文本与图片翻译载荷、OCR 载荷、用户侧 items/glossary 载荷，以及自定义请求参数注入。
 *
 * JSON 结构与原始 LlmClient 实现完全一致（含 OPENAI_RESPONSES 的 model/input/instructions、
 * OCR 仅 OpenAI 兼容 chat 等契约）。
 */
internal class PayloadBuilder(
    private val appContext: Context,
    private val settingsStore: SettingsStore
) {
    private val promptCache = ConcurrentHashMap<String, LlmPromptConfig>()

    fun buildPayload(
        settings: ApiSettings,
        modelName: String,
        promptAsset: String,
        apiFormat: ApiFormat,
        userPayload: String
    ): JSONObject {
        val config = getPromptConfig(promptAsset)
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> buildOpenAiPayload(
                settings = settings,
                modelName = modelName,
                config = config,
                userPayload = userPayload
            )
            ApiFormat.OPENAI_RESPONSES -> buildOpenAiResponsesPayload(
                settings = settings,
                modelName = modelName,
                config = config,
                userPayload = userPayload
            )
            ApiFormat.GEMINI -> buildGeminiTextPayload(
                settings = settings,
                config = config,
                userPayload = userPayload
            )
        }
    }

    fun buildImageOcrPayload(ocrSettings: OcrApiSettings, image: Bitmap): JSONObject {
        val config = getPromptConfig(OCR_PROMPT_CONFIG_ASSET)
        val imageBase64 = ImageEncodingUtils.encodeBitmapToBase64(image)
            ?: throw LlmRequestException(
                LlmErrorCode.ImageEncodeFailed,
                "Failed to encode OCR image as JPEG"
            )
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
        val payload = JSONObject()
            .put("model", ocrSettings.modelName)
            .put("messages", messages)
        applyCustomRequestParameters(
            payload,
            ApiSettings(
                apiUrl = ocrSettings.apiUrl,
                apiKey = ocrSettings.apiKey,
                modelName = ocrSettings.modelName,
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                providerId = OCR_PROVIDER_ID
            )
        )
        return payload
    }

    fun buildImageTranslationPayload(
        settings: ApiSettings,
        modelName: String,
        imageBase64: String,
        promptAsset: String,
        apiFormat: ApiFormat
    ): JSONObject {
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> buildOpenAiImageTranslationPayload(
                settings = settings,
                modelName = modelName,
                imageBase64 = imageBase64,
                promptAsset = promptAsset
            )
            ApiFormat.OPENAI_RESPONSES -> buildOpenAiResponsesImageTranslationPayload(
                settings = settings,
                modelName = modelName,
                imageBase64 = imageBase64,
                promptAsset = promptAsset
            )
            ApiFormat.GEMINI -> buildGeminiImageTranslationPayload(settings, imageBase64, promptAsset)
        }
    }

    fun buildUserPayload(text: String, glossary: Map<String, String>): String {
        return JSONObject()
            .put("text", text)
            .put("glossary", buildGlossaryJson(glossary))
            .toString()
    }

    fun buildBubbleItemsUserPayload(
        items: List<LlmBubbleTranslationRequestItem>,
        glossary: Map<String, String>
    ): String {
        val itemsJson = JSONArray()
        items.forEach { item ->
            itemsJson.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
            )
        }
        return JSONObject()
            .put("items", itemsJson)
            .put("glossary", buildGlossaryJson(glossary))
            .toString()
    }

    /** 日志脱敏：把请求/响应 JSON 中的 base64 图片数据替换为占位文本。 */
    fun sanitizeModelIoForLog(content: String): String {
        val sanitizedJson = runCatching {
            when {
                content.trimStart().startsWith("{") -> {
                    when (val sanitized = sanitizeJsonValue(JSONObject(content))) {
                        is JSONObject -> sanitized.toString()
                        is JSONArray -> sanitized.toString()
                        else -> null
                    }
                }
                content.trimStart().startsWith("[") -> {
                    when (val sanitized = sanitizeJsonValue(JSONArray(content))) {
                        is JSONObject -> sanitized.toString()
                        is JSONArray -> sanitized.toString()
                        else -> null
                    }
                }
                else -> null
            }
        }.getOrNull()
        return sanitizedJson ?: content
            .replace(Regex("""data:image/[^;]+;base64,[A-Za-z0-9+/=]+"""), "data:image/<base64 omitted>")
    }

    private fun buildOpenAiPayload(
        settings: ApiSettings,
        modelName: String,
        config: LlmPromptConfig,
        userPayload: String
    ): JSONObject {
        val llmParams = settingsStore.loadLlmParameters()
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
                    config.userPromptPrefix + userPayload
                )
        )
        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", messages)
        applyOpenAiSamplingParams(payload, llmParams, settings)
        applyOpenAiThinkingParams(payload, llmParams)
        applyCustomRequestParameters(payload, settings)
        return payload
    }

    private fun buildOpenAiResponsesPayload(
        settings: ApiSettings,
        modelName: String,
        config: LlmPromptConfig,
        userPayload: String
    ): JSONObject {
        val llmParams = settingsStore.loadLlmParameters()
        val input = JSONArray()
        for (message in config.exampleMessages) {
            input.put(
                JSONObject()
                    .put("role", mapOpenAiResponsesRole(message.role))
                    .put("content", message.content)
            )
        }
        input.put(
            JSONObject()
                .put("role", "user")
                .put("content", config.userPromptPrefix + userPayload)
        )
        val payload = JSONObject()
            .put("model", modelName)
            .put("input", input)
        if (config.systemPrompt.isNotBlank()) {
            payload.put("instructions", config.systemPrompt)
        }
        applyOpenAiResponsesSamplingParams(payload, llmParams)
        applyOpenAiThinkingParams(payload, llmParams)
        applyCustomRequestParameters(payload, settings)
        return payload
    }

    private fun mapOpenAiResponsesRole(role: String): String {
        return when (role.lowercase()) {
            "assistant", "model" -> "assistant"
            "system", "developer" -> "developer"
            else -> "user"
        }
    }

    private fun buildGeminiTextPayload(
        settings: ApiSettings,
        config: LlmPromptConfig,
        userPayload: String
    ): JSONObject {
        val userText = config.userPromptPrefix + userPayload
        val payload = JSONObject()
            .put("contents", buildGeminiContents(config, buildGeminiUserParts(buildGeminiTextPart(userText))))
        if (config.systemPrompt.isNotBlank()) {
            payload.put("systemInstruction", buildGeminiSystemInstruction(config.systemPrompt))
        }
        buildGeminiGenerationConfig(useJsonPayload = true)?.let { payload.put("generationConfig", it) }
        applyCustomRequestParameters(payload, settings)
        return payload
    }

    private fun buildOpenAiImageTranslationPayload(
        settings: ApiSettings,
        modelName: String,
        imageBase64: String,
        promptAsset: String
    ): JSONObject {
        val llmParams = settingsStore.loadLlmParameters()
        val config = getPromptConfig(promptAsset)
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
                .put(
                    "content",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", config.userPromptPrefix.ifBlank {
                                    DEFAULT_IMAGE_TRANSLATION_USER_PROMPT
                                })
                        )
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                                )
                        )
                )
        )
        val payload = JSONObject()
            .put("model", modelName)
            .put("messages", messages)
        applyOpenAiSamplingParams(payload, llmParams, settings)
        applyOpenAiThinkingParams(payload, llmParams)
        applyCustomRequestParameters(payload, settings)
        return payload
    }

    private fun buildOpenAiResponsesImageTranslationPayload(
        settings: ApiSettings,
        modelName: String,
        imageBase64: String,
        promptAsset: String
    ): JSONObject {
        val llmParams = settingsStore.loadLlmParameters()
        val config = getPromptConfig(promptAsset)
        val input = JSONArray()
        for (message in config.exampleMessages) {
            input.put(
                JSONObject()
                    .put("role", mapOpenAiResponsesRole(message.role))
                    .put("content", message.content)
            )
        }
        input.put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "input_text")
                                .put(
                                    "text",
                                    config.userPromptPrefix.ifBlank {
                                        DEFAULT_IMAGE_TRANSLATION_USER_PROMPT
                                    }
                                )
                        )
                        .put(
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", "data:image/jpeg;base64,$imageBase64")
                        )
                )
        )
        val payload = JSONObject()
            .put("model", modelName)
            .put("input", input)
        if (config.systemPrompt.isNotBlank()) {
            payload.put("instructions", config.systemPrompt)
        }
        applyOpenAiResponsesSamplingParams(payload, llmParams)
        applyOpenAiThinkingParams(payload, llmParams)
        applyCustomRequestParameters(payload, settings)
        return payload
    }

    private fun applyOpenAiSamplingParams(
        payload: JSONObject,
        llmParams: LlmParameterSettings,
        settings: ApiSettings
    ) {
        llmParams.temperature?.let { payload.put("temperature", it) }
        llmParams.topP?.let { payload.put("top_p", it) }
        llmParams.topK?.let { payload.put("top_k", it) }
        // max_output_tokens belongs to the Responses API. Chat Completions uses the
        // broadly supported max_tokens name for the same user-facing setting.
        llmParams.maxOutputTokens?.let { payload.put("max_tokens", it) }
        llmParams.frequencyPenalty?.let { payload.put("frequency_penalty", it) }
        llmParams.presencePenalty?.let { payload.put("presence_penalty", it) }
    }

    private fun applyOpenAiResponsesSamplingParams(
        payload: JSONObject,
        llmParams: LlmParameterSettings
    ) {
        llmParams.temperature?.let { payload.put("temperature", it) }
        llmParams.topP?.let { payload.put("top_p", it) }
        llmParams.maxOutputTokens?.let { payload.put("max_output_tokens", it) }
    }

    private fun buildGeminiImageTranslationPayload(
        settings: ApiSettings,
        imageBase64: String,
        promptAsset: String
    ): JSONObject {
        val config = getPromptConfig(promptAsset)
        val userText = config.userPromptPrefix.ifBlank {
            DEFAULT_IMAGE_TRANSLATION_USER_PROMPT
        }
        val payload = JSONObject().put(
            "contents",
            buildGeminiContents(
                config,
                buildGeminiUserParts(
                    buildGeminiTextPart(userText),
                    buildGeminiInlineImagePart(imageBase64)
                )
            )
        )
        if (config.systemPrompt.isNotBlank()) {
            payload.put("systemInstruction", buildGeminiSystemInstruction(config.systemPrompt))
        }
        buildGeminiGenerationConfig(useJsonPayload = false)?.let {
            payload.put("generationConfig", it)
        }
        applyCustomRequestParameters(payload, settings)
        return payload
    }

    private fun applyCustomRequestParameters(payload: JSONObject, settings: ApiSettings) {
        if (settings.providerId.ifBlank { PRIMARY_PROVIDER_ID } != PRIMARY_PROVIDER_ID) return
        val parameters = settingsStore.loadCustomRequestParameters()
        if (parameters.isEmpty()) return
        val reservedKeys = ReservedRequestKeys.forFormat(settings.apiFormat)
        val seenKeys = LinkedHashSet<String>()
        parameters.forEach { parameter ->
            if (!parameter.enabled) return@forEach
            val key = parameter.key.trim()
            val value = parameter.value.trim()
            if (key.isBlank() && value.isBlank()) return@forEach
            if (key.isBlank()) {
                throw LlmRequestException(LlmErrorCode.CustomParamConflict, "blank key")
            }
            if (!seenKeys.add(key)) {
                throw LlmRequestException(LlmErrorCode.CustomParamConflict, key)
            }
            if (key in reservedKeys || payload.has(key)) {
                throw LlmRequestException(LlmErrorCode.CustomParamConflict, key)
            }
            payload.put(key, parseCustomRequestParameterValue(key, parameter.value))
        }
    }

    private fun parseCustomRequestParameterValue(key: String, rawValue: String): Any {
        val trimmed = rawValue.trim()
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false
        if (trimmed.equals("null", ignoreCase = true)) return JSONObject.NULL
        trimmed.toLongOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { return it }
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }
                .getOrElse { throw LlmRequestException(LlmErrorCode.CustomParamInvalidValue, key) }
        }
        if (trimmed.startsWith("[")) {
            return runCatching { JSONArray(trimmed) }
                .getOrElse { throw LlmRequestException(LlmErrorCode.CustomParamInvalidValue, key) }
        }
        return rawValue
    }

    private fun applyOpenAiThinkingParams(
        payload: JSONObject,
        llmParams: LlmParameterSettings
    ) {
        // enable_thinking / thinking_budget 均非 OpenAI 官方字段，而是硅基流动、通义千问、
        // 智谱 GLM 等兼容网关的扩展字段。与 fd47d62 对 Gemini 的处理不同：这里关闭思考时
        // 仍要显式发送 enable_thinking: false，因为部分供应商默认开启思考，省略字段等于
        // 关不掉；显式 false 才能保证关闭。该行为自 7fda9f4 起即为有意设计（不再按 URL
        // 白名单限制）。thinking_budget 仅在开启思考时发送，避免出现无效的 0 预算。
        payload.put("enable_thinking", llmParams.enableThinking)
        if (llmParams.enableThinking) {
            payload.put("thinking_budget", llmParams.thinkingLength.openAiBudgetTokens())
        }
    }

    private fun resolveGeminiThinkingBudget(llmParams: LlmParameterSettings): Int {
        if (!llmParams.enableThinking) return 0
        return llmParams.thinkingLength.geminiThinkingBudget()
    }

    private fun buildGeminiContents(config: LlmPromptConfig, userParts: JSONArray): JSONArray {
        val contents = JSONArray()
        for (message in config.exampleMessages) {
            val role = when (message.role.lowercase()) {
                "assistant", "model" -> "model"
                else -> "user"
            }
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", buildGeminiUserParts(buildGeminiTextPart(message.content)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", userParts)
        )
        return contents
    }

    private fun buildGeminiSystemInstruction(systemPrompt: String): JSONObject {
        return JSONObject().put("parts", buildGeminiUserParts(buildGeminiTextPart(systemPrompt)))
    }

    private fun buildGeminiUserParts(vararg parts: JSONObject): JSONArray {
        val array = JSONArray()
        parts.forEach { array.put(it) }
        return array
    }

    private fun buildGeminiTextPart(text: String): JSONObject {
        return JSONObject().put("text", text)
    }

    private fun buildGeminiInlineImagePart(imageBase64: String): JSONObject {
        return JSONObject().put(
            "inline_data",
            JSONObject()
                .put("mime_type", "image/jpeg")
                .put("data", imageBase64)
        )
    }

    private fun buildGeminiGenerationConfig(useJsonPayload: Boolean): JSONObject? {
        val llmParams = settingsStore.loadLlmParameters()
        val config = JSONObject()
        if (useJsonPayload) {
            config.put("responseMimeType", "application/json")
        }
        llmParams.temperature?.let { config.put("temperature", it) }
        llmParams.topP?.let { config.put("topP", it) }
        llmParams.topK?.let { config.put("topK", it) }
        llmParams.maxOutputTokens?.let { config.put("maxOutputTokens", it) }
        llmParams.frequencyPenalty?.let { config.put("frequencyPenalty", it) }
        llmParams.presencePenalty?.let { config.put("presencePenalty", it) }
        // Gemini rejects thinkingConfig on models/requests where thinking is disabled.
        // Omitting the block is also the API-compatible way to use the model default.
        if (llmParams.enableThinking) {
            config.put("thinkingConfig", buildGeminiThinkingConfig(llmParams))
        }
        return config.takeIf { it.length() > 0 }
    }

    private fun buildGeminiThinkingConfig(llmParams: LlmParameterSettings): JSONObject {
        return JSONObject().put("thinkingBudget", resolveGeminiThinkingBudget(llmParams))
    }

    private fun getPromptConfig(name: String): LlmPromptConfig {
        val resolvedName = PromptAssetResolver.resolve(appContext, name)
        val style = settingsStore.loadTranslationStyle()
        val cacheKey = "$resolvedName\u0000$style"
        return promptCache.getOrPut(cacheKey) { loadPromptConfig(resolvedName, style) }
    }

    private fun loadPromptConfig(name: String, styleHint: String): LlmPromptConfig {
        val json = JSONObject(readAsset(name))
        val systemPrompt = json.optString("system_prompt")
        val userPromptPrefix = json.optString("user_prompt_prefix")
        val examplesJson = json.optJSONArray("example_messages") ?: JSONArray()
        val examples = ArrayList<PromptMessage>(examplesJson.length())
        for (i in 0 until examplesJson.length()) {
            val messageObj = examplesJson.optJSONObject(i) ?: continue
            val role = messageObj.optString("role")
            val content = messageObj.optString("content")
                .replace("{{STYLE_HINT}}", styleHint)
            if (role.isNotBlank() && content.isNotBlank()) {
                examples.add(PromptMessage(role, content))
            }
        }
        return LlmPromptConfig(systemPrompt, userPromptPrefix, examples)
    }

    private fun readAsset(name: String): String {
        return appContext.assets.open(name).bufferedReader().use { it.readText() }
    }

    private fun buildGlossaryJson(glossary: Map<String, String>): JSONObject {
        val glossaryJson = JSONObject()
        for ((key, value) in glossary) {
            glossaryJson.put(key, value)
        }
        return glossaryJson
    }

    private fun sanitizeJsonValue(value: Any?): Any? {
        return when (value) {
            is JSONObject -> {
                val sanitized = JSONObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    sanitized.put(key, sanitizeJsonField(key, child))
                }
                sanitized
            }
            is JSONArray -> {
                JSONArray().also { array ->
                    for (i in 0 until value.length()) {
                        array.put(sanitizeJsonValue(value.opt(i)))
                    }
                }
            }
            else -> value
        }
    }

    private fun sanitizeJsonField(key: String, value: Any?): Any? {
        val normalizedKey = key.lowercase()
        return when {
            normalizedKey == "url" && value is String && value.startsWith("data:image/", ignoreCase = true) -> {
                "data:image/<base64 omitted>"
            }
            normalizedKey == "data" && value is String -> {
                "<base64 omitted>"
            }
            normalizedKey == "image_url" || normalizedKey == "inline_data" || normalizedKey == "inlinedata" -> {
                sanitizeJsonValue(value)
            }
            else -> sanitizeJsonValue(value)
        }
    }

    companion object {
        const val PROMPT_CONFIG_ASSET = "prompts/llm_prompts.json"
        const val OCR_PROMPT_CONFIG_ASSET = "prompts/ocr_prompts.json"
        const val DEFAULT_OCR_USER_PROMPT =
            "<image>\nExtract only visible text from this image. Do not describe objects, people, or scene. If no text is visible, return None."
        const val DEFAULT_IMAGE_TRANSLATION_USER_PROMPT =
            "Translate only the text visible in this manga bubble into Simplified Chinese. Output only the translated text."
    }
}

/** 各协议下禁止被自定义请求参数覆盖的保留键。与 LlmClient.reservedRequestKeys 同一实现。 */
internal object ReservedRequestKeys {
    fun forFormat(apiFormat: ApiFormat): Set<String> {
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> setOf(
                "model",
                "messages",
                "temperature",
                "top_p",
                "top_k",
                "max_tokens",
                "max_completion_tokens",
                "max_output_tokens",
                "frequency_penalty",
                "presence_penalty",
                "enable_thinking",
                "thinking_budget"
            )
            ApiFormat.OPENAI_RESPONSES -> setOf(
                "model",
                "input",
                "instructions",
                "temperature",
                "top_p",
                "max_tokens",
                "max_completion_tokens",
                "max_output_tokens",
                "enable_thinking",
                "thinking_budget"
            )
            ApiFormat.GEMINI -> setOf(
                "contents",
                "systemInstruction",
                "generationConfig"
            )
        }
    }
}

private data class LlmPromptConfig(
    val systemPrompt: String,
    val userPromptPrefix: String,
    val exampleMessages: List<PromptMessage>
)

private data class PromptMessage(
    val role: String,
    val content: String
)
