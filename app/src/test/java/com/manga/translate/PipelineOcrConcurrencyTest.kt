package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.detection.PageRegion
import com.manga.translate.detection.PageRegionDetectionResult
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmClient
import com.manga.translate.network.LlmGateway
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.settings.SettingsStore
import com.manga.translate.translation.TranslationPipeline
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PipelineOcrConcurrencyTest {
    @Test
    fun `concurrent page OCR requests release decoded pages on cancellation and preserve IDs`() = runBlocking {
        withTimeout(10_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = SettingsStore(context)
            settings.saveOcrApiSettings(settings.loadOcrApiSettings().copy(useLocalOcr = false, apiOcrConcurrencyLimit = 2))
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val requests = AtomicInteger()
            val crops = Collections.synchronizedList(mutableListOf<Bitmap>())
            val annotations = Collections.synchronizedList(mutableListOf<Bitmap>())
            val peak = AtomicInteger()
            val source = object : BitmapCropSource {
                override val width = 100
                override val height = 100
                override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap = synchronized(crops) {
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also {
                        crops.add(it)
                        peak.set(maxOf(peak.get(), crops.count { bitmap -> !bitmap.isRecycled }))
                    }
                }
                override fun close() = Unit
            }
            val gateway = object : LlmGateway by LlmClient(context, settings) {
                override suspend fun recognizePageRegions(image: Bitmap, requestedIds: List<Int>): Map<Int, String> {
                    annotations.add(image)
                    if (requests.incrementAndGet() == 2) started.complete(Unit)
                    gate.await()
                    return requestedIds.associateWith { "text" }
                }
            }
            val pipeline = TranslationPipeline(context, llmClient = gateway, settingsStore = settings)
            val regions = List(100) { PageRegion(it, RectF(0f, 0f, 8f, 8f), BubbleSource.BUBBLE_DETECTOR) }
            val first = launch { pipeline.recognize(source, regions) }
            val second = launch { pipeline.recognize(source, regions) }
            started.await()
            val waiting = launch { pipeline.recognize(source, regions) }
            repeat(10) { yield() }
            assertEquals(2, crops.size)
            assertEquals(2, annotations.count { !it.isRecycled })
            waiting.cancelAndJoin()
            first.cancelAndJoin()
            second.cancelAndJoin()
            assertTrue(crops.all { it.isRecycled })
            assertTrue(annotations.all { it.isRecycled })
            gate.complete(Unit)
            // Reuse after cancellation also verifies the crop permits were returned.
            val pages = List(2) { async { pipeline.recognize(source, regions) } }.awaitAll()
            pages.forEach { assertEquals((0 until 100).toList(), it.map { bubble -> bubble.id }) }
            assertEquals(1, peak.get())
            assertTrue(crops.all { it.isRecycled })
            assertTrue(annotations.all { it.isRecycled })
        }
    }

    private suspend fun TranslationPipeline.recognize(
        source: BitmapCropSource,
        regions: List<PageRegion>
    ): List<OcrBubble> = suspendCoroutineUninterceptedOrReturn { continuation ->
        TranslationPipeline::class.java.getDeclaredMethod(
            "recognizeBubblesIndividually", BitmapCropSource::class.java, PageRegionDetectionResult::class.java,
            TranslationLanguage::class.java, Boolean::class.javaPrimitiveType, Continuation::class.java
        ).apply { isAccessible = true }.invoke(this, source,
            PageRegionDetectionResult(source.width, source.height, emptyList(), emptyList(), regions),
            TranslationLanguage.JA_TO_ZH, false, continuation)
    }
}
