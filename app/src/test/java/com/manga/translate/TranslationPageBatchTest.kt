package com.manga.translate

import com.manga.translate.di.appContainer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.network.LlmBubbleTranslationItem
import com.manga.translate.network.LlmBubbleTranslationRequestItem
import com.manga.translate.network.LlmBubbleTranslationResult
import com.manga.translate.network.LlmGateway
import com.manga.translate.network.LlmResponseException
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.translation.TranslationPipeline
import com.manga.translate.translation.buildPageTranslationBatches
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TranslationPageBatchTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val settings = SettingsStore(context)
    private val gateway = BatchGateway(context)
    private val pipeline = TranslationPipeline(context, llmClient = gateway, settingsStore = settings)

    @Before
    fun setUp() {
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings.saveApiTimeoutSeconds(60)
        settings.saveApiRetryCount(5)
    }

    @Test
    fun `different folder styles stay isolated and saved results survive setting changes`() = runBlocking {
        val container = context.appContainer
        val first = requireNotNull(container.libraryRepository.createFolder("style_first_${System.nanoTime()}"))
        val second = requireNotNull(container.libraryRepository.createFolder("style_second_${System.nanoTime()}"))
        try {
            container.libraryPreferencesGateway.setTranslationStyle(first, "first style")
            container.libraryPreferencesGateway.setTranslationStyle(second, "second style")
            val pages = listOf(first, second).map { folder ->
                page(java.io.File(folder, "page.jpg").path, listOf(bubble(1, "source")))
            }
            val results = requireNotNull(translate(pages))
            assertEquals(listOf("first style", "second style"), gateway.styles)
            assertEquals(2, gateway.calls)
            pipeline.saveResult(pages[0].imageFile, results[0].result)
            val json = container.translationStore.translationFileFor(pages[0].imageFile)
            val saved = json.readText()
            container.libraryPreferencesGateway.setTranslationStyle(first, "changed style")
            val loaded = requireNotNull(pipeline.loadValidTranslation(pages[0].imageFile))
            assertEquals("translated:source", loaded.bubbles.single().translatedText)
            assertTrue(pipeline.hasValidTranslation(pages[0].imageFile))
            assertEquals(saved, json.readText())
        } finally {
            listOf(first, second).forEach {
                container.libraryPreferencesGateway.clearFolderSettings(it)
                it.deleteRecursively()
            }
        }
    }

    @Test
    fun `batch sends one request in page order and restores local ids and geometry`() = runBlocking {
        val contour = floatArrayOf(1f, 2f, 3f, 4f)
        val first = page("first.jpg", listOf(
            bubble(9, "first bottom", 200f),
            bubble(3, "first top", 100f).copy(source = BubbleSource.BUBBLE_DETECTOR, maskContour = contour)
        ))
        val second = page("second.jpg", listOf(bubble(3, "second top", 0f)))
        val results = requireNotNull(translate(listOf(first, second)))

        assertEquals(1, gateway.calls)
        assertEquals(listOf("first top", "first bottom", "second top"), gateway.items.map { it.text })
        assertEquals(listOf(0, 1, 2), gateway.items.map { it.id })
        assertEquals(listOf(3, 9), results[0].result.bubbles.map { it.id })
        assertEquals(3, results[1].result.bubbles.single().id)
        assertEquals("translated:second top", results[1].result.bubbles.single().translatedText)
        assertSame(first.bubbles[1].rect, results[0].result.bubbles[0].rect)
        assertSame(contour, results[0].result.bubbles[0].maskContour)
        assertEquals(BubbleSource.BUBBLE_DETECTOR, results[0].result.bubbles[0].source)
        assertEquals(0f, results[1].result.bubbles.single().rect.top)
        assertEquals(listOf("first.jpg", "second.jpg"), results.map { it.result.imageName })
        assertEquals(120_000, gateway.timeout)
        assertEquals(5, gateway.retries)
        assertEquals(mapOf("name" to "译名"), gateway.glossary)
        assertEquals(mapOf("new" to "新译名"), results.first().glossaryUsed)
        assertTrue(results.all { it.result.metadata.status == PageTranslationStatus.SUCCESS })
        assertTrue(results.all { it.result.metadata.ocrCacheMode == "test-ocr" })
    }

    @Test
    fun `eight text pages cap the request timeout at fifteen minutes`() = runBlocking {
        settings.saveApiTimeoutSeconds(300)
        val pages = List(8) { page("cap-$it.jpg", listOf(bubble(0, "text-$it"))) }
        assertEquals(8, requireNotNull(translate(pages)).size)
        assertEquals(900_000, gateway.timeout)
        assertEquals(1, gateway.calls)
    }

    @Test
    fun `five pages in two-page batches make three requests including the tail`() = runBlocking {
        val pages = List(5) { index -> page("page-$index.jpg", listOf(bubble(0, "text-$index"))) }
        val batches = buildPageTranslationBatches(List(pages.size) { true }, 2)
        val translated = batches.flatMap { indices -> requireNotNull(translate(indices.map { pages[it] })) }
        assertEquals(3, gateway.calls)
        assertEquals(pages.map { it.imageFile.name }, translated.map { it.result.imageName })
        assertEquals(60_000, gateway.timeout)
        assertEquals(List(5) { index -> "translated:text-$index" }, translated.map { it.result.bubbles.single().translatedText })
    }

    @Test
    fun `blank translations only remove the matching bubble on its own page`() = runBlocking {
        gateway.response = { items ->
            items.map { LlmBubbleTranslationItem(it.id, if (it.id == 0) "" else it.text) }
        }
        val results = requireNotNull(translate(listOf(
            page("first.jpg", listOf(bubble(1, "remove"))),
            page("second.jpg", listOf(bubble(1, "keep")))
        )))
        assertTrue(results[0].result.bubbles.isEmpty())
        assertEquals("keep", results[1].result.bubbles.single().translatedText)
    }

    @Test
    fun `empty pages are retained without increasing request timeout`() = runBlocking {
        val results = requireNotNull(translate(listOf(
            page("empty.jpg", emptyList()),
            page("text.jpg", listOf(bubble(0, "text")))
        )))
        assertEquals(2, results.size)
        assertTrue(results.first().result.bubbles.isEmpty())
        assertEquals(60_000, gateway.timeout)
        assertEquals(1, gateway.calls)
    }

    @Test
    fun `all empty pages do not call AI`() = runBlocking {
        val results = requireNotNull(translate(listOf(
            page("empty.jpg", emptyList()), page("blank.jpg", listOf(bubble(1, " ")))
        )))
        assertEquals(0, gateway.calls)
        assertTrue(results.all { it.result.bubbles.isEmpty() })
        assertTrue(requireNotNull(translate(emptyList())).isEmpty())
    }

    @Test
    fun `missing duplicate and extra ids reject the whole batch`() {
        val invalidResponses = listOf<(List<LlmBubbleTranslationRequestItem>) -> List<LlmBubbleTranslationItem>>(
            { listOf(LlmBubbleTranslationItem(0, "only first")) },
            { listOf(LlmBubbleTranslationItem(0, "first"), LlmBubbleTranslationItem(0, "duplicate")) },
            { listOf(LlmBubbleTranslationItem(0, "first"), LlmBubbleTranslationItem(1, "second"), LlmBubbleTranslationItem(2, "extra")) }
        )
        invalidResponses.forEach { response ->
            gateway.response = response
            val error = assertThrows(LlmResponseException::class.java) {
                runBlocking {
                    translate(listOf(
                        page("first.jpg", listOf(bubble(1, "first"))),
                        page("second.jpg", listOf(bubble(1, "second")))
                    ))
                }
            }
            assertTrue(error.responseContent.contains("first.jpg"))
            assertTrue(error.responseContent.contains("second.jpg"))
        }
    }

    @Test
    fun `full translation keeps its mode and prompt`() = runBlocking {
        val results = requireNotNull(pipeline.translatePagesWithGlossary(
            listOf(page("first.jpg", listOf(bubble(1, "first"))), page("second.jpg", listOf(bubble(1, "second")))),
            emptyMap(), "prompts/llm_prompts_FullTrans.json", TranslationLanguage.JA_TO_ZH,
            TranslationMetadata.MODE_FULL_PAGE
        ))
        assertEquals("prompts/llm_prompts_FullTrans.json", gateway.prompt)
        assertTrue(results.all { it.result.metadata.mode == TranslationMetadata.MODE_FULL_PAGE })
    }

    @Test
    fun `single page modes share bubble filtering and result assembly`() = runBlocking {
        val contour = floatArrayOf(1f, 2f, 3f, 4f)
        val page = page("single.jpg", listOf(
            bubble(1, "keep").copy(source = BubbleSource.BUBBLE_DETECTOR, maskContour = contour),
            bubble(2, "remove", 50f)
        ))
        gateway.response = { items ->
            items.map { LlmBubbleTranslationItem(it.id, if (it.text == "remove") "" else "translated:${it.text}") }
        }
        val standard = requireNotNull(pipeline.translateStandardPageWithGlossary(
            page, page.imageFile, emptyMap(), onProgress = {}
        ))
        assertEquals("prompts/llm_prompts.json", gateway.prompt)
        val full = requireNotNull(pipeline.translateFullPageWithGlossary(
            page, emptyMap(), "prompts/llm_prompts_FullTrans.json", onProgress = {}
        ))
        assertEquals("prompts/llm_prompts_FullTrans.json", gateway.prompt)
        assertEquals(2, gateway.calls)
        assertEquals(listOf(1), standard.result.bubbles.map { it.id })
        assertEquals(standard.result.bubbles, full.result.bubbles)
        assertSame(page.bubbles[0].rect, full.result.bubbles.single().rect)
        assertSame(contour, full.result.bubbles.single().maskContour)
        assertEquals(PageTranslationStatus.SUCCESS, standard.result.metadata.status)
        assertEquals(PageTranslationStatus.SUCCESS, full.result.metadata.status)
        assertEquals(TranslationMetadata.MODE_STANDARD, standard.result.metadata.mode)
        assertEquals(TranslationMetadata.MODE_FULL_PAGE, full.result.metadata.mode)
        assertEquals(mapOf("new" to "新译名"), standard.glossaryUsed)
        assertEquals(standard.glossaryUsed, full.glossaryUsed)
    }

    @Test
    fun `empty single pages succeed without a model request`() = runBlocking {
        val page = page("empty.jpg", emptyList())
        val standard = requireNotNull(pipeline.translateStandardPageWithGlossary(
            page, page.imageFile, emptyMap(), onProgress = {}
        ))
        val full = requireNotNull(pipeline.translateFullPageWithGlossary(
            page, emptyMap(), "prompts/llm_prompts_FullTrans.json", onProgress = {}
        ))
        assertEquals(0, gateway.calls)
        assertTrue(standard.result.bubbles.isEmpty())
        assertTrue(full.result.bubbles.isEmpty())
        assertEquals(PageTranslationStatus.SUCCESS, standard.result.metadata.status)
        assertEquals(PageTranslationStatus.SUCCESS, full.result.metadata.status)
    }

    @Test
    fun `cancellation is propagated without retry`() {
        gateway.response = { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) {
            runBlocking { translate(listOf(page("first.jpg", listOf(bubble(1, "first"))))) }
        }
        assertEquals(1, gateway.calls)
    }

    private suspend fun translate(pages: List<PageOcrResult>) = pipeline.translatePagesWithGlossary(
        pages, mapOf("name" to "译名"), "prompts/llm_prompts.json", TranslationLanguage.JA_TO_ZH,
        TranslationMetadata.MODE_STANDARD
    )

    private fun page(name: String, bubbles: List<OcrBubble>) =
        PageOcrResult(File(name), 800, 1200, bubbles, cacheMode = "test-ocr")

    private fun bubble(id: Int, text: String, top: Float = 0f) =
        OcrBubble(id, RectF(10f, top, 100f, top + 30f), text)
}

