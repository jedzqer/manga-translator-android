package com.manga.translate

import android.graphics.RectF
import com.manga.translate.model.BubbleSource
import com.manga.translate.ocr.EnglishLine
import com.manga.translate.ocr.resolveCropOcrText
import com.manga.translate.ocr.shouldRejectFreeTextWithoutLines
import com.manga.translate.ocr.shouldReuseDetectedLineRectsForOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrSharedToolsTest {
    @Test
    fun `page detected lines are reused only for free text regions`() {
        assertTrue(shouldReuseDetectedLineRectsForOcr(BubbleSource.TEXT_DETECTOR))
        assertFalse(shouldReuseDetectedLineRectsForOcr(BubbleSource.BUBBLE_DETECTOR))
        assertFalse(shouldReuseDetectedLineRectsForOcr(BubbleSource.MANUAL))
    }

    @Test
    fun `free text is rejected when an available line detector finds no lines`() {
        assertTrue(
            shouldRejectFreeTextWithoutLines(
                source = BubbleSource.TEXT_DETECTOR,
                lineDetectorAvailable = true,
                detectedLineCount = 0
            )
        )
    }

    @Test
    fun `line validation fails open for unavailable detector and normal bubbles`() {
        assertFalse(
            shouldRejectFreeTextWithoutLines(
                source = BubbleSource.TEXT_DETECTOR,
                lineDetectorAvailable = false,
                detectedLineCount = 0
            )
        )
        assertFalse(
            shouldRejectFreeTextWithoutLines(
                source = BubbleSource.BUBBLE_DETECTOR,
                lineDetectorAvailable = true,
                detectedLineCount = 0
            )
        )
        assertFalse(
            shouldRejectFreeTextWithoutLines(
                source = BubbleSource.TEXT_DETECTOR,
                lineDetectorAvailable = true,
                detectedLineCount = 1
            )
        )
    }

    // Reproduces app_2026-09-01_12-18-55-003.log: a 3-line balloon where the middle line
    // scored too low ("EF" dropped), and the whole-crop pass returned the single char "R".
    // Preferring "R" discarded both good lines, and the bubble was then dropped for having
    // no usable text.
    @Test
    fun `partially recognized multi line region keeps its recognized lines`() {
        var wholeCropCalls = 0
        val text = resolveCropOcrText(
            recognizedLines = listOf(
                line("HUMANITY HAP CREATED ANOTHER."),
                line("MROKUTOOK THE AIRBUS TOHELPTHEM.")
            ),
            lineRectCount = 3
        ) {
            wholeCropCalls++
            "R"
        }

        assertEquals("HUMANITY HAP CREATED ANOTHER.\nMROKUTOOK THE AIRBUS TOHELPTHEM.", text)
        assertEquals("Whole-crop fallback must not run when lines were recognized", 0, wholeCropCalls)
    }

    @Test
    fun `multi line region with no recognized line does not fall back to whole crop`() {
        var wholeCropCalls = 0
        val text = resolveCropOcrText(
            recognizedLines = emptyList(),
            lineRectCount = 3
        ) {
            wholeCropCalls++
            "R"
        }

        assertEquals("", text)
        assertEquals(0, wholeCropCalls)
    }

    @Test
    fun `single line region still falls back to whole crop`() {
        val text = resolveCropOcrText(recognizedLines = emptyList(), lineRectCount = 1) {
            "SMALL CAPTION"
        }

        assertEquals("SMALL CAPTION", text)
    }

    @Test
    fun `fully recognized region never runs the fallback`() {
        var wholeCropCalls = 0
        val text = resolveCropOcrText(
            recognizedLines = listOf(line("FIRST"), line("SECOND")),
            lineRectCount = 2
        ) {
            wholeCropCalls++
            "GARBAGE"
        }

        assertEquals("FIRST\nSECOND", text)
        assertEquals(0, wholeCropCalls)
    }

    private fun line(text: String) = EnglishLine(RectF(0f, 0f, 10f, 10f), text)
}
