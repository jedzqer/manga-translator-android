package com.manga.translate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmGateway
import com.manga.translate.ocr.BubbleTextRecognizer
import com.manga.translate.ocr.ConfidenceTrackingOcrEngine
import com.manga.translate.ocr.OcrEngine
import com.manga.translate.ocr.OcrEngineRegistry
import com.manga.translate.ocr.recognizeLocalCrop
import com.manga.translate.ocr.recognizeWithOcrSupplement
import com.manga.translate.settings.SettingsStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OcrSupplementTest {
    @Test
    fun `missing detection and incomplete lines send the region to API even with confident fallback`() = runBlocking {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val line = RectF(0f, 0f, 40f, 15f)
        try {
            for (level in 1..3) {
                for (source in listOf(BubbleSource.BUBBLE_DETECTOR, BubbleSource.TEXT_DETECTOR)) {
                    for (scenario in listOf("unavailable", "no lines", "partial", "detector error", "recognizer error", "complete")) {
                        var calls = 0
                        var apiCalls = 0
                        val engine = ConfidenceTrackingOcrEngine(object : OcrEngine {
                            override fun recognize(bitmap: Bitmap) = error("Must retain confidence")
                            override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?): OcrEngine.OcrEngineResult {
                                calls++
                                if (scenario == "recognizer error") error("Line recognition failed")
                                val text = if (scenario == "partial" && calls == 1) "" else "confident text"
                                return OcrEngine.OcrEngineResult(text, 0.99f)
                            }
                        }, level, TranslationLanguage.EN_TO_ZH, source)
                        val result = recognizeWithOcrSupplement(true, { engine.needsSupplement }, {
                            recognizeLocalCrop(bitmap, TranslationLanguage.EN_TO_ZH, source, null,
                                engine, "test", onIncompleteRecognition = engine::markIncomplete) {
                                when (scenario) {
                                    "unavailable" -> null
                                    "no lines" -> emptyList()
                                    "detector error" -> error("Detection failed")
                                    else -> listOf(line, RectF(0f, 20f, 40f, 35f))
                                }
                            }
                        }, { apiCalls++; "API whole region" })
                        assertEquals("$level $source $scenario", if (scenario == "complete") 0 else 1, apiCalls)
                        if (scenario != "complete") assertEquals("API whole region", result)
                    }
                }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun `region empty results supplement but cancellation and API failures stay failures`() = runBlocking {
        var apiCalls = 0
        assertEquals("API", recognizeWithOcrSupplement(true, { false }, { "" }, { apiCalls++; "API" }))
        assertEquals(1, apiCalls)
        assertEquals("", recognizeWithOcrSupplement(false, { true }, { "" }, { error("Disabled API") }))
        val localFailure = IllegalStateException("local failure")
        try {
            recognizeWithOcrSupplement(false, { false }, { throw localFailure }, { error("Disabled API") })
            fail("Must propagate local failure when disabled")
        } catch (e: IllegalStateException) { assertSame(localFailure, e) }
        val cancellation = CancellationException("cancel")
        try {
            recognizeWithOcrSupplement(true, { true }, { throw cancellation }, { error("Cancelled API") })
            fail("Must propagate cancellation")
        } catch (e: CancellationException) { assertSame(cancellation, e) }
        assertNull(recognizeWithOcrSupplement(true, { true }, { "partial" }, { null }))
        val apiFailure = IllegalStateException("API failure")
        try {
            recognizeWithOcrSupplement(true, { true }, { "partial" }, { throw apiFailure })
            fail("Must not treat partial local text as API success")
        } catch (e: IllegalStateException) { assertSame(apiFailure, e) }
    }

    @Test
    fun `strength widens review while low follows language and free text thresholds`() {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        try {
            for (language in listOf(TranslationLanguage.EN_TO_ZH, TranslationLanguage.JA_TO_ZH, TranslationLanguage.KO_TO_ZH)) {
                for (source in listOf(BubbleSource.BUBBLE_DETECTOR, BubbleSource.TEXT_DETECTOR)) {
                    for (text in listOf("hello", "hi", "")) {
                        val minimum = when {
                            source == BubbleSource.TEXT_DETECTOR && text.length <= 2 -> 0.65f
                            language == TranslationLanguage.KO_TO_ZH -> 0.65f
                            source == BubbleSource.TEXT_DETECTOR -> 0.55f
                            else -> 0.5f
                        }
                        for (level in 1..3) {
                            val threshold = when (level) { 1 -> minimum; 2 -> 0.8f; else -> 0.95f }
                            for (score in listOf(minimum - 0.01f, minimum, 0.79f, 0.8f, 0.94f, 0.95f, 0.99f, Float.NaN, Float.POSITIVE_INFINITY)) {
                                val engine = ConfidenceTrackingOcrEngine(object : OcrEngine {
                                    override fun recognize(bitmap: Bitmap) = error("Must retain confidence")
                                    override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?) = OcrEngine.OcrEngineResult(text, score)
                                }, level, language, source)
                                engine.recognizeWithScore(bitmap)
                                assertEquals("$language $source '$text' level=$level score=$score",
                                    text.isBlank() || !score.isFinite() || score < threshold, engine.needsSupplement)
                            }
                        }
                    }
                }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun `low scoring rejected line marks whole bubble for supplement`() {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        try {
            for (score in listOf(0.3f, 0.79f, 0.8f, 0.99f, Float.NaN)) {
                var calls = 0
                val engine = ConfidenceTrackingOcrEngine(object : OcrEngine {
                    override fun recognize(bitmap: Bitmap) = error("Must retain confidence")
                    override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?): OcrEngine.OcrEngineResult {
                        calls++
                        return OcrEngine.OcrEngineResult("hello", if (calls == 1) 0.99f else score)
                    }
                })
                val text = recognizeLocalCrop(bitmap, TranslationLanguage.EN_TO_ZH,
                    BubbleSource.BUBBLE_DETECTOR, null, engine, "test") {
                    listOf(RectF(0f, 0f, 40f, 15f), RectF(0f, 20f, 40f, 35f))
                }
                assertTrue(text.startsWith("hello"))
                assertEquals(2, calls)
                assertEquals(!score.isFinite() || score < 0.8f, engine.needsSupplement)
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun `manual selection bypasses unavailable local models when API preference enabled`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(context)
        settings.saveOcrApiSettings(settings.loadOcrApiSettings().copy(
            useLocalOcr = true, manualBubblesUseApi = true, apiSupplementEnabled = false))
        var calls = 0
        val gateway = Proxy.newProxyInstance(LlmGateway::class.java.classLoader,
            arrayOf(LlmGateway::class.java)) { _, method, _ ->
            check(method.name == "recognizeImageText")
            calls++
            "manual text"
        } as LlmGateway
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        try {
            val result = BubbleTextRecognizer(gateway, OcrEngineRegistry(context, settings))
                .recognizeCrop(bitmap, TranslationLanguage.EN_TO_ZH, true, "test", BubbleSource.MANUAL)
            assertTrue(result is com.manga.translate.model.OcrRecognitionResult.Success)
            assertEquals("manual text", (result as com.manga.translate.model.OcrRecognitionResult.Success).text)
            assertEquals(1, calls)
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }
}
