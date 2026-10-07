package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.*
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.FloatingTranslationCacheStore
import com.manga.translate.translation.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VlPageResponseRetryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var client: LlmClient
    private lateinit var settings: ApiSettings
    private lateinit var coordinator: VlPageTranslationCoordinator

    @Before
    fun setUp() {
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().apply { start() }
        client = LlmClient(context, SettingsStore(context))
        settings = ApiSettings(apiUrl = server.url("/").toString(), apiKey = "key",
            modelName = "vision", apiFormat = ApiFormat.OPENAI_COMPATIBLE)
        coordinator = VlPageTranslationCoordinator(client, FloatingTranslationCacheStore(context))
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun page(localId: Int, requestId: Int, image: String): VlPageTranslationCoordinator.PreparedPage {
        val target = BubbleTranslation.pending(localId, RectF(10f, 20f, 80f, 90f), "")
        return VlPageTranslationCoordinator.PreparedPage(VlPageLayout(100, 100, listOf(target)),
            listOf(image), mapOf(requestId to localId))
    }

    private fun response(content: String, finishReason: String = "stop"): MockResponse = MockResponse().setBody(
        JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", content)).put("finish_reason", finishReason))).toString()
    )

    private val valid = """{"items":[{"id":1,"translation":"second"},{"id":0,"translation":"first"}],"glossary_used":{"new":"New"}}"""

    @Test
    fun `merged malformed JSON retries identical payload and restores each page`() = runBlocking {
        val malformed = """{"items":[{"id":0,"translation":"first"},"{"id":1,"translation":"second"}]}"""
        server.enqueue(response(malformed))
        server.enqueue(response(valid))
        val pages = listOf(page(7, 0, "first-image"), page(3, 1, "second-image"))
        val result = coordinator.translatePreparedPages(pages, settings, 10_000, 1, mapOf("old" to "Old"), true)
        assertEquals(2, server.requestCount)
        val firstRequest = server.takeRequest().body.readUtf8()
        assertEquals(firstRequest, server.takeRequest().body.readUtf8())
        assertTrue(firstRequest.indexOf("first-image") < firstRequest.indexOf("second-image"))
        assertEquals(listOf(7, 3), result.map { it.bubbles.single().id })
        assertEquals(listOf("first", "second"), result.map { it.bubbles.single().translatedText })
        assertEquals(pages.map { it.layout.targets.single().rect }, result.map { it.bubbles.single().rect })
        assertTrue(result.all { it.glossaryUsed == mapOf("new" to "New") })
        assertTrue(pages.all { it.layout.targets.single().translatedText.isEmpty() })
    }

    @Test
    fun `single page retries format failure and caches only validated response`() = runBlocking {
        server.enqueue(response("not JSON"))
        server.enqueue(response("""{"items":[{"id":7,"translation":"ok"}],"glossary_used":{"new":"New"}}"""))
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val layout = page(7, 0, "unused").layout
        try {
            PipelineBitmapDecoder.openCropSource(bitmap).use { source ->
                suspend fun translate() = coordinator.translate(source, layout, settings,
                    TranslationLanguage.JA_TO_ZH, 10_000, 1, useCache = true)
                val first = translate()
                assertEquals("ok", first.bubbles.single().translatedText)
                assertTrue(first.glossaryUsed.isEmpty())
                assertEquals(first.bubbles.single().translatedText, translate().bubbles.single().translatedText)
                assertEquals(2, server.requestCount)
                assertEquals(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun `merged response retries duplicate extra missing IDs and invalid fields`() = runBlocking {
        val invalid = listOf(
            """{"items":[{"id":0,"translation":"x"},{"id":0,"translation":"y"}]}""",
            """{"items":[{"id":0,"translation":"x"},{"id":1,"translation":"y"},{"id":2,"translation":"extra"}]}""",
            """{"items":[{"id":0,"translation":"x"}]}""",
            """{"items":[{"id":0,"text":"source"},{"id":1,"translation":"y"}]}""",
            """{"items":[{"id":0,"translation":null},{"id":1,"translation":"y"}]}""",
            ""
        )
        for (content in invalid) {
            val before = server.requestCount
            server.enqueue(response(content))
            server.enqueue(response(valid))
            val result = coordinator.translatePreparedPages(listOf(page(7, 0, "image"), page(3, 1, "image")),
                settings, 10_000, 1, emptyMap(), false)
            assertEquals(before + 2, server.requestCount)
            assertEquals(listOf("first", "second"), result.map { it.bubbles.single().translatedText })
            assertTrue(result.all { it.glossaryUsed.isEmpty() })
        }
    }

    @Test
    fun `empty translations are valid and truncated responses retry`() = runBlocking {
        val empty = """{"items":[{"id":0,"translation":""},{"id":1,"translation":""}]}"""
        server.enqueue(response(empty, "length"))
        server.enqueue(response(empty))
        val result = coordinator.translatePreparedPages(listOf(page(7, 0, "image"), page(3, 1, "image")),
            settings, 10_000, 1, emptyMap(), false)
        assertEquals(2, server.requestCount)
        assertTrue(result.all { it.bubbles.isEmpty() })
    }

    @Test
    fun `invalid image content is never retried inside the client`() = runBlocking {
        server.enqueue(response("not JSON"))
        server.enqueue(response(valid))
        val error = assertThrows(LlmResponseException::class.java) { runBlocking {
            client.translateMultiImageItems(listOf("image"), listOf(0, 1), VlPageTranslationCoordinator.PROMPT_ASSET,
                emptyMap(), false, 10_000, 5, settings)
        } }
        assertEquals(LlmErrorCode.InvalidFormat, error.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `persistent response errors stop after three attempts without mutating pages`() = runBlocking {
        repeat(3) { server.enqueue(response("not JSON")) }
        server.enqueue(response(valid))
        val pages = listOf(page(7, 0, "image"), page(3, 1, "image"))
        val error = assertThrows(LlmResponseException::class.java) { runBlocking {
            coordinator.translatePreparedPages(pages, settings, 10_000, 5, emptyMap(), true)
        } }
        assertEquals(LlmErrorCode.InvalidFormat, error.errorCode)
        assertEquals(3, server.requestCount)
        assertTrue(pages.all { it.layout.targets.single().translatedText.isEmpty() })
    }

    @Test
    fun `VL response retries share one deadline and cancel stalled body`() = runBlocking {
        server.enqueue(response("not JSON").setBodyDelay(600, TimeUnit.MILLISECONDS))
        server.enqueue(response(valid).setBodyDelay(600, TimeUnit.MILLISECONDS))
        server.enqueue(response(valid))
        withTimeout(4_000) {
            val error = assertThrows(LlmRequestException::class.java) { runBlocking {
                coordinator.translatePreparedPages(listOf(page(7, 0, "image"), page(3, 1, "image")),
                    settings, 1_000, 1, emptyMap(), false)
            } }
            assertEquals(LlmErrorCode.Timeout, error.errorCode)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `network backoff after invalid response consumes the same VL budget`() = runBlocking {
        server.enqueue(response("not JSON"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        server.enqueue(response(valid))
        val budgetContext = RequestBudgetContext().apply { getOrStart(1_000, 1) }
        val error = assertThrows(LlmRequestException::class.java) { runBlocking(budgetContext) {
            coordinator.translatePreparedPages(listOf(page(7, 0, "image"), page(3, 1, "image")),
                settings, 10_000, 5, emptyMap(), false)
        } }
        assertEquals(LlmErrorCode.Timeout, error.errorCode)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `authentication failures and caller cancellation do not trigger VL response retries`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        val pages = listOf(page(7, 0, "image"))
        val error = assertThrows(LlmRequestException::class.java) { runBlocking {
            coordinator.translatePreparedPages(pages, settings, 10_000, 5, emptyMap(), false)
        } }
        assertEquals(LlmErrorCode.Http(401), error.errorCode)
        assertEquals(1, server.requestCount)
        val job = Job().apply { cancel() }
        assertThrows(CancellationException::class.java) { runBlocking(job) {
            coordinator.translatePreparedPages(pages, settings, 10_000, 5, emptyMap(), false)
        } }
        assertEquals(1, server.requestCount)
    }
}
