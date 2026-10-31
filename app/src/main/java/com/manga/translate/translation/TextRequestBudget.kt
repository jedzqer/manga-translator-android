package com.manga.translate.translation

import com.manga.translate.settings.ModelContextSettings

/** Estimates only supplied text; chat framing and hidden server instructions need separate reserves.
 * Implementations must return nonnegative, conservative counts for independently encoded fragments.
 * Counts are estimates, never authoritative server usage.
 */
internal fun interface TextTokenEstimator {
    fun estimate(text: String): Long
}

/** Byte-level BPE starts with bytes and merges them. Counting unmerged UTF-8 bytes is deliberately
 * conservative, including rare scripts and emoji, without a vocabulary download. Normalization and
 * server templates can add tokens, so this is not a universal tokenizer upper bound.
 * See https://huggingface.co/learn/llm-course/en/chapter6/5
 */
internal object Utf8TextTokenEstimator : TextTokenEstimator {
    override fun estimate(text: String): Long {
        var count = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            count += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) && index < text.length &&
                    Character.isLowSurrogate(text[index]) -> { index++; 4 }
                // Unpaired surrogates are budgeted as a replacement character (three bytes).
                else -> 3
            }
        }
        return count
    }
}

/** Caller chooses task-specific allowances for translation, glossary extraction or summary.
 * reasoningTokens is included in the context reserve; whether it also consumes the advertised
 * output limit is an explicit provider policy, not inferred from the model name.
 */
internal data class TextOutputReserve(
    val contentTokens: Int,
    val structureTokens: Int,
    val reasoningTokens: Int = 0,
    val reasoningCountsTowardOutputLimit: Boolean = true
) {
    init {
        require(contentTokens > 0 && structureTokens >= 0 && reasoningTokens >= 0)
    }
    val contextTokens: Long get() = contentTokens.toLong() + structureTokens + reasoningTokens
    val outputLimitTokens: Long get() = contentTokens.toLong() + structureTokens +
        if (reasoningCountsTowardOutputLimit) reasoningTokens else 0
}

/** All fixed text must already be expanded, including examples, glossary and JSON wrappers.
 * Units contain serialized/escaped JSON when that is what the actual request sends.
 * Fragment estimates are summed conservatively; separator is charged only between units.
 */
internal data class TextRequestEnvelope(
    val fixedText: List<String>,
    val separator: String,
    val protocolTokens: Int
) {
    init { require(protocolTokens >= 0) }
}

/** One indivisible source, normally all bubbles from one image. Never pass individual bubbles
 * from the same image as separate units. Ordering is the caller's page order, not sourceId order.
 */
internal data class TextBudgetUnit(val sourceId: String, val serializedText: String)

internal data class TextBudgetDetails(
    val availableInputTokens: Long,
    val fixedInputTokens: Long,
    val bodyInputTokens: Long,
    val outputReserveTokens: Long,
    val safetyTokens: Int,
    val usesFallbackLimits: Boolean,
    val isEstimate: Boolean = true
) {
    val inputTokens: Long get() = Math.addExact(fixedInputTokens, bodyInputTokens)
    val remainingInputTokens: Long get() = availableInputTokens - inputTokens
}

internal data class TextBudgetBatch(val units: List<TextBudgetUnit>, val budget: TextBudgetDetails)

internal enum class TextBudgetFailureReason {
    OUTPUT_LIMIT, CONTEXT_EXHAUSTED, FIXED_INPUT_TOO_LARGE, UNIT_TOO_LARGE, INDIVISIBLE_UNIT
}

internal sealed interface TextBatchPlan {
    data class Ready(val batches: List<TextBudgetBatch>) : TextBatchPlan
    /** No partial sendable plan is returned on failure. */
    data class Rejected(
        val reason: TextBudgetFailureReason,
        val budget: TextBudgetDetails,
        val sourceId: String? = null
    ) : TextBatchPlan
}

