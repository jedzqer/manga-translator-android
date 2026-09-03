package com.manga.translate

import com.manga.translate.platform.PerformanceTrace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTraceTest {
    @Test
    fun `disabled trace does not log or sample clock`() = runBlocking {
        var clockCalls = 0
        val messages = mutableListOf<String>()
        val trace = PerformanceTrace(
            tag = "Test",
            operation = "page",
            enabled = false,
            clockNanos = { clockCalls += 1; 1_000_000L },
            log = { _, message -> messages += message }
        )

        assertEquals("result", trace.measure("ocr") { "result" })
        trace.attribute("bubbles", 7)
        trace.logSummary()

        assertEquals(0, clockCalls)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `enabled trace logs stages and aggregates summary`() = runBlocking {
        var now = 0L
        val messages = mutableListOf<String>()
        val trace = PerformanceTrace(
            tag = "Test",
            operation = "page",
            enabled = true,
            clockNanos = { now += 2_000_000L; now },
            log = { _, message -> messages += message }
        )

        trace.measure("ocr") { Unit }
        trace.measure("ocr") { Unit }
        trace.logSummary()

        assertEquals(3, messages.size)
        assertTrue(messages[0].contains("stage=ocr elapsedMs=2"))
        assertTrue(messages[1].contains("stage=ocr elapsedMs=2"))
        assertTrue(messages[2].contains("summary ocr=4ms/2x total=4ms"))
    }

    @Test
    fun `summary appends page context attributes`() = runBlocking {
        var now = 0L
        val messages = mutableListOf<String>()
        val trace = PerformanceTrace(
            tag = "Test",
            operation = "page",
            enabled = true,
            clockNanos = { now += 2_000_000L; now },
            log = { _, message -> messages += message }
        )

        trace.measure("detection") { Unit }
        trace.attribute("size", "800x1200")
        trace.attribute("bubbles", 4)
        trace.attribute("blank", "")
        trace.attribute("missing", null)
        trace.logSummary()

        val summary = messages.last()
        assertTrue(summary.contains("detection=2ms total=2ms"))
        assertTrue(summary.contains("size=800x1200"))
        assertTrue(summary.contains("bubbles=4"))
        assertFalse(summary.contains("blank="))
        assertFalse(summary.contains("missing="))
    }

    @Test
    fun `summary is emitted for attributes without any stage`() = runBlocking {
        val messages = mutableListOf<String>()
        val trace = PerformanceTrace(
            tag = "Test",
            operation = "page",
            enabled = true,
            clockNanos = { 0L },
            log = { _, message -> messages += message }
        )

        trace.attribute("httpStatus", 429)
        trace.logSummary()

        assertEquals(1, messages.size)
        assertTrue(messages[0].contains("httpStatus=429"))
    }
}