private class BatchGateway(private val context: Context) : LlmGateway {
    val styles = mutableListOf<String?>()
    var calls = 0
    var items = emptyList<LlmBubbleTranslationRequestItem>()
    var timeout: Int? = null
    var retries = 0
    var prompt = ""
    var glossary = emptyMap<String, String>()
    var response: (List<LlmBubbleTranslationRequestItem>) -> List<LlmBubbleTranslationItem> = { request ->
        request.reversed().map { LlmBubbleTranslationItem(it.id, "translated:${it.text}") }
    }

    override fun isConfigured(apiSettings: ApiSettings?) = true
    override fun isOcrConfigured() = true
    override fun resourceContext() = context

    override suspend fun translateBubbleItems(
        items: List<LlmBubbleTranslationRequestItem>,
        glossary: Map<String, String>,
        promptAsset: String,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?, glossaryProcessingEnabled: Boolean
    ): LlmBubbleTranslationResult {
        styles += apiSettings?.translationStyle
        calls++
        this.items = items
        this.glossary = glossary
        timeout = requestTimeoutMs
        retries = retryCount
        prompt = promptAsset
        return LlmBubbleTranslationResult(response(items), mapOf("new" to "新译名"))
    }

    override suspend fun extractGlossary(text: String, glossary: Map<String, String>, promptAsset: String) =
        emptyMap<String, String>()
    override suspend fun recognizeImageText(image: Bitmap, language: TranslationLanguage): String? = null
    override suspend fun translateImageBubble(
        imageBase64: String,
        promptAsset: String,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?
    ): com.manga.translate.network.LlmTranslationResult? = null
}
