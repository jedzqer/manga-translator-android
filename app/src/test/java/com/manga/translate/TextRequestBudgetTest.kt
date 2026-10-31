package com.manga.translate

import com.manga.translate.settings.*
import com.manga.translate.translation.*
import org.junit.Assert.*
import org.junit.Test

class TextRequestBudgetTest {
    private fun planner(
        input: Int = 20, context: Int = 30, maxOutput: Int = 10,
        output: TextOutputReserve = TextOutputReserve(8, 2),
        fixed: List<String> = listOf("[]"), safety: Int = 0,
        limits: ModelContextLimits = ModelContextLimits(input, maxOutput, context)
    ) = TextRequestBudget(
        ModelContextSettings(null, ModelContextIdentity("endpoint", "chat", "model", "provider"), limits),
        output, safety, TextRequestEnvelope(fixed, ",", 0)
    )

    private fun units(vararg sizes: Int) = sizes.mapIndexed { index, size ->
        TextBudgetUnit("page-$index", "x".repeat(size))
    }

    private fun ready(result: TextBatchPlan) = (result as TextBatchPlan.Ready).batches
    private fun rejected(result: TextBatchPlan) = result as TextBatchPlan.Rejected

    @Test fun `exact boundary includes separator structure and output`() {
        val batch = ready(planner().plan(units(8, 9))).single()
        assertEquals(20L, batch.budget.inputTokens)
        assertEquals(0L, batch.budget.remainingInputTokens)
        assertEquals(10L, batch.budget.outputReserveTokens)
        assertTrue(batch.budget.isEstimate)
        assertEquals(2, ready(planner().plan(units(9, 9))).size)
    }

    @Test fun `reserves and fixed prompt fail with diagnostic`() {
        assertEquals(TextBudgetFailureReason.CONTEXT_EXHAUSTED,
            rejected(planner(context = 10).plan(units(1))).reason)
        assertEquals(TextBudgetFailureReason.CONTEXT_EXHAUSTED,
            rejected(planner(safety = Int.MAX_VALUE).plan(units(1))).reason)
        val failure = rejected(planner(fixed = listOf("prompt", "glossary".repeat(100))).plan(units(1)))
        assertEquals(TextBudgetFailureReason.FIXED_INPUT_TOO_LARGE, failure.reason)
        assertEquals(806L, failure.budget.fixedInputTokens)
        assertEquals(TextBudgetFailureReason.OUTPUT_LIMIT,
            rejected(planner(maxOutput = 9).plan(units(1))).reason)
    }

    @Test fun `single image never gets split or silently dropped`() {
        val source = units(5, 19)
        val failure = rejected(planner().plan(source))
        assertEquals(TextBudgetFailureReason.UNIT_TOO_LARGE, failure.reason)
        assertEquals("page-1", failure.sourceId)
        assertEquals(21L, failure.budget.inputTokens)
    }

    @Test fun `unicode counts bytes including supplementary and malformed surrogates`() {
        assertEquals(9L, Utf8TextTokenEstimator.estimate("中文日"))
        assertEquals(6L, Utf8TextTokenEstimator.estimate("한글"))
        assertEquals(4L, Utf8TextTokenEstimator.estimate("\uD83D\uDE00"))
        assertEquals(4L, Utf8TextTokenEstimator.estimate("\uD840\uDC00"))
        assertEquals(3L, Utf8TextTokenEstimator.estimate("\uD800"))
        assertEquals(4L, Utf8TextTokenEstimator.estimate("éя"))
        assertEquals(3L, Utf8TextTokenEstimator.estimate("e\u0301"))
        assertEquals(0L, Utf8TextTokenEstimator.estimate(""))
    }

    @Test fun `ordered packing preserves all sources and content across many batches`() {
        val source = (0..199).map { TextBudgetUnit("page-${199-it}", "x".repeat(it % 17)) }
        val batches = ready(planner().plan(source))
        assertEquals(source, batches.flatMap { it.units })
        assertTrue(batches.all { it.budget.inputTokens <= it.budget.availableInputTokens })
        assertTrue(ready(planner().plan(emptyList())).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { planner().plan(listOf(source[0], source[0])) }
    }

    @Test fun `task output and provider reasoning policy alter available input`() {
        val source = units(8, 8)
        assertEquals(1, ready(planner(output = TextOutputReserve(4, 1)).plan(source)).size)
        assertEquals(2, ready(planner(maxOutput = 20, output = TextOutputReserve(12, 2)).plan(source)).size)
        val reasoning = TextOutputReserve(7, 2, 5)
        assertEquals(TextBudgetFailureReason.OUTPUT_LIMIT, rejected(planner(output = reasoning).plan(source)).reason)
        val batches = ready(planner(output = reasoning.copy(reasoningCountsTowardOutputLimit = false)).plan(source))
        assertEquals(2, batches.size)
        assertEquals(16L, batches.first().budget.availableInputTokens)
        assertEquals(14L, batches.first().budget.outputReserveTokens)
    }

    @Test fun `fallback and large reserves never wrap integers`() {
        val batch = ready(planner(limits = ModelContextLimits(maxInputTokens = 20)).plan(units(1))).single()
        assertTrue(batch.budget.usesFallbackLimits)
        assertEquals(20L, batch.budget.availableInputTokens)
        val failure = rejected(planner(output = TextOutputReserve(Int.MAX_VALUE, Int.MAX_VALUE)).plan(units(1)))
        assertEquals(4294967294L, failure.budget.outputReserveTokens)
    }

    @Test fun `truncation shrink preserves entire pages and terminates at one page`() {
        val planner = planner(input = 100, context = 110)
        val original = ready(planner.plan(units(40, 10, 10, 10))).single()
        val smaller = ready(planner.shrinkForRetry(original))
        assertEquals(listOf(1, 3), smaller.map { it.units.size })
        assertEquals(original.units, smaller.flatMap { it.units })
        assertTrue(smaller.all { it.budget.inputTokens < original.budget.inputTokens })
        assertEquals(TextBudgetFailureReason.INDIVISIBLE_UNIT,
            rejected(planner.shrinkForRetry(smaller.first())).reason)
        val next = ready(planner.shrinkForRetry(smaller.last()))
        assertEquals(smaller.last().units, next.flatMap { it.units })
    }

    @Test fun `custom estimator and framing are accounted for`() {
        val settings = ModelContextSettings(null, ModelContextIdentity("e", "f", "m", "p"),
            ModelContextLimits(20, 10, 30))
        val planner = TextRequestBudget(settings, TextOutputReserve(8, 2), 1,
            TextRequestEnvelope(listOf("system", "example", "glossary", "{}"), ",", 3),
            TextTokenEstimator { it.length.toLong() })
        assertEquals(26L, rejected(planner.plan(units(1))).budget.fixedInputTokens)
    }
}
