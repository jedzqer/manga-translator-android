package com.manga.translate.network

import com.manga.translate.model.ApiFormat
import com.manga.translate.platform.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 响应解析：按协议解析模型输出文本、结构化翻译内容（items/translation/glossary）、
 * 模型列表，以及错误体摘要。
 *
 * OpenAI Responses 优先读 output_text，再解析 output[].content[].text；
 * chat 读 choices/message/content；错误与错误码映射语义与原始实现一致。
 *
 * 截断检测（[isTruncatedResponse]）覆盖三家协议的 max token 截断信号，
 * 由 LlmClient 在内容解析成功后调用，避免截断响应被静默当作完整译文。
 */
internal class ResponseParser {
    fun parseResponseContent(body: String, apiFormat: ApiFormat): String? {
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE -> parseOpenAiResponseContent(body)
            ApiFormat.OPENAI_RESPONSES -> parseOpenAiResponsesContent(body)
            ApiFormat.GEMINI -> parseGeminiResponseContent(body)
        }
    }

    /**
     * 响应是否因达到最大输出 Token 而被截断：
     * - OpenAI 兼容 chat：choices[].finish_reason == "length"；
     * - OpenAI Responses：status == "incomplete"（当前唯一原因是 max_output_tokens）；
     * - Gemini：candidates[].finishReason == "MAX_TOKENS"。
     *
     * 解析失败一律返回 false（未截断），交由内容解析路径按原有错误分类处理。
     */
    fun isTruncatedResponse(body: String, apiFormat: ApiFormat): Boolean {
        return try {
            val json = JSONObject(body)
            when (apiFormat) {
                ApiFormat.OPENAI_COMPATIBLE ->
                    hasTruncationMarker(json.optJSONArray("choices"), "finish_reason", "length")
                ApiFormat.OPENAI_RESPONSES ->
                    json.optString("status").trim().equals("incomplete", ignoreCase = true)
                ApiFormat.GEMINI ->
                    hasTruncationMarker(json.optJSONArray("candidates"), "finishReason", "MAX_TOKENS")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun hasTruncationMarker(array: JSONArray?, key: String, truncatedValue: String): Boolean {
        if (array == null) return false
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (item.optString(key).trim().equals(truncatedValue, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun parseTranslationContent(content: String): LlmTranslationResult {
        val cleaned = stripCodeFence(content)
        val directFallback = parseTranslationFallback(cleaned)
        if (directFallback != null) {
            return directFallback
        }
        return try {
            val json = JSONObject(cleaned)
            val translation = extractTranslationText(json)
            if (translation.isBlank()) {
                AppLogger.log("LlmClient", "Missing translation field in response")
                throw LlmResponseException(LlmErrorCode.MissingTranslation, content)
            }
            LlmTranslationResult(translation, parseGlossaryUsed(json))
        } catch (e: LlmResponseException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(
                "LlmClient",
                "Invalid translation response format: ${summarizeBody(content)}",
                e
            )
            throw LlmResponseException(LlmErrorCode.InvalidFormat, content, e)
        }
    }

    fun parseBubbleTranslationContent(
        content: String,
        requestedIds: List<Int>
    ): LlmBubbleTranslationResult {
        val cleaned = stripCodeFence(content)
        val directFallback = parseBubbleTranslationFallback(cleaned, requestedIds)
        if (directFallback != null) {
            return directFallback
        }
        return try {
            if (cleaned.trim().startsWith("[")) {
                val items = parseBubbleTranslationItems(JSONArray(cleaned))
                if (items.isEmpty()) {
                    throw LlmResponseException(LlmErrorCode.MissingTranslationItems, content)
                }
                return LlmBubbleTranslationResult(items = items, glossaryUsed = emptyMap())
            }
            val json = JSONObject(cleaned)
            val items = extractBubbleTranslationItems(json, requestedIds)
            if (items.isEmpty()) {
                AppLogger.log("LlmClient", "Missing items field in structured translation response")
                throw LlmResponseException(LlmErrorCode.MissingTranslationItems, content)
            }
            LlmBubbleTranslationResult(items = items, glossaryUsed = parseGlossaryUsed(json))
        } catch (e: LlmResponseException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(
                "LlmClient",
                "Invalid structured translation response format: ${summarizeBody(content)}",
                e
            )
            throw LlmResponseException(LlmErrorCode.InvalidFormat, content, e)
        }
    }

    fun parseImageTranslationContent(content: String): String? {
        val cleaned = stripCodeFence(content).trim()
        if (cleaned.isBlank()) return null
        return try {
            parseTranslationContent(cleaned).translation.trim().ifBlank { null }
        } catch (_: Exception) {
            // Some compatible providers may still return plain text for image translation.
            cleaned.ifBlank { null }
        }
    }

    fun parseGlossaryContent(content: String): Map<String, String> {
        return try {
            val cleaned = stripCodeFence(content)
            val json = JSONObject(cleaned)
            parseGlossaryUsed(json)
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Glossary parse failed", e)
            emptyMap()
        }
    }

    fun parseModelList(body: String, apiFormat: ApiFormat): List<String> {
        return when (apiFormat) {
            ApiFormat.OPENAI_COMPATIBLE,
            ApiFormat.OPENAI_RESPONSES -> parseOpenAiModelList(body)
            ApiFormat.GEMINI -> parseGeminiModelList(body)
        }
    }

    fun summarizeBody(body: String?, limit: Int = 600): String {
        if (body.isNullOrBlank()) return "(empty)"
        val normalized = body.replace("\n", " ").replace("\r", " ").trim()
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "...(truncated)"
    }

    private fun parseOpenAiResponseContent(body: String): String? {
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

    private fun parseOpenAiResponsesContent(body: String): String? {
        return try {
            val json = JSONObject(body)
            val outputText = json.optString("output_text").trim()
            if (outputText.isNotBlank()) {
                return outputText
            }
            val output = json.optJSONArray("output") ?: return null
            val parts = ArrayList<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                val type = item.optString("type").trim().lowercase()
                if (type.isNotBlank() && type != "message") continue
                val content = item.opt("content")
                when (content) {
                    is String -> if (content.isNotBlank()) parts.add(content.trim())
                    is JSONArray -> {
                        for (j in 0 until content.length()) {
                            when (val part = content.opt(j)) {
                                is String -> if (part.isNotBlank()) parts.add(part.trim())
                                is JSONObject -> {
                                    val partType = part.optString("type").trim().lowercase()
                                    if (
                                        partType.isNotBlank() &&
                                        partType != "output_text" &&
                                        partType != "text"
                                    ) {
                                        continue
                                    }
                                    val text = part.optString("text").trim()
                                    if (text.isNotBlank()) parts.add(text)
                                }
                            }
                        }
                    }
                }
            }
            parts.joinToString("\n").trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseGeminiResponseContent(body: String): String? {
        return try {
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates") ?: return null
            val first = candidates.optJSONObject(0) ?: return null
            val content = first.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val texts = ArrayList<String>(parts.length())
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                if (text.isNotBlank()) {
                    texts.add(text)
                }
            }
            texts.joinToString("\n").trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTranslationFallback(content: String): LlmTranslationResult? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
        // Some OpenAI-compatible providers still return the translation as plain text.
        return LlmTranslationResult(trimmed, emptyMap())
    }

    private fun parseBubbleTranslationFallback(
        content: String,
        requestedIds: List<Int>
    ): LlmBubbleTranslationResult? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return null
        val singleId = requestedIds.singleOrNull() ?: return null
        return LlmBubbleTranslationResult(
            items = listOf(LlmBubbleTranslationItem(id = singleId, translation = trimmed)),
            glossaryUsed = emptyMap()
        )
    }

    private fun extractBubbleTranslationItems(
        json: JSONObject,
        requestedIds: List<Int>
    ): List<LlmBubbleTranslationItem> {
        findBubbleTranslationItemsArray(json)?.let { array ->
            return parseBubbleTranslationItems(array)
        }
        val singleId = requestedIds.singleOrNull()
        val translation = extractStructuredTranslationText(json)
        if (singleId != null && translation.isNotBlank()) {
            return listOf(LlmBubbleTranslationItem(id = singleId, translation = translation))
        }
        return emptyList()
    }

    private fun findBubbleTranslationItemsArray(json: JSONObject): JSONArray? {
        val directKeys = listOf("items", "translations", "translation_items", "translationItems")
        for (key in directKeys) {
            json.optJSONArray(key)?.let { return it }
        }
        val nestedKeys = listOf("data", "result", "output", "response", "message")
        for (key in nestedKeys) {
            val nested = json.optJSONObject(key) ?: continue
            findBubbleTranslationItemsArray(nested)?.let { return it }
        }
        return null
    }

    private fun parseBubbleTranslationItems(array: JSONArray): List<LlmBubbleTranslationItem> {
        val items = ArrayList<LlmBubbleTranslationItem>(array.length())
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is JSONObject -> {
                    val id = parseBubbleTranslationItemId(item.opt("id"))
                    val translation = extractStructuredTranslationText(item).trim()
                    if (id != null) {
                        items.add(LlmBubbleTranslationItem(id = id, translation = translation))
                    }
                }
            }
        }
        return items
    }

    private fun parseBubbleTranslationItemId(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun extractTranslationText(json: JSONObject): String {
        val directKeys = listOf("translation", "translated_text", "translatedText", "text", "content")
        for (key in directKeys) {
            val value = json.opt(key)
            when (value) {
                is String -> value.trim().takeIf { it.isNotBlank() }?.let { return it }
                is JSONObject -> extractTranslationText(value).takeIf { it.isNotBlank() }?.let { return it }
                is JSONArray -> joinJsonText(value).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        val nestedKeys = listOf("data", "result", "output", "response", "message")
        for (key in nestedKeys) {
            val nested = json.optJSONObject(key) ?: continue
            extractTranslationText(nested).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun extractStructuredTranslationText(json: JSONObject): String {
        val translationKeys = listOf("translation", "translated_text", "translatedText")
        for (key in translationKeys) {
            val value = json.optString(key, "").trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun joinJsonText(array: JSONArray): String {
        val parts = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            when (val item = array.opt(i)) {
                is String -> item.trim().takeIf { it.isNotBlank() }?.let(parts::add)
                is JSONObject -> extractTranslationText(item).takeIf { it.isNotBlank() }?.let(parts::add)
            }
        }
        return parts.joinToString("\n").trim()
    }

    private fun parseGlossaryUsed(json: JSONObject): Map<String, String> {
        json.optJSONObject("glossary_used")?.let { return parseGlossaryJson(it) }
        val nestedKeys = listOf("data", "result", "output", "response", "message")
        for (key in nestedKeys) {
            val nested = json.optJSONObject(key) ?: continue
            parseGlossaryUsed(nested).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyMap()
    }

    private fun parseGlossaryJson(glossaryJson: JSONObject): Map<String, String> {
        val glossary = mutableMapOf<String, String>()
        for (key in glossaryJson.keys()) {
            val value = glossaryJson.optString(key).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                glossary[key] = value
            }
        }
        return glossary
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

    private fun parseOpenAiModelList(body: String): List<String> {
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

    private fun parseGeminiModelList(body: String): List<String> {
        return try {
            val json = JSONObject(body)
            val modelsJson = json.optJSONArray("models") ?: return emptyList()
            val models = ArrayList<String>(modelsJson.length())
            for (i in 0 until modelsJson.length()) {
                val item = modelsJson.optJSONObject(i) ?: continue
                val id = item.optString("baseModelId").trim().ifBlank {
                    item.optString("name").trim().removePrefix("models/")
                }
                if (id.isNotBlank()) {
                    models.add(id)
                }
            }
            models
        } catch (e: Exception) {
            AppLogger.log("LlmClient", "Gemini model list parse failed", e)
            emptyList()
        }
    }
}
