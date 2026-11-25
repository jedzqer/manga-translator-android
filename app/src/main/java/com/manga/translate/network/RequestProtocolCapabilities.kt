package com.manga.translate.network

import com.manga.translate.model.ApiFormat
import com.manga.translate.settings.ApiSettings
import java.net.URI
import org.json.JSONObject

/** Field names and accounting for the actual request endpoint, protocol and model. */
internal class RequestProtocolCapabilities private constructor(
    val format: ApiFormat,
    val outputKey: String,
    val sharedReasoning: Boolean,
    val chatGatewayThinking: Boolean = false,
    val efforts: Set<String>? = null, // null: compatibility gateway controls validation
    val geminiThinking: GeminiThinking = GeminiThinking.LEGACY,
    val trustedUsage: Boolean = false,
    val trustedTextInputUsage: Boolean = false
) {
    enum class GeminiThinking { LEGACY, BUDGET_PRO, BUDGET_FLASH, LEVEL_PRO, LEVEL_FLASH }

    fun outputConfig(payload: JSONObject): JSONObject? =
        if (format == ApiFormat.GEMINI) payload.optJSONObject("generationConfig") else payload

    fun outputLimit(payload: JSONObject): Int? = outputConfig(payload)?.let { config ->
        if (config.has(outputKey)) config.getInt(outputKey) else null
    }

    fun fixedThinking(payload: JSONObject): Int? = when (format) {
        ApiFormat.OPENAI_COMPATIBLE -> if (payload.opt("enable_thinking") == false ||
            payload.opt("reasoning_effort") == "none") 0 else payload.optInt("thinking_budget", -1)
        ApiFormat.OPENAI_RESPONSES -> if (payload.optJSONObject("reasoning")?.optString("effort") == "none") 0 else -1
        ApiFormat.GEMINI -> outputConfig(payload)?.optJSONObject("thinkingConfig")?.optInt("thinkingBudget", -1) ?: -1
    }.takeIf { it >= 0 }

    fun usageAgainstLimit(output: Int, reasoning: Int?): Long =
        if (format == ApiFormat.GEMINI && sharedReasoning) output.toLong() + (reasoning ?: 0) else output.toLong()

    companion object {
        const val CHAT_OUTPUT_OVERRIDE = "manga_translate.output_token_field"
        val CHAT_OUTPUT_KEYS = setOf("max_tokens", "max_completion_tokens")
        private val dated = Regex("-20\\d{2}-\\d{2}-\\d{2}$")
        private val baseEfforts = setOf("low", "medium", "high")
        private val gpt51 = baseEfforts + "none"
        private val gpt52 = gpt51 + "xhigh"

        fun resolve(settings: ApiSettings, chatOutputOverride: String? = null): RequestProtocolCapabilities {
            val model = settings.modelName.trim()
            val endpoint = runCatching { URI(EndpointBuilder.buildEndpoint(settings.copy(apiKey = ""), model)) }.getOrNull()
            val secure = endpoint != null && endpoint.scheme.equals("https", true) &&
                endpoint.userInfo == null && endpoint.port in setOf(-1, 443) && endpoint.fragment == null
            val path = endpoint?.rawPath
            val openAi = secure && endpoint?.host.equals("api.openai.com", true) && endpoint?.query == null
            return when (settings.apiFormat) {
                ApiFormat.OPENAI_COMPATIBLE -> {
                    val official = openAi && path == "/v1/chat/completions"
                    val key = chatOutputOverride ?: if (official) "max_completion_tokens" else "max_tokens"
                    RequestProtocolCapabilities(settings.apiFormat, key, key == "max_completion_tokens",
                        chatGatewayThinking = !official, trustedUsage = official && key == "max_completion_tokens",
                        trustedTextInputUsage = official)
                }
                ApiFormat.OPENAI_RESPONSES -> {
                    val official = openAi && path == "/v1/responses"
                    val efforts = if (official) responsesEfforts(model.replace(dated, "")) else null
                    RequestProtocolCapabilities(settings.apiFormat, "max_output_tokens", true, efforts = efforts,
                        trustedUsage = official, trustedTextInputUsage = official)
                }
                ApiFormat.GEMINI -> {
                    // The actual URL can fix a model regardless of the profile's model picker value.
                    val endpointModel = path?.let { Regex("^/(v1|v1beta)/models/([^/]+):generateContent$")
                        .matchEntire(it)?.groupValues?.get(2) }
                    val selected = model.removePrefix("/").removePrefix("models/")
                    val official = secure && endpoint?.host.equals("generativelanguage.googleapis.com", true) &&
                        endpointModel == selected
                    val thinking = if (official) when (selected) {
                        "gemini-2.5-pro" -> GeminiThinking.BUDGET_PRO
                        "gemini-2.5-flash", "gemini-2.5-flash-lite" -> GeminiThinking.BUDGET_FLASH
                        "gemini-3-pro-preview", "gemini-3.1-pro-preview" -> GeminiThinking.LEVEL_PRO
                        "gemini-3-flash-preview" -> GeminiThinking.LEVEL_FLASH
                        else -> GeminiThinking.LEGACY
                    } else GeminiThinking.LEGACY
                    val known = thinking != GeminiThinking.LEGACY
                    RequestProtocolCapabilities(settings.apiFormat, "maxOutputTokens", known,
                        geminiThinking = thinking, trustedUsage = known,
                        trustedTextInputUsage = official && endpoint?.rawQuery == null)
                }
            }
        }

        private fun responsesEfforts(model: String): Set<String> = when (model) {
            "gpt-5", "gpt-5-mini", "gpt-5-nano" -> baseEfforts + "minimal"
            "gpt-5.1" -> gpt51
            "gpt-5.2", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano" -> gpt52
            "o3", "o3-mini", "o4-mini" -> baseEfforts
            // Unknown/unsupported official model: allow the provider to choose its default.
            else -> emptySet()
        }
    }
}
