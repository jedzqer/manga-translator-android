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
    val protocol: RequestProtocolCapabilities,
    private val context: ModelContextSettings,
    private val estimatedContentTokens: Int,
    private val inputEstimator: TextTokenEstimator = Utf8TextTokenEstimator
) {
    private val format = protocol.format
    private val frozen = template.toString()
    val hasInputLimit = context.limits.maxInputTokens != null || context.limits.contextWindowTokens != null ||
        protocol.sharedReasoning && protocol.fixedThinking(template)?.let { it > 0 } == true &&
        (protocol.outputLimit(template) != null || context.limits.maxOutputTokens != null)

    private fun reserve(payload: JSONObject, minimum: Int, reduced: Boolean): TextOutputReserve {
        val cap = protocol.outputLimit(payload)
        val fixed = protocol.fixedThinking(payload)
        val knownLimit = context.limits.maxOutputTokens ?: Int.MAX_VALUE
        val sharedFixed = if (protocol.sharedReasoning) fixed ?: 0 else 0
        val estimated = if (reduced) maxOf(minimum, MIN_ESTIMATED_CONTENT_TOKENS) else estimatedContentTokens
        val wanted = estimated.toLong() + (if (fixed == null && !reduced) estimated else 0)
        val visible = if (cap != null) cap.toLong() - sharedFixed else minOf(wanted,
            knownLimit.toLong() - sharedFixed).coerceAtLeast(1)
        return TextOutputReserve(
            contentTokens = if (sharedFixed > 0) maxOf(visible, minimum.toLong()).toInt() else visible.coerceAtLeast(1).toInt(),
            structureTokens = 0,
            reasoningTokens = fixed ?: 0,
            reasoningCountsTowardOutputLimit = protocol.sharedReasoning
        )
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

    fun check(payload: JSONObject, singlePage: Boolean = false, minimum: Int = 12): TextBatchPlan {
        val envelope = TextRequestEnvelope(listOf(payload.toString()), "", PROTOCOL_TOKENS)
        fun budget(reserve: TextOutputReserve) = TextRequestBudget(context, reserve, SAFETY_TOKENS, envelope, inputEstimator)
        val output = reserve(payload, minimum, reduced = false)
        val initial = budget(output)
        val cap = protocol.outputLimit(payload)
        // Fixed reasoning shares this cap. Only a small per-item structural allowance is hard;
        // the much larger content estimate is used for packing, not output rejection.
        if (cap != null && output.outputLimitTokens > cap) {
            return TextBatchPlan.Rejected(TextBudgetFailureReason.OUTPUT_LIMIT, initial.fixedBudgetDetails())
        }
        val result = initial.plan(emptyList())
        return if (singlePage && cap == null && context.limits.contextWindowTokens != null && result is TextBatchPlan.Rejected) {
            budget(reserve(payload, minimum, reduced = true)).plan(emptyList())
        } else result
    }

    fun recoverTruncated(payload: JSONObject, failure: LlmResponseException, minimum: Int = 12): JSONObject? {
        val evidence = failure.cause as? TruncatedTokenUsage ?: return null
        val cap = protocol.outputLimit(payload) ?: return null
        val ceiling = context.limits.maxOutputTokens ?: return null
        if (!protocol.trustedUsage || cap <= 0 || cap >= ceiling ||
            protocol.usageAgainstLimit(evidence.output, evidence.reasoning) < cap) return null
        val larger = minOf(ceiling.toLong(), cap.toLong() + maxOf(128, cap / 4)).toInt()
        return JSONObject(payload.toString()).apply { protocol.outputConfig(this)?.put(protocol.outputKey, larger) }
            .takeIf { check(it, singlePage = true, minimum = minimum) is TextBatchPlan.Ready }
    }

    fun shrink(units: List<TextBudgetUnit>): List<List<TextBudgetUnit>> = splitTextBudgetUnits(units)

    companion object {
        const val SAFETY_TOKENS = 1024
        const val PROTOCOL_TOKENS = 256
        private const val MIN_ESTIMATED_CONTENT_TOKENS = 1024
    }
}

internal class TruncatedTokenUsage(val output: Int, val reasoning: Int?) : Exception()

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
    minimumOutput: (List<TextBudgetUnit>) -> Int = { 12 },
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
            var payload = template.payload(buildUser(next))
            val minimum = minimumOutput(next)
            val rejection = template.check(payload, singlePage = next.size == 1, minimum = minimum) as? TextBatchPlan.Rejected
            if (rejection != null) {
                if (next.size == 1) {
                    throw LlmResponseException(LlmErrorCode.Custom("TEXT_BUDGET_EXCEEDED"),
                        "${rejection.reason}: ${next.first().sourceId}; input=${rejection.budget.inputTokens}, available=${rejection.budget.availableInputTokens}",
                        retryable = false)
                }
                template.shrink(next).asReversed().forEach { pending.addFirst(it) }
                continue
            }
            var attempts = 0
            var adjusted = false
            while (true) {
                val result = try {
                    send(payload, next)
                } catch (failure: LlmResponseException) {
                    if (!failure.retryable) throw failure
                    if (!adjusted && failure.errorCode == LlmErrorCode.ResponseTruncated) {
                        adjusted = true
                        val recovery = template.recoverTruncated(payload, failure, minimum)
                        if (recovery != null) {
                            payload = recovery
                            continue
                        }
                    }
                    val shrink = failure.errorCode == LlmErrorCode.ResponseTruncated ||
                        failure.errorCode.value == "CONTEXT_LENGTH_EXCEEDED"
                    if (shrink && next.size > 1) {
                        template.shrink(next).asReversed().forEach { pending.addFirst(it) }
                        break
                    }
                    if (shrink || ++attempts >= 3) throw LlmResponseException(
                        failure.errorCode, failure.responseContent, failure, retryable = false)
                    continue
                }
                onSuccess(result)
                results += result
                break
            }
        }
    }
    var start = 0
    while (start < units.size) {
        currentCoroutineContext().ensureActive()
        var end = if (template.hasInputLimit) start + 1 else units.size
        while (end < units.size) {
            val batch = units.subList(start, end + 1)
            if (template.check(template.payload(buildUser(batch)), minimum = minimumOutput(batch)) !is TextBatchPlan.Ready) break
            end++
        }
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
