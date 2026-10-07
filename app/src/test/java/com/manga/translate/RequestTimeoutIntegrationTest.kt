package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.ApiFormat
import com.manga.translate.network.*
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.SettingsStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RequestTimeoutIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LlmClient
    private lateinit var settings: ApiSettings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().apply { start() }
        client = LlmClient(context, SettingsStore(context))
        settings = ApiSettings(apiUrl = server.url("/").toString(), apiKey = "key",
            modelName = "test", apiFormat = ApiFormat.OPENAI_COMPATIBLE)
    }

    @After
    fun tearDown() { server.shutdown() }

    @Test
    fun `real model response retries cannot reset the request budget`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{}"}}]}""")
            .setBodyDelay(600, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{}"}}]}""")
            .setBodyDelay(600, TimeUnit.MILLISECONDS))
        try {
            executeWithModelResponseRetries("Test") {
                client.translateBubbleItems(listOf(LlmBubbleTranslationRequestItem(1, "source")),
                    emptyMap(), requestTimeoutMs = 1_000, retryCount = 1, apiSettings = settings)
            }
            fail("Expected shared budget expiration before another invalid response")
        } catch (error: LlmRequestException) {
            assertEquals(LlmErrorCode.Timeout, error.errorCode)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `invalid response retry reuses the first request deadline`() = runBlocking {
        val context = RequestBudgetContext()
        var firstBudget: RequestTimeoutBudget? = null
        var attempt = 0
        val error = try {
            withContext(context) {
                executeWithModelResponseRetries("Test") {
                    val budget = currentCoroutineContext()[RequestBudgetContext]!!.getOrStart(1_000, 1)
                    if (firstBudget == null) firstBudget = budget else assertSame(firstBudget, budget)
                    attempt++
                    budget.run {
                        delay(650)
                        throw LlmResponseException(LlmErrorCode.InvalidResponse, "invalid")
                    }
                }
            }
            error("Expected total budget to expire")
        } catch (e: LlmRequestException) { e }
        assertEquals(LlmErrorCode.Timeout, error.errorCode)
        assertEquals(2, attempt)
    }

    @Test
    fun `retry backoff consumes the budget and does not send another request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        val context = RequestBudgetContext().apply { getOrStart(1_000, 1) }
        try {
            withContext(context) {
                client.translate("hello", emptyMap(), retryCount = 3, apiSettings = settings)
            }
            fail("Expected timeout during backoff")
        } catch (error: LlmRequestException) {
            assertEquals(LlmErrorCode.Timeout, error.errorCode)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `total budget cancels stalled response body and stops retries`() = runBlocking {
        server.enqueue(MockResponse().setBody("body").setBodyDelay(10, TimeUnit.SECONDS))
        val context = RequestBudgetContext().apply { getOrStart(1_000, 1) }
        withTimeout(4_000) {
            try {
                withContext(context) {
                    client.translate("hello", emptyMap(), retryCount = 3, apiSettings = settings)
                }
                fail("Expected timeout while consuming response")
            } catch (error: LlmRequestException) {
                assertEquals(LlmErrorCode.Timeout, error.errorCode)
            }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `user cancellation closes a response after headers arrive`() = runBlocking {
        server.enqueue(MockResponse().setBody("body").setBodyDelay(10, TimeUnit.SECONDS))
        val reading = CountDownLatch(1)
        val request = Request.Builder().url(server.url("/")).build()
        val job = async(Dispatchers.IO) {
            HttpTransport().executeRequest(request, 30_000) { response ->
                reading.countDown()
                response.body.string()
            }
        }
        assertTrue(withContext(Dispatchers.IO) { reading.await(3, TimeUnit.SECONDS) })
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }
}
