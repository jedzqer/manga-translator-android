package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.ApiFormat
import com.manga.translate.network.LlmClient
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.CustomRequestParameter
import com.manga.translate.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlmClientPayloadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setUp() {
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        settingsStore = SettingsStore(context)
        settingsStore.saveLlmParameters(
            settingsStore.loadLlmParameters().copy(maxOutputTokens = 321)
        )
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `chat payload uses chat token parameter and not responses parameter`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"{\"translation\":\"ok\"}"}}]}"""
            )
        )

        val result = LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())

        assertEquals("ok", result?.translation)
        assertEquals(321, payload.getInt("max_tokens"))
        assertFalse(payload.has("max_output_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
    }

    @Test
    fun `responses payload uses responses token parameter and not chat parameters`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"output_text":"{\"translation\":\"ok\"}"}"""
            )
        )

        val result = LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.OPENAI_RESPONSES)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())

        assertEquals("ok", result?.translation)
        assertEquals(321, payload.getInt("max_output_tokens"))
        assertFalse(payload.has("max_tokens"))
        assertFalse(payload.has("max_completion_tokens"))
    }

    @Test
    fun `gemini payload uses custom parameters for the primary provider`() = runBlocking {
        settingsStore.saveCustomRequestParameters(
            listOf(
                CustomRequestParameter("first_parameter", "true"),
                CustomRequestParameter("second_parameter", "42")
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"translation\":\"ok\"}"}]}}]}"""
            )
        )

        val result = LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.GEMINI)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())

        assertEquals("ok", result?.translation)
        assertTrue(payload.getBoolean("first_parameter"))
        assertEquals(42, payload.getInt("second_parameter"))
    }

    @Test
    fun `gemini payload omits thinking config when thinking is disabled`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"translation\":\"ok\"}"}]}}]}"""
            )
        )

        LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.GEMINI)
        )
        val payload = JSONObject(server.takeRequest().body.readUtf8())

        assertFalse(payload.getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test
    fun `truncated chat response raises response truncated error`() = runBlocking {
        // Content parses fine, but finish_reason=length means the translation was cut off;
        // it must surface as an error instead of being silently returned.
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"{\"translation\":\"ok\"}"},"finish_reason":"length"}]}"""
            )
        )

        val exception = org.junit.Assert.assertThrows(LlmResponseException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
                )
            }
        }

        assertEquals(LlmErrorCode.ResponseTruncated, exception.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `incomplete responses api response raises response truncated error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"output_text":"{\"translation\":\"ok\"}","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}"""
            )
        )

        val exception = org.junit.Assert.assertThrows(LlmResponseException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    apiSettings = apiSettings(ApiFormat.OPENAI_RESPONSES)
                )
            }
        }

        assertEquals(LlmErrorCode.ResponseTruncated, exception.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `truncated gemini response raises response truncated error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"translation\":\"ok\"}"}]},"finishReason":"MAX_TOKENS"}]}"""
            )
        )

        val exception = org.junit.Assert.assertThrows(LlmResponseException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    apiSettings = apiSettings(ApiFormat.GEMINI)
                )
            }
        }

        assertEquals(LlmErrorCode.ResponseTruncated, exception.errorCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `chat response with stop finish reason is accepted`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"{\"translation\":\"ok\"}"},"finish_reason":"stop"}]}"""
            )
        )

        val result = LlmClient(context, settingsStore).translate(
            text = "hello",
            glossary = emptyMap(),
            apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
        )

        assertEquals("ok", result?.translation)
    }

    @Test
    fun `invalid response is not retried inside the client`() = runBlocking {
        // Retrying invalid content here as well as in the caller's silent-retry wrapper would
        // multiply into retries x silentRetries real requests, so the client must fail fast and
        // leave the second enqueued response untouched.
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary failure"))

        org.junit.Assert.assertThrows(LlmResponseException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    retryCount = 2,
                    apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
                )
            }
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `transport failure is still retried inside the client`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary failure"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary failure"))

        org.junit.Assert.assertThrows(LlmRequestException::class.java) {
            runBlocking {
                LlmClient(context, settingsStore).translate(
                    text = "hello",
                    glossary = emptyMap(),
                    retryCount = 2,
                    apiSettings = apiSettings(ApiFormat.OPENAI_COMPATIBLE)
                )
            }
        }
        assertEquals(2, server.requestCount)
    }

    private fun apiSettings(apiFormat: ApiFormat): ApiSettings {
        return ApiSettings(
            apiUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            modelName = "test-model",
            apiFormat = apiFormat
        )
    }
}
