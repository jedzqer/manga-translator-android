package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.manga.translate.detection.DetectionTile
import com.manga.translate.detection.PageRegion
import com.manga.translate.detection.PageRegionDetectionResult
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.OcrRecognitionException
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmClient
import com.manga.translate.network.LlmGateway
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.settings.SettingsStore
import com.manga.translate.translation.TranslationPipeline
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelinePageOcrTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `page OCR masks artwork and restores IDs contours and order across detection tiles`() = runBlocking {
        val settings = SettingsStore(context)
        val source = TrackingSource()
        val requests = mutableListOf<List<Int>>()
        val annotations = mutableListOf<Bitmap>()
        val gateway = object : LlmGateway by LlmClient(context, settings) {
            override suspend fun recognizePageRegions(image: Bitmap, requestedIds: List<Int>): Map<Int, String> {
                requests.add(requestedIds)
                annotations.add(image)
                if (requests.size == 1) {
                    assertEquals(Color.WHITE, image.getPixel(25, 60))
                    assertTrue(Color.red(image.getPixel(50, 60)) < 80)
                    assertEquals(Color.WHITE, image.getPixel(50, 140))
                } else {
                    assertEquals(175, image.height)
                    assertTrue(Color.red(image.getPixel(50, 25)) < 80)
                    assertEquals(Color.WHITE, image.getPixel(90, 165))
                }
                return requestedIds.reversed().associateWith { if (it == 2) "None" else "source-$it" }
            }
        }
        try {
            val detection = detection()
            val result = TranslationPipeline(context, llmClient = gateway, settingsStore = settings)
                .recognize(source, detection)
            assertEquals(listOf(listOf(1), listOf(0, 2)), requests)
            assertEquals(listOf(RectF(0f, 0f, 100f, 160f), RectF(0f, 125f, 100f, 300f)), source.rects)
            assertEquals(listOf(70, 11), result.map { it.id })
            assertEquals(listOf("source-0", "source-1"), result.map { it.text })
            result.forEach { bubble ->
                val original = detection.regions.single { it.id == bubble.id }
                assertEquals(original.rect, bubble.rect)
                assertEquals(original.source, bubble.source)
                assertArrayEquals(original.maskContour, bubble.maskContour, 0f)
            }
            assertTrue(source.crops.all { it.isRecycled })
            assertTrue(annotations.all { it.isRecycled })
        } finally { source.close() }
    }

    @Test
    fun `ordinary page OCR uses one masked request and empty pages avoid decoding`() = runBlocking {
        val settings = SettingsStore(context)
        val source = TrackingSource()
        var calls = 0
        val gateway = object : LlmGateway by LlmClient(context, settings) {
            override suspend fun recognizePageRegions(image: Bitmap, requestedIds: List<Int>): Map<Int, String> {
                calls++
                assertEquals(listOf(0, 1, 2), requestedIds)
                assertEquals(Color.WHITE, image.getPixel(90, 290))
                return requestedIds.associateWith { "source-$it" }
            }
        }
        try {
            val pipeline = TranslationPipeline(context, llmClient = gateway, settingsStore = settings)
            assertEquals(3, pipeline.recognize(source, detection().copy(tiles = emptyList())).size)
            assertEquals(listOf(RectF(0f, 0f, 100f, 300f)), source.rects)
            assertTrue(pipeline.recognize(source, detection().copy(regions = emptyList())).isEmpty())
            assertEquals(1, calls)
            assertEquals(1, source.crops.size)
        } finally { source.close() }
    }

    @Test
    fun `late chunk failures and mismatched IDs release all images and reject partial OCR`() {
        for (failure in listOf("request", "missing", "extra")) {
            val settings = SettingsStore(context)
            val source = TrackingSource()
            val annotations = mutableListOf<Bitmap>()
            val gateway = object : LlmGateway by LlmClient(context, settings) {
                override suspend fun recognizePageRegions(image: Bitmap, requestedIds: List<Int>): Map<Int, String> {
                    annotations.add(image)
                    if (annotations.size == 2) {
                        when (failure) {
                            "request" -> throw IllegalStateException("Chunk failed")
                            "missing" -> return emptyMap()
                            "extra" -> return requestedIds.associateWith { "text" } + (99 to "unexpected")
                        }
                    }
                    return requestedIds.associateWith { "text" }
                }
            }
            try {
                val pipeline = TranslationPipeline(context, llmClient = gateway, settingsStore = settings)
                assertThrows(if (failure == "request") IllegalStateException::class.java else OcrRecognitionException::class.java) {
                    runBlocking { pipeline.recognize(source, detection()) }
                }
                assertEquals(2, annotations.size)
                assertTrue(annotations.all { it.isRecycled })
                assertTrue(source.crops.all { it.isRecycled })
            } finally { source.close() }
        }
    }

    private fun detection(): PageRegionDetectionResult {
        val contour = floatArrayOf(.3f, .15f, .7f, .15f, .7f, .25f, .3f, .25f)
        return PageRegionDetectionResult(100, 300, emptyList(), emptyList(), listOf(
            PageRegion(70, RectF(20f, 210f, 80f, 260f), BubbleSource.TEXT_DETECTOR),
            PageRegion(11, RectF(20f, 40f, 80f, 80f), BubbleSource.BUBBLE_DETECTOR, contour),
            PageRegion(90, RectF(20f, 125f, 80f, 180f), BubbleSource.BUBBLE_DETECTOR)
        ), tiles = listOf(DetectionTile(0, 0, 100, 160), DetectionTile(0, 140, 100, 300)))
    }

    private class TrackingSource : BitmapCropSource {
        private val bitmap = Bitmap.createBitmap(100, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        private val delegate = PipelineBitmapDecoder.openCropSource(bitmap)
        val rects = mutableListOf<RectF>()
        val crops = mutableListOf<Bitmap>()
        override val width = 100
        override val height = 300
        override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap? {
            rects.add(RectF(rect))
            return delegate.decodeRegion(rect, maxEdge)?.also { crops.add(it) }
        }
        override fun close() {
            delegate.close()
            bitmap.recycle()
        }
    }

    private suspend fun TranslationPipeline.recognize(
        source: BitmapCropSource,
        detection: PageRegionDetectionResult
    ): List<OcrBubble> = suspendCoroutineUninterceptedOrReturn { continuation ->
        try {
            TranslationPipeline::class.java.getDeclaredMethod(
                "recognizeBubblesIndividually", BitmapCropSource::class.java, PageRegionDetectionResult::class.java,
                TranslationLanguage::class.java, Boolean::class.javaPrimitiveType, Continuation::class.java
            ).apply { isAccessible = true }.invoke(this, source, detection, TranslationLanguage.JA_TO_ZH, false, continuation)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
