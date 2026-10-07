package com.manga.translate

import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.ocr.OcrEngine
import com.manga.translate.ocr.recognizeLocalCrop
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

    @Test
    fun `Chinese blocks without page line boxes detect and recognize all lines`() {
        val crop = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        val rects = listOf(RectF(0f, 0f, 100f, 20f), RectF(0f, 30f, 100f, 50f))
        try {
            for (language in listOf(TranslationLanguage.ZH_HANS_TO_TARGET,
                TranslationLanguage.ZH_HANT_TO_TARGET, TranslationLanguage.CHN_ENG_TO_ZH)) {
                var detections = 0
                val seen = mutableListOf<RectF?>()
                val engine = object : OcrEngine {
                    override fun recognize(bitmap: Bitmap): String = error("Must not recognize a paragraph as one line")
                    override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?): OcrEngine.OcrEngineResult {
                        seen.add(rect)
                        return OcrEngine.OcrEngineResult(if (seen.size == 1) "第一行" else "第二行", .9f)
                    }
                }
                assertEquals("第一行\n第二行", recognizeLocalCrop(crop, language,
                    BubbleSource.TEXT_DETECTOR, null, engine, "test") { detections++; rects })
                assertEquals(1, detections)
                assertEquals(rects, seen)
            }
        } finally { crop.recycle() }
    }

    @Test
    fun `unavailable line detector fails open and Chinese empty detection keeps whole crop fallback`() {
        val crop = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        val engine = object : OcrEngine { override fun recognize(bitmap: Bitmap) = "whole" }
        try {
            for (language in listOf(TranslationLanguage.JA_TO_ZH, TranslationLanguage.EN_TO_ZH,
                TranslationLanguage.KO_TO_ZH, TranslationLanguage.ZH_HANS_TO_TARGET)) {
                assertEquals("whole", recognizeLocalCrop(crop, language, BubbleSource.TEXT_DETECTOR,
                    null, engine, "test") { null })
                val expected = if (language == TranslationLanguage.ZH_HANS_TO_TARGET) "whole" else ""
                assertEquals(expected, recognizeLocalCrop(crop, language, BubbleSource.TEXT_DETECTOR,
                    null, engine, "test") { emptyList() })
            }
        } finally { crop.recycle() }
    }

    @Test
    fun `explicit lines skip detection and Korean retains its stricter confidence threshold`() {
        val crop = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        val rects = listOf(RectF(0f, 0f, 100f, 20f), RectF(0f, 30f, 100f, 50f))
        val engine = object : OcrEngine {
            override fun recognize(bitmap: Bitmap): String = error("No whole crop fallback for multiple lines")
            override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?) = OcrEngine.OcrEngineResult("text", .6f)
        }
        try {
            assertEquals("text\ntext", recognizeLocalCrop(crop, TranslationLanguage.EN_TO_ZH,
                BubbleSource.TEXT_DETECTOR, rects, engine, "test") { error("Must reuse lines") })
            assertEquals("", recognizeLocalCrop(crop, TranslationLanguage.KO_TO_ZH,
                BubbleSource.TEXT_DETECTOR, rects, engine, "test") { error("Must reuse lines") })
        } finally { crop.recycle() }
    }

    @Test
    fun `manual multi line bubbles keep low confidence text for every local language`() {
        val crop = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        val rects = listOf(RectF(0f, 0f, 100f, 20f), RectF(0f, 30f, 100f, 50f))
        val engine = object : OcrEngine {
            override fun recognize(bitmap: Bitmap): String = error("Must preserve the detected lines")
            override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?) =
                OcrEngine.OcrEngineResult(if (rect == rects.first()) "A" else "B", 0.01f)
        }
        try {
            for (language in TranslationLanguage.entries.filter { it.supportsLocalOcr() }) {
                assertEquals(language.name, "A\nB", recognizeLocalCrop(crop, language,
                    BubbleSource.MANUAL, null, engine, "test") { rects })
                for (source in listOf(BubbleSource.BUBBLE_DETECTOR, BubbleSource.TEXT_DETECTOR)) {
                    assertEquals("$language / $source", "", recognizeLocalCrop(crop, language,
                        source, null, engine, "test") { rects })
                }
            }
        } finally { crop.recycle() }
    }

    @Test
    fun `manual Korean whole crop fallback also bypasses confidence filtering`() {
        val crop = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        val engine = object : OcrEngine {
            override fun recognize(bitmap: Bitmap): String = error("Korean must request scored recognition")
            override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?) =
                OcrEngine.OcrEngineResult("안녕", 0.01f)
        }
        try {
            for (detected in listOf(null, emptyList<RectF>())) {
                assertEquals("안녕", recognizeLocalCrop(crop, TranslationLanguage.KO_TO_ZH,
                    BubbleSource.MANUAL, null, engine, "test") { detected })
                assertEquals("", recognizeLocalCrop(crop, TranslationLanguage.KO_TO_ZH,
                    BubbleSource.BUBBLE_DETECTOR, null, engine, "test") { detected })
            }
        } finally { crop.recycle() }
    }

    @Test
    fun `manual confidence exemption does not turn empty recognition into text`() {
        val crop = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        val rects = listOf(RectF(0f, 0f, 100f, 20f), RectF(0f, 30f, 100f, 50f))
        val engine = object : OcrEngine {
            override fun recognize(bitmap: Bitmap): String = error("No paragraph fallback")
            override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?) =
                OcrEngine.OcrEngineResult("   ", 0.01f)
        }
        try {
            assertEquals("", recognizeLocalCrop(crop, TranslationLanguage.EN_TO_ZH,
                BubbleSource.MANUAL, null, engine, "test") { rects })
        } finally { crop.recycle() }
    }

    private fun line(text: String) = EnglishLine(RectF(0f, 0f, 10f, 10f), text)
}
