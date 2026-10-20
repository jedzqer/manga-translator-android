package com.manga.translate

import android.content.Context
import android.graphics.*
import com.manga.translate.detection.*
import com.manga.translate.model.*
import com.manga.translate.network.*
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.settings.*
import com.manga.translate.storage.FloatingTranslationCacheStore
import com.manga.translate.translation.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VlPageTranslationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun bubble(id: Int, rect: RectF) = BubbleTranslation.pending(id, rect, "")

    @Test fun `merged pages use consecutive image IDs and restore local IDs and parent geometry`() = runBlocking {
        val gateway = PageGateway(context)
        val coordinator = VlPageTranslationCoordinator(gateway, FloatingTranslationCacheStore(context))
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        val parent = BubbleDetection(RectF(10f, 10f, 150f, 350f), 1f, 0)
        val layout = VlPageLayout(200, 400,
            listOf(bubble(8, RectF(20f, 250f, 80f, 300f)), bubble(4, RectF(20f, 20f, 80f, 80f))),
            backgrounds = listOf(parent),
            tiles = listOf(DetectionTile(0, 0, 200, 200), DetectionTile(0, 200, 200, 400)),
            targetParents = mapOf(8 to 0, 4 to 0))
        try {
            val first = PipelineBitmapDecoder.openCropSource(bitmap).use { coordinator.preparePage(it, layout, 0) }
            val second = PipelineBitmapDecoder.openCropSource(bitmap).use {
                coordinator.preparePage(it, layout.copy(targetParents = emptyMap()), first.localIds.size)
            }
            assertEquals(mapOf(0 to 4, 1 to 8), first.localIds)
            assertEquals(mapOf(2 to 4, 3 to 8), second.localIds)
            gateway.emptyIds = setOf(3)
            val results = coordinator.translatePreparedPages(listOf(first, second), SettingsStore(context).load(),
                1000, 0, mapOf("old" to "Old"), true)
            assertEquals(1, gateway.calls)
            assertEquals(4, gateway.imageCount)
            assertEquals(listOf(0, 1, 2, 3), gateway.batchIds)
            assertEquals(parent.rect, results[0].bubbles.single().rect)
            assertEquals("translated-1\ntranslated-0", results[0].bubbles.single().translatedText)
            assertEquals(4, results[1].bubbles.single().id)
            assertEquals("translated-2", results[1].bubbles.single().translatedText)
            assertEquals(mapOf("new" to "New"), results[1].glossaryUsed)
            gateway.invalidBatch = true
            assertThrows(LlmResponseException::class.java) { runBlocking {
                coordinator.translatePreparedPages(listOf(first, second), SettingsStore(context).load(), 1000, 0, emptyMap(), false)
            } }
            gateway.cancel = true
            assertThrows(CancellationException::class.java) { runBlocking {
                coordinator.translatePreparedPages(listOf(first), SettingsStore(context).load(), 1000, 0, emptyMap(), false)
            } }
        } finally { bitmap.recycle() }
        Unit
    }

    @Test fun `merged blank pages avoid network requests`() = runBlocking {
        val gateway = PageGateway(context)
        val coordinator = VlPageTranslationCoordinator(gateway, FloatingTranslationCacheStore(context))
        val blank = VlPageTranslationCoordinator.PreparedPage(VlPageLayout(100, 100, emptyList()), emptyList(), emptyMap())
        val results = coordinator.translatePreparedPages(listOf(blank, blank), SettingsStore(context).load(), 1000, 0, emptyMap(), false)
        assertEquals(2, results.size)
        assertTrue(results.all { it.bubbles.isEmpty() })
        assertEquals(0, gateway.calls)
    }

    @Test fun `text blocks replace their parent while empty balloons remain and ordinary regions stay unchanged`() {
        val balloons = listOf(BubbleDetection(RectF(10f, 10f, 100f, 100f), 1f, 0),
            BubbleDetection(RectF(150f, 10f, 190f, 90f), 1f, 0))
        val texts = listOf(RectF(20f, 20f, 40f, 80f), RectF(60f, 20f, 80f, 80f), RectF(5f, 120f, 80f, 150f))
            .map { BubbleDetection(it, 1f, 1) }
        val ordinary = listOf(PageRegion(0, balloons[0].rect, BubbleSource.BUBBLE_DETECTOR))
        val page = PageRegionDetectionResult(200, 200, balloons, emptyList(), ordinary, allTextDetections = texts)
        val layout = page.vlLayout()
        assertEquals(listOf(0, 1, 2, 3), layout.targets.map { it.id })
        assertEquals(balloons[1].rect, layout.targets[0].rect)
        assertEquals(texts[0].rect, layout.targets[1].rect)
        assertEquals(BubbleSource.BUBBLE_DETECTOR, layout.targets[1].source)
        assertEquals(BubbleSource.TEXT_DETECTOR, layout.targets.last().source)
        assertSame(ordinary, page.regions)
        assertEquals(layout.targets.map { it.rect }, page.vlLayout().targets.map { it.rect })
    }

    @Test fun `overlapping parents choose smaller fully covering balloon`() {
        val large = BubbleDetection(RectF(0f, 0f, 100f, 100f), 1f, 0)
        val small = BubbleDetection(RectF(10f, 10f, 80f, 80f), 1f, 0)
        val text = BubbleDetection(RectF(20f, 20f, 50f, 50f), 1f, 1)
        val layout = PageRegionDetectionResult(100, 100, listOf(large, small), emptyList(), emptyList(),
            allTextDetections = listOf(text)).vlLayout()
        assertEquals(listOf(large.rect, text.rect), layout.targets.map { it.rect })
        assertEquals(small.rect, layout.renderTranslations(mapOf(0 to "", 1 to "text")).single().rect)
    }

    @Test fun `responses across chunks fill one parent mask and omit filtered text`() = runBlocking {
        val contour = floatArrayOf(.1f, .1f, .9f, .1f, .9f, .9f, .1f, .9f)
        val parent = BubbleDetection(RectF(20f, 40f, 180f, 360f), 1f, 0, contour)
        val texts = listOf(RectF(40f, 60f, 100f, 100f), RectF(40f, 120f, 100f, 160f),
            RectF(40f, 260f, 100f, 300f), RectF(0f, 370f, 100f, 390f))
            .map { BubbleDetection(it, 1f, 1) }
        val layout = PageRegionDetectionResult(200, 400, listOf(parent), emptyList(), emptyList(),
            allTextDetections = texts, tiles = listOf(DetectionTile(0, 0, 200, 200), DetectionTile(0, 200, 200, 400))).vlLayout()
        val gateway = PageGateway(context).apply { emptyIds = setOf(1) }
        val coordinator = VlPageTranslationCoordinator(gateway, FloatingTranslationCacheStore(context))
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        try {
            PipelineBitmapDecoder.openCropSource(bitmap).use { source ->
                val result = coordinator.translate(source, layout, SettingsStore(context).load(), TranslationLanguage.JA_TO_ZH, 1000, 0)
                assertEquals(2, gateway.calls)
                assertEquals(listOf(0, 3), result.bubbles.map { it.id })
                assertEquals("translated-0\ntranslated-2", result.bubbles.first().translatedText)
                assertEquals(parent.rect, result.bubbles.first().rect)
                assertArrayEquals(contour, result.bubbles.first().maskContour, 0f)
                assertEquals(texts.last().rect, result.bubbles.last().rect)
                assertEquals(BubbleSource.TEXT_DETECTOR, result.bubbles.last().source)
                assertEquals(texts.map { it.rect }, layout.targets.map { it.rect })
            }
            assertTrue(layout.renderTranslations(layout.targets.associate { it.id to "" }).isEmpty())
            val partlyFiltered = layout.renderTranslations(mapOf(0 to "", 1 to "", 2 to "dialogue", 3 to ""))
            assertEquals(parent.rect, partlyFiltered.single().rect)
            assertEquals("dialogue", partlyFiltered.single().translatedText)
        } finally { bitmap.recycle() }
    }

    @Test fun `overlap tiles assign each ID once and expand to include boundary regions`() {
        val targets = listOf(bubble(4, RectF(10f, 60f, 40f, 130f)), bubble(8, RectF(10f, 150f, 40f, 195f)))
        val layout = VlPageLayout(100, 200, targets, tiles = listOf(DetectionTile(0, 0, 100, 100), DetectionTile(0, 80, 100, 200)))
        val chunks = layout.chunks()
        assertEquals(listOf(4, 8), chunks.flatMap { it.targets }.map { it.id })
        assertTrue(chunks.all { chunk -> chunk.targets.all { chunk.rect.contains(it.rect) } })
        assertEquals(60f, chunks.single().rect.top)
        assertTrue(layout.copy(targets = emptyList()).chunks().isEmpty())
    }

    @Test fun `source remapping preserves normalized text contours`() {
        val contour = floatArrayOf(.1f, .1f, .3f, .1f, .3f, .3f)
        val page = PageRegionDetectionResult(100, 100, emptyList(), emptyList(), emptyList(),
            allTextDetections = listOf(BubbleDetection(RectF(10f, 10f, 30f, 30f), 1f, 1, contour)))
        val mapped = page.remapToSource(200, 400)
        assertEquals(RectF(20f, 40f, 60f, 120f), mapped.allTextDetections.single().rect)
        assertArrayEquals(contour, mapped.allTextDetections.single().maskContour, 0f)
    }

    @Test fun `annotation removes artwork preserves text and gives nearby targets distinct colors`() {
        val source = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        Canvas(source).apply {
            val paint = Paint().apply { color = Color.WHITE }
            drawRect(30f, 80f, 120f, 180f, paint); drawRect(150f, 80f, 240f, 180f, paint)
            paint.color = Color.BLACK; drawRect(50f, 110f, 90f, 150f, paint)
        }
        val layout = VlPageLayout(300, 300, listOf(bubble(0, RectF(30f, 80f, 120f, 180f)), bubble(1, RectF(150f, 80f, 240f, 180f))))
        val result = VlPageAnnotationRenderer.render(source, layout, layout.chunks().single())
        try {
            assertEquals(Color.WHITE, result.getPixel(280, 280))
            assertTrue(Color.red(result.getPixel(60, 120)) < 80)
            assertNotEquals(result.getPixel(100, 160), result.getPixel(200, 160))
            val file = java.io.File("build/reports/vl-annotation-preview.png")
            requireNotNull(file.parentFile).mkdirs()
            file.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { result.recycle(); source.recycle() }
    }

    @Test fun `full visual keeps artwork and annotated targets`() {
        val source = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val layout = VlPageLayout(300, 300, listOf(bubble(7, RectF(50f, 80f, 120f, 180f))))
        val result = VlPageAnnotationRenderer.render(source, layout, layout.chunks().single(), fullVisual = true)
        try {
            assertEquals(Color.RED, result.getPixel(280, 280))
            assertNotEquals(Color.RED, result.getPixel(80, 120))
            assertEquals(Color.RED, source.getPixel(80, 120))
        } finally { result.recycle(); source.recycle() }
    }

    @Test fun `refill keeps completed entries and geometry while removing explicit empty targets`() {
        val completed = bubble(1, RectF(0f, 0f, 20f, 20f)).withTranslationResult("keep")
        val target = bubble(2, RectF(25f, 20f, 60f, 50f))
        val empty = bubble(3, RectF(70f, 20f, 100f, 50f))
        val translated = bubble(2, RectF(1f, 1f, 10f, 10f)).withTranslationResult("new")
        val result = mergeVlTargetResults(listOf(completed, target, empty), listOf(target, empty), listOf(translated))
        assertEquals(listOf(1, 2), result.map { it.id })
        assertSame(completed, result[0])
        assertEquals(target.rect, result[1].rect)
        assertEquals("new", result[1].translatedText)
    }

    @Test fun `background masks do not expose text belonging to another chunk`() {
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val first = bubble(0, RectF(20f, 50f, 60f, 100f))
        val second = bubble(1, RectF(100f, 50f, 140f, 100f))
        val layout = VlPageLayout(200, 200, listOf(first, second), listOf(BubbleDetection(RectF(10f, 20f, 160f, 150f), 1f, 0)))
        val result = VlPageAnnotationRenderer.render(source, layout, VlPageChunk(RectF(0f, 0f, 200f, 200f), listOf(first)))
        try {
            assertEquals(Color.WHITE, result.getPixel(120, 75))
            assertTrue(Color.red(result.getPixel(40, 75)) < 80)
        } finally { source.recycle(); result.recycle() }
    }

    @Test fun `structured image rejects invalid ID sets and invalid translation fields`() {
        val parser = ResponseParser()
        for (body in listOf(
            """{"items":[{"id":1,"translation":"x"},{"id":1,"translation":"y"}]}""",
            """{"items":[{"id":2,"translation":"x"}]}""",
            """{"items":[]}""", """{"items":[{"id":1,"text":"source"}]}""",
            """{"items":[{"id":1,"translation":null}]}""",
            """{"items":[{"id":1.5,"translation":"x"}]}""",
            """{"items":[{"id":1,"translation":"x"},{}]}"""
        )) assertThrows(LlmResponseException::class.java) { parser.parseImageItemsContent(body, listOf(1)) }
        val valid = parser.parseImageItemsContent("""{"items":[{"id":2,"translatedText":"原文"},{"id":1,"translation":""}]}""", listOf(1, 2))
        assertEquals(listOf(2, 1), valid.items.map { it.id })
        assertEquals("", valid.items.last().translation)
    }

    @Test fun `whole page cache is exact and glossary requests bypass it`() = runBlocking {
        val gateway = PageGateway(context)
        val cache = FloatingTranslationCacheStore(context)
        val coordinator = VlPageTranslationCoordinator(gateway, cache)
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val settings = SettingsStore(context).load().copy(modelName = "cache-test-${System.nanoTime()}")
        val layout = VlPageLayout(200, 200, listOf(bubble(7, RectF(20f, 40f, 100f, 120f))))
        PipelineBitmapDecoder.openCropSource(bitmap).use { source ->
            suspend fun request(glossary: Map<String, String> = emptyMap(), enabled: Boolean = false) =
                coordinator.translate(source, layout, settings, TranslationLanguage.JA_TO_ZH, 1000, 0, glossary, enabled, true)
            assertTrue(request().glossaryUsed.isEmpty())
            request(); assertEquals(1, gateway.calls)
            request(mapOf("a" to "A")); assertEquals(2, gateway.calls)
            assertEquals(mapOf("new" to "New"), request(enabled = true).glossaryUsed)
            assertEquals(3, gateway.calls)
        }
        bitmap.recycle()
    }

    @Test fun `late chunk failure does not return partial translations and cancellation propagates`() = runBlocking {
        val gateway = PageGateway(context)
        val coordinator = VlPageTranslationCoordinator(gateway, FloatingTranslationCacheStore(context))
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        val layout = VlPageLayout(200, 400, listOf(bubble(1, RectF(20f, 20f, 80f, 80f)), bubble(2, RectF(20f, 250f, 80f, 300f))),
            tiles = listOf(DetectionTile(0, 0, 200, 200), DetectionTile(0, 200, 200, 400)))
        gateway.failAt = 2
        PipelineBitmapDecoder.openCropSource(bitmap).use { source ->
            assertThrows(LlmResponseException::class.java) { runBlocking {
                coordinator.translate(source, layout, SettingsStore(context).load(), TranslationLanguage.JA_TO_ZH, 1000, 0)
            } }
            gateway.cancel = true
            assertThrows(CancellationException::class.java) { runBlocking {
                coordinator.translate(source, layout, SettingsStore(context).load(), TranslationLanguage.JA_TO_ZH, 1000, 0)
            } }
        }
        assertTrue(layout.targets.all { it.translatedText.isEmpty() })
        bitmap.recycle()
    }
}