/** Pure planning only: no network, storage, truncation or retry loops. */
internal class TextRequestBudget(
    settings: ModelContextSettings,
    private val output: TextOutputReserve,
    private val safetyTokens: Int,
    envelope: TextRequestEnvelope,
    private val estimator: TextTokenEstimator = Utf8TextTokenEstimator
) {
    private val limits = settings.effectiveLimits
    private val fallback = settings.limits.hasUnknownLimits
    private val separator = estimate(envelope.separator)
    private val fixed = envelope.fixedText.fold(envelope.protocolTokens.toLong()) { sum, text ->
        Math.addExact(sum, estimate(text))
    }
    private val available = minOf(limits.maxInputTokens.toLong(),
        limits.contextWindowTokens.toLong() - output.contextTokens - safetyTokens)

    init { require(safetyTokens >= 0) }

    private fun estimate(text: String): Long = estimator.estimate(text).also { require(it >= 0) }

    private fun details(body: Long = 0) = TextBudgetDetails(
        available, fixed, body, output.contextTokens, safetyTokens, fallback
    )

    fun plan(units: List<TextBudgetUnit>): TextBatchPlan {
        require(units.map { it.sourceId }.toSet().size == units.size) { "Duplicate source IDs" }
        require(units.all { it.sourceId.isNotBlank() }) { "Blank source ID" }
        val failure = when {
            output.outputLimitTokens > limits.maxOutputTokens -> TextBudgetFailureReason.OUTPUT_LIMIT
            available <= 0 -> TextBudgetFailureReason.CONTEXT_EXHAUSTED
            fixed > available -> TextBudgetFailureReason.FIXED_INPUT_TOO_LARGE
            else -> null
        }
        if (failure != null) return TextBatchPlan.Rejected(failure, details())
        val batches = mutableListOf<TextBudgetBatch>()
        var pending = mutableListOf<TextBudgetUnit>()
        var body = 0L
        for (unit in units) {
            val cost = estimate(unit.serializedText)
            if (cost > available - fixed) return TextBatchPlan.Rejected(
                TextBudgetFailureReason.UNIT_TOO_LARGE, details(cost), unit.sourceId
            )
            val candidate = Math.addExact(body, Math.addExact(if (pending.isEmpty()) 0 else separator, cost))
            if (candidate > available - fixed) {
                batches += TextBudgetBatch(pending.toList(), details(body))
                pending = mutableListOf()
                body = 0
            }
            body = Math.addExact(body, Math.addExact(if (pending.isEmpty()) 0 else separator, cost))
            pending += unit
        }
        if (pending.isNotEmpty()) batches += TextBudgetBatch(pending.toList(), details(body))
        return TextBatchPlan.Ready(batches)
    }

    /** Use after confirmed context overflow or output truncation. Split only at source boundaries.
     * The executor must retain its original deadline/attempt budget, validate complete responses,
     * and commit only complete results. Generic malformed JSON alone does not prove truncation.
     */
    fun shrinkForRetry(batch: TextBudgetBatch): TextBatchPlan {
        require(batch.units.isNotEmpty())
        val validated = plan(batch.units)
        if (validated is TextBatchPlan.Rejected) return validated
        if (batch.units.size == 1) return TextBatchPlan.Rejected(
            TextBudgetFailureReason.INDIVISIBLE_UNIT,
            (validated as TextBatchPlan.Ready).batches.single().budget, batch.units.single().sourceId
        )
        val parts = splitTextBudgetUnits(batch.units, estimator)
        val first = plan(parts.first()) as TextBatchPlan.Ready
        val second = plan(parts.last()) as TextBatchPlan.Ready
        return TextBatchPlan.Ready(first.batches + second.batches)
    }
}


/** Weighted source-boundary split, independent of any server capacity claims. */
internal fun splitTextBudgetUnits(
    units: List<TextBudgetUnit>, estimator: TextTokenEstimator = Utf8TextTokenEstimator
): List<List<TextBudgetUnit>> {
    require(units.size >= 2)
    val weights = units.map { estimator.estimate(it.serializedText).also { size -> require(size >= 0) } }
    val total = weights.fold(0L, Math::addExact)
    var left = 0L
    var split = 1
    var best = Long.MAX_VALUE
    for (index in 0 until weights.lastIndex) {
        left = Math.addExact(left, weights[index])
        val imbalance = kotlin.math.abs(left - (total - left))
        if (imbalance < best) { best = imbalance; split = index + 1 }
    }
    return listOf(units.take(split), units.drop(split))
}
