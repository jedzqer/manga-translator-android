package com.manga.translate.network

import com.manga.translate.model.ApiFormat
import com.manga.translate.settings.ModelContextSettings
import com.manga.translate.translation.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** One immutable template: prompts, examples, sampling, custom parameters and capability snapshot.
 * Only the final user payload changes between subrequests. Count the actual serialized payload
 * (including escaping and custom fields), deliberately overestimating transport-only fields.
 */
internal class BudgetedTextTemplate(
    template: JSONObject,
    private val format: ApiFormat,
    private val context: ModelContextSettings,
    defaultOutputTokens: Int
) {
    private val frozen: String
    private val output: TextOutputReserve

    init {
        val copy = JSONObject(template.toString())
        val config = if (format == ApiFormat.GEMINI) {
            copy.optJSONObject("generationConfig") ?: JSONObject().also { copy.put("generationConfig", it) }
        } else copy
        val key = when (format) {
            ApiFormat.OPENAI_COMPATIBLE -> "max_tokens"
            ApiFormat.OPENAI_RESPONSES -> "max_output_tokens"
            ApiFormat.GEMINI -> "maxOutputTokens"
        }
        val defaultCap = minOf(defaultOutputTokens, context.effectiveLimits.maxOutputTokens)
        val explicitCap = if (config.has(key)) config.getInt(key) else null
        val thinking = if (format == ApiFormat.GEMINI) {
            config.optJSONObject("thinkingConfig")?.optInt("thinkingBudget", -1) ?: 0
        } else if (copy.optBoolean("enable_thinking")) copy.optInt("thinking_budget", explicitCap ?: defaultCap) else 0
        // Automatic caps include a fixed reasoning allowance without consuming the task's
        // content allowance. Explicit user caps remain total caps. AUTO has no fixed allowance.
        val automaticCap = defaultCap.toLong() + thinking.coerceAtLeast(0)
        if (explicitCap == null && automaticCap > context.effectiveLimits.maxOutputTokens) {
            throw LlmResponseException(LlmErrorCode.Custom("TEXT_BUDGET_EXCEEDED"),
                "OUTPUT_LIMIT: content=$defaultCap; reasoning=$thinking; maxOutput=${context.effectiveLimits.maxOutputTokens}",
                retryable = false)
        }
        val cap = explicitCap ?: automaticCap.toInt()
        require(cap > 0)
        config.put(key, cap)
        // Also reserve reasoning separately in the context for providers that exclude it from
        // their output cap. AUTO conservatively reserves the whole cap as reasoning.
        val reasoning = if (thinking < 0) cap else thinking
        output = TextOutputReserve(cap, 0, reasoning, reasoningCountsTowardOutputLimit = false)
        frozen = copy.toString()
    }

    fun payload(user: String): JSONObject = JSONObject(frozen).also { root ->
        when (format) {
            ApiFormat.OPENAI_COMPATIBLE, ApiFormat.OPENAI_RESPONSES -> {
                val messages = root.getJSONArray(if (format == ApiFormat.OPENAI_COMPATIBLE) "messages" else "input")
                val message = messages.getJSONObject(messages.length() - 1)
                message.put("content", message.getString("content") + user)
            }
            ApiFormat.GEMINI -> {
                val contents = root.getJSONArray("contents")
                val part = contents.getJSONObject(contents.length() - 1).getJSONArray("parts").getJSONObject(0)
                part.put("text", part.getString("text") + user)
            }
        }
    }

    fun check(payload: JSONObject): TextBatchPlan = TextRequestBudget(
        context, output, SAFETY_TOKENS,
        TextRequestEnvelope(listOf(payload.toString()), "", PROTOCOL_TOKENS)
    ).plan(emptyList())

    fun shrink(units: List<TextBudgetUnit>): List<List<TextBudgetUnit>> = splitTextBudgetUnits(units)

    companion object {
        const val SAFETY_TOKENS = 1024
        const val PROTOCOL_TOKENS = 256
    }
}

/** All HTTP attempts, including network and malformed-response retries, consume this same counter. */
internal class TextAttemptBudget(private var remaining: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TextAttemptBudget>
    @Synchronized fun consume() {
        if (remaining-- <= 0) throw LlmRequestException(LlmErrorCode.Custom("TEXT_ATTEMPTS_EXHAUSTED"))
    }
}

/** Ordered greedy packing with whole-source adaptive retry. The caller owns the common deadline.
 * onSuccess runs serially only after parse/ID validation. No partial result is returned on failure.
 */
internal suspend fun <T> executeBudgetedText(
    units: List<TextBudgetUnit>,
    template: BudgetedTextTemplate,
    buildUser: (List<TextBudgetUnit>) -> String,
    send: suspend (JSONObject, List<TextBudgetUnit>) -> T,
    onSuccess: suspend (T) -> Unit = {}
): List<T> {
    require(units.map { it.sourceId }.distinct().size == units.size)
    val results = mutableListOf<T>()
    suspend fun checkedSend(batch: List<TextBudgetUnit>) {
        val pending = java.util.ArrayDeque<List<TextBudgetUnit>>()
        pending.add(batch)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val next = pending.removeFirst()
            val payload = template.payload(buildUser(next))
            val rejection = template.check(payload) as? TextBatchPlan.Rejected
            if (rejection != null) {
                if (next.size == 1 || rejection.reason == TextBudgetFailureReason.OUTPUT_LIMIT ||
                    rejection.reason == TextBudgetFailureReason.CONTEXT_EXHAUSTED) {
                    throw LlmResponseException(LlmErrorCode.Custom("TEXT_BUDGET_EXCEEDED"),
                        "${rejection.reason}: ${next.first().sourceId}; input=${rejection.budget.inputTokens}, available=${rejection.budget.availableInputTokens}",
                        retryable = false)
                }
                template.shrink(next).asReversed().forEach { pending.addFirst(it) }
                continue
            }
            var attempts = 0
            while (true) {
                try {
                    val result = send(payload, next)
                    onSuccess(result)
                    results += result
                    break
                } catch (failure: LlmResponseException) {
                    if (!failure.retryable) throw failure
                    val shrink = failure.errorCode == LlmErrorCode.ResponseTruncated ||
                        failure.errorCode.value == "CONTEXT_LENGTH_EXCEEDED"
                    if (shrink && next.size > 1) {
                        template.shrink(next).asReversed().forEach { pending.addFirst(it) }
                        break
                    }
                    if (shrink || ++attempts >= 3) throw LlmResponseException(
                        failure.errorCode, failure.responseContent, failure, retryable = false)
                }
            }
        }
    }
    var start = 0
    while (start < units.size) {
        currentCoroutineContext().ensureActive()
        var end = start + 1
        while (end < units.size && template.check(template.payload(buildUser(units.subList(start, end + 1))))
            is TextBatchPlan.Ready) end++
        checkedSend(units.subList(start, end).toList())
        start = end
    }
    return results
}

/** Narrow evidence only; auth errors, arbitrary invalid JSON and generic 400s never trigger splitting. */
internal fun isContextOverflow(status: Int, body: String): Boolean {
    if (status !in listOf(400, 413, 422)) return false
    val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull() ?: return false
    if (error.optString("code") in setOf("context_length_exceeded", "context_window_exceeded")) return true
    val message = error.optString("message").lowercase()
    return message.contains("maximum context length") ||
        (message.contains("input token count") && message.contains("exceeds"))
}