private class PageGateway(private val context: Context) : LlmGateway {
    var imageCount = 0
    var batchIds = emptyList<Int>()
    var invalidBatch = false
    override suspend fun translateMultiImageItems(imagesBase64: List<String>, requestedIds: List<Int>, promptAsset: String,
        glossary: Map<String, String>, glossaryProcessingEnabled: Boolean, requestTimeoutMs: Int,
        retryCount: Int, apiSettings: ApiSettings): LlmBubbleTranslationResult {
        imageCount = imagesBase64.size
        batchIds = requestedIds
        val result = translateImageItems(imagesBase64.first(), requestedIds, promptAsset, glossary,
            glossaryProcessingEnabled, requestTimeoutMs, retryCount, apiSettings)
        return if (invalidBatch) result.copy(items = result.items.drop(1)) else result
    }
    var calls = 0
    var failAt = -1
    var cancel = false
    var emptyIds: Set<Int> = emptySet()
    override fun resourceContext() = context
    override fun isConfigured(apiSettings: ApiSettings?) = true
    override fun isOcrConfigured() = false
    override suspend fun translateImageItems(imageBase64: String, requestedIds: List<Int>, promptAsset: String,
        glossary: Map<String, String>, glossaryProcessingEnabled: Boolean, requestTimeoutMs: Int,
        retryCount: Int, apiSettings: ApiSettings): LlmBubbleTranslationResult {
        calls++
        if (cancel) throw CancellationException()
        if (failAt >= 0 && calls >= failAt) throw LlmResponseException(LlmErrorCode.MissingTranslationItems, "missing")
        return LlmBubbleTranslationResult(requestedIds.reversed().map { LlmBubbleTranslationItem(it, if (it in emptyIds) "" else "translated-$it") }, mapOf("new" to "New"))
    }
    override suspend fun translateBubbleItems(items: List<LlmBubbleTranslationRequestItem>, glossary: Map<String, String>, promptAsset: String,
        requestTimeoutMs: Int?, retryCount: Int, apiSettings: ApiSettings?, glossaryProcessingEnabled: Boolean): LlmBubbleTranslationResult? = error("OCR path must not be used")
    override suspend fun translateImageBubble(imageBase64: String, promptAsset: String, glossary: Map<String, String>, glossaryProcessingEnabled: Boolean,
        requestTimeoutMs: Int?, retryCount: Int, apiSettings: ApiSettings?): LlmTranslationResult? = error("Single bubble path must not be used")
    override suspend fun extractGlossary(text: String, glossary: Map<String, String>, promptAsset: String): Map<String, String>? = null
    override suspend fun recognizeImageText(image: Bitmap, language: TranslationLanguage): String? = error("OCR must not run")
}
