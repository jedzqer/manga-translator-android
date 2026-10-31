package com.manga.translate.settings

import org.json.JSONArray
import org.json.JSONObject

/** Model capabilities in tokens, not per-request allocations. Null means unknown, never unlimited. */
data class ModelContextLimits(
    val maxInputTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val contextWindowTokens: Int? = null
) {
    init {
        require(listOf(maxInputTokens, maxOutputTokens, contextWindowTokens).all { it == null || it > 0 })
    }

    // Independent advertised maxima need not add up to the total context window.
    val hasUnknownLimits: Boolean
        get() = maxInputTokens == null || maxOutputTokens == null || contextWindowTokens == null
}

/** Exact deployment identity: only surrounding whitespace is ignored. API keys are not identities. */
data class ModelContextIdentity(
    val endpoint: String,
    val apiFormat: String,
    val modelName: String,
    val providerId: String
) {
    companion object {
        fun from(settings: ApiSettings) = ModelContextIdentity(
            settings.apiUrl.trim(), settings.apiFormat.prefValue,
            settings.modelName.trim(), settings.providerId
        )
    }
}

data class ModelContextEntry(val identity: ModelContextIdentity, val limits: ModelContextLimits)

/** Capture this together with request settings; do not re-read a different profile mid-task. */
data class ModelContextSettings(
    val profileName: String?,
    val identity: ModelContextIdentity,
    val limits: ModelContextLimits
) {
    /** User overrides stay nullable on disk; consumers use this complete fallback snapshot. */
    val effectiveLimits: EffectiveModelContextLimits
        get() = EffectiveModelContextLimits(
            limits.maxInputTokens ?: ModelContextDefaults.MAX_INPUT_TOKENS,
            limits.maxOutputTokens ?: ModelContextDefaults.MAX_OUTPUT_TOKENS,
            limits.contextWindowTokens ?: ModelContextDefaults.CONTEXT_WINDOW_TOKENS
        )
}

data class EffectiveModelContextLimits(
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val contextWindowTokens: Int
)

/** Conservative application defaults, not inferred or verified provider capabilities.
 * Context baseline: https://huggingface.co/Qwen/Qwen3.5-397B-A17B (native 262,144).
 * Output baseline: Qwen3.5 Plus / Flash 64K output, documented at
 * https://www.alibabacloud.com/help/en/model-studio/vision-model
 * Request budgeting must still
 * reserve output and safety margin inside the shared context window.
 */
object ModelContextDefaults {
    const val MAX_INPUT_TOKENS = 262144
    const val MAX_OUTPUT_TOKENS = 65536
    const val CONTEXT_WINDOW_TOKENS = 262144
}

internal object ModelContextCodec {
    fun encode(entries: List<ModelContextEntry>): JSONArray = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("endpoint", entry.identity.endpoint)
                put("apiFormat", entry.identity.apiFormat)
                put("modelName", entry.identity.modelName)
                put("providerId", entry.identity.providerId)
                put("maxInputTokens", entry.limits.maxInputTokens)
                put("maxOutputTokens", entry.limits.maxOutputTokens)
                put("contextWindowTokens", entry.limits.contextWindowTokens)
            })
        }
    }

    fun decode(array: JSONArray?): List<ModelContextEntry> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            fun identity(key: String) = (item.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val endpoint = identity("endpoint") ?: continue
            val format = identity("apiFormat") ?: continue
            val model = identity("modelName") ?: continue
            val provider = identity("providerId") ?: continue
            // Do not coerce fractions, strings, zero, negatives or overflowing integers into limits.
            fun tokens(key: String): Int? = when (val value = item.opt(key)) {
                is Int -> value.takeIf { it > 0 }
                is Long -> value.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                else -> null
            }
            add(ModelContextEntry(
                ModelContextIdentity(endpoint, format, model, provider),
                ModelContextLimits(tokens("maxInputTokens"), tokens("maxOutputTokens"), tokens("contextWindowTokens"))
            ))
        }
    }.let { entries ->
        // Ambiguous duplicate identities are unknown rather than choosing an arbitrary imported value.
        entries.groupBy { it.identity }.values.filter { it.size == 1 }.map { it.single() }
    }
}
