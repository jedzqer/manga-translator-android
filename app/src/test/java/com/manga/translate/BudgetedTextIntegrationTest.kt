package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.ThinkingLength
import com.manga.translate.model.ApiFormat
import com.manga.translate.network.*
import com.manga.translate.settings.*
import com.manga.translate.translation.TextBudgetUnit
import com.manga.translate.translation.FullTranslationSynopsis
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BudgetedTextIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var store: SettingsStore
    private lateinit var client: LlmClient
    private lateinit var api: ApiSettings

    @Before fun setup() {
        context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve(SettingsStore.AI_PROVIDER_PROFILES_FILE_NAME).delete()
        server = MockWebServer().apply { start() }
        store = SettingsStore(context)
        api = ApiSettings(server.url("/").toString(), "key", "model")
        store.save(api)
        store.saveLlmParameters(store.loadLlmParameters().copy(maxOutputTokens = 512, enableThinking = false))
        client = LlmClient(context, store)
    }
    @After fun cleanup() { server.shutdown() }

    private fun response(content: String, finish: String = "stop", format: ApiFormat = api.apiFormat): MockResponse {
        val root = when (format) {
            ApiFormat.OPENAI_COMPATIBLE -> JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("finish_reason", finish).put("message", JSONObject().put("content", content))))
            ApiFormat.OPENAI_RESPONSES -> JSONObject().put("output_text", content)
                .put("status", if (finish == "length") "incomplete" else "completed")
                .put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
            ApiFormat.GEMINI -> JSONObject().put("candidates", JSONArray().put(JSONObject()
                .put("finishReason", if (finish == "length") "MAX_TOKENS" else "STOP")
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", content))))))
        }
        return MockResponse().setBody(root.toString())
    }
    private fun translated(vararg ids: Int) = JSONObject().put("items", JSONArray(ids.map {
        JSONObject().put("id", it).put("translation", "translated-$it")
    })).put("glossary_used", JSONObject()).toString()

    private fun items() = listOf(LlmBubbleTranslationRequestItem(1, "first", "p1"),
        LlmBubbleTranslationRequestItem(2, "second", "p1"), LlmBubbleTranslationRequestItem(3, "third", "p2"))

    private fun request(): JSONObject = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
    private fun user(payload: JSONObject, format: ApiFormat = api.apiFormat): String = when (format) {
        ApiFormat.OPENAI_COMPATIBLE -> payload.getJSONArray("messages").let { it.getJSONObject(it.length() - 1).getString("content") }
        ApiFormat.OPENAI_RESPONSES -> payload.getJSONArray("input").let { it.getJSONObject(it.length() - 1).getString("content") }
        ApiFormat.GEMINI -> payload.getJSONArray("contents").let { it.getJSONObject(it.length() - 1).getJSONArray("parts").getJSONObject(0).getString("text") }
    }

    private fun outputCap(payload: JSONObject): Int = when (api.apiFormat) {
        ApiFormat.OPENAI_COMPATIBLE -> payload.getInt("max_tokens")
        ApiFormat.OPENAI_RESPONSES -> payload.getInt("max_output_tokens")
        ApiFormat.GEMINI -> payload.getJSONObject("generationConfig").getInt("maxOutputTokens")
    }

    @Test fun `full synopsis follows successful budget splits and stays out of ordinary translation`() = runBlocking {
        val synopsis = FullTranslationSynopsis()
        val pages = listOf(TextBudgetUnit("p1", "first"), TextBudgetUnit("p2", "second"))
        server.enqueue(response("{", "length"))
        server.enqueue(response("""{"glossary_used":{"name":"Name"},"synopsis":"First event."}"""))
        server.enqueue(response("""{"glossary_used":{},"synopsis":"First and second events."}"""))
        withContext(synopsis) {
            client.extractGlossaryPages(pages, emptyMap(), "prompts/llm_prompts_abstract.json")
            assertEquals("First and second events.", synopsis.text)
            assertTrue(user(request()).contains("\"synopsis\":\"\""))
            assertTrue(user(request()).contains("\"synopsis\":\"\""))
            val second = user(request())
            assertTrue(second.contains("First event."))
            assertTrue(second.contains("Name"))
            // Retrying a window must not append already committed pages to the synopsis.
            client.extractGlossaryPages(pages, emptyMap(), "prompts/llm_prompts_abstract.json")
            assertEquals(3, server.requestCount)
            server.enqueue(response(translated(1, 2, 3)))
            client.translateBubbleItems(items(), emptyMap(), promptAsset = "prompts/llm_prompts_FullTrans.json")
            assertTrue(user(request()).contains("First and second events."))
            server.enqueue(response(translated(1, 2, 3)))
            client.translateBubbleItems(items(), emptyMap())
            assertFalse(user(request()).contains("\"synopsis\":"))
        }
        server.enqueue(response("""{"glossary_used":{}}"""))
        client.extractGlossaryPages(pages, emptyMap(), "prompts/llm_prompts_abstract.json")
        assertFalse(user(request()).contains("\"synopsis\":"))
    }

    @Test fun `failed later batch preserves synopsis and resumes only pending pages`() = runBlocking {
        val synopsis = FullTranslationSynopsis()
        val pages = listOf(TextBudgetUnit("p1", "first"), TextBudgetUnit("p2", "second"))
        val names = linkedMapOf<String, String>()
        server.enqueue(response("{", "length"))
        server.enqueue(response("""{"glossary_used":{"name":"Name"},"synopsis":"First event."}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        withContext(synopsis) {
            try {
                client.extractGlossaryPages(pages, names, "prompts/llm_prompts_abstract.json") { names.putAll(it) }
                fail("Expected failed second batch")
            } catch (expected: LlmRequestException) {
                assertEquals(LlmErrorCode.Http(401), expected.errorCode)
            }
            repeat(3) { request() }
            assertEquals("First event.", synopsis.text)
            assertEquals("Name", names["name"])
            assertTrue(synopsis.isComplete("p1"))
            assertFalse(synopsis.isComplete("p2"))
            server.enqueue(response("""{"glossary_used":{},"synopsis":"Both events."}"""))
            client.extractGlossaryPages(pages, names, "prompts/llm_prompts_abstract.json")
            val resumed = user(request())
            val input = JSONObject(resumed.substring(resumed.indexOf('{')))
            assertEquals("second", input.getString("text"))
            assertEquals("First event.", input.getString("synopsis"))
            assertEquals("Both events.", synopsis.text)
        }
    }

    @Test fun `synopsis requires explicit strings and bounds oversized output`() {
        val synopsis = FullTranslationSynopsis()
        synopsis.commit(listOf("p1"), "x".repeat(9000))
        assertEquals(6000, synopsis.text.length)
        synopsis.commit(listOf("p3"), "")
        assertEquals(6000, synopsis.text.length)
        assertTrue(synopsis.isComplete("p3"))
        val parser = ResponseParser()
        assertEquals("", parser.parseSynopsis("""{"glossary_used":{},"synopsis":""}"""))
        try {
            parser.parseSynopsis("""{"glossary_used":{}}""")
            fail("Legacy response without synopsis must fail")
        } catch (expected: LlmResponseException) {
            assertEquals(LlmErrorCode.InvalidFormat, expected.errorCode)
        }
        try {
            parser.parseSynopsis("""{"glossary_used":{},"synopsis":42}""")
            fail("Non-string synopsis must not be committed")
        } catch (expected: LlmResponseException) {
            assertEquals(LlmErrorCode.InvalidFormat, expected.errorCode)
        }
    }

    @Test fun `automatic translation and glossary caps include fixed thinking budgets`() = runBlocking {
        for (format in ApiFormat.entries) {
            api = api.copy(apiFormat = format)
            store.save(api)
            for (length in listOf(ThinkingLength.MEDIUM, ThinkingLength.HIGH, ThinkingLength.XHIGH)) {
                store.saveLlmParameters(store.loadLlmParameters().copy(
                    maxOutputTokens = null, enableThinking = true, thinkingLength = length))
                val reasoning = if (format == ApiFormat.GEMINI) length.geminiThinkingBudget()
                    else length.openAiBudgetTokens()
                server.enqueue(response(translated(1, 2, 3)))
                client.translateBubbleItems(items(), emptyMap(), apiSettings = api)
                assertEquals(8192 + reasoning, outputCap(request()))
                server.enqueue(response("""{"glossary_used":{}}"""))
                client.extractGlossaryPages(listOf(TextBudgetUnit("p1", "first")),
                    emptyMap(), "prompts/llm_prompts_abstract.json") {}
                assertEquals(4096 + reasoning, outputCap(request()))
            }
        }
    }

    @Test fun `explicit caps and disabled thinking retain their output allocations`() = runBlocking {
        for (format in ApiFormat.entries) {
            api = api.copy(apiFormat = format)
            store.saveLlmParameters(store.loadLlmParameters().copy(
                maxOutputTokens = 512, enableThinking = true, thinkingLength = ThinkingLength.HIGH))
            server.enqueue(response(translated(1, 2, 3)))
            client.translateBubbleItems(items(), emptyMap(), apiSettings = api)
            assertEquals(512, outputCap(request()))
            store.saveLlmParameters(store.loadLlmParameters().copy(maxOutputTokens = null, enableThinking = false))
            server.enqueue(response(translated(1, 2, 3)))
            client.translateBubbleItems(items(), emptyMap(), apiSettings = api)
            assertEquals(8192, outputCap(request()))
        }
    }

    @Test fun `automatic thinking allocation exceeding model output is rejected without requests`() = runBlocking {
        for (format in ApiFormat.entries) {
            api = api.copy(apiFormat = format)
            store.save(api)
            store.saveLlmParameters(store.loadLlmParameters().copy(
                maxOutputTokens = null, enableThinking = true, thinkingLength = ThinkingLength.HIGH))
            store.saveModelContextSettings(store.loadModelContextSettings().copy(
                limits = ModelContextLimits(maxOutputTokens = 8192)))
            try {
                client.extractGlossaryPages(listOf(TextBudgetUnit("p1", "first")),
                    emptyMap(), "prompts/llm_prompts_abstract.json") {}
                fail()
            } catch (e: LlmResponseException) {
                assertEquals("TEXT_BUDGET_EXCEEDED", e.errorCode.value)
                assertFalse(e.retryable)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `truncated requests shrink only at whole image boundaries for every protocol`() = runBlocking {
        for (format in ApiFormat.entries) {
            api = api.copy(apiFormat = format)
            server.enqueue(response("{\"items\":[", "length"))
            server.enqueue(response(translated(1, 2)))
            server.enqueue(response(translated(3)))
            val result = client.translateBubbleItems(items(), emptyMap(), apiSettings = api)!!
            assertEquals(listOf(1, 2, 3), result.items.map { it.id })
            val original = user(request())
            val first = user(request())
            val second = user(request())
            assertTrue(original.contains("third"))
            assertTrue(first.contains("first") && first.contains("second"))
            assertFalse(first.contains("third"))
            assertTrue(second.contains("third"))
            assertFalse(second.contains("first"))
        }
        assertEquals(9, server.requestCount)
    }

    @Test fun `explicit context overflow shrinks but auth failure does not`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"context_length_exceeded"}}"""))
        server.enqueue(response(translated(1, 2)))
        server.enqueue(response(translated(3)))
        assertEquals(3, client.translateBubbleItems(items(), emptyMap(), apiSettings = api)!!.items.size)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"context_length_exceeded"}}"""))
        try { client.translateBubbleItems(items(), emptyMap(), apiSettings = api); fail() }
        catch (e: LlmRequestException) { assertEquals(LlmErrorCode.Http(401), e.errorCode) }
        assertEquals(4, server.requestCount)
    }

    @Test fun `single image truncation never retries its bubbles or outer request`() = runBlocking {
        server.enqueue(response("{", "length"))
        try {
            executeWithModelResponseRetries("Test") {
                client.translateBubbleItems(items().take(2), emptyMap(), apiSettings = api)
            }
            fail()
        } catch (e: LlmResponseException) {
            assertEquals(LlmErrorCode.ResponseTruncated, e.errorCode)
            assertFalse(e.retryable)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `invalid IDs are retried within budget and never returned as partial success`() = runBlocking {
        repeat(3) { server.enqueue(response(translated(1, 1, 999))) }
        try {
            executeWithModelResponseRetries("Test") {
                client.translateBubbleItems(items(), emptyMap(), apiSettings = api)
            }
            fail()
        } catch (e: LlmResponseException) { assertFalse(e.retryable) }
        assertEquals(3, server.requestCount)
    }

    @Test fun `input preflight packs complete images and rejects oversized single image`() = runBlocking {
        store.saveModelContextSettings(store.loadModelContextSettings().copy(limits = ModelContextLimits(18000, 512, 20000)))
        val source = listOf(LlmBubbleTranslationRequestItem(1, "x".repeat(9000), "p1"),
            LlmBubbleTranslationRequestItem(2, "y".repeat(9000), "p2"))
        server.enqueue(response(translated(1)))
        server.enqueue(response(translated(2)))
        assertEquals(2, client.translateBubbleItems(source, emptyMap(), apiSettings = api)!!.items.size)
        assertFalse(user(request()).contains("y".repeat(9000)))
        assertFalse(user(request()).contains("x".repeat(9000)))
        try { client.translateBubbleItems(listOf(source[0].copy(text = "x".repeat(20000))), emptyMap(), apiSettings = api); fail() }
        catch (e: LlmResponseException) { assertEquals("TEXT_BUDGET_EXCEEDED", e.errorCode.value) }
        assertEquals(2, server.requestCount)
    }

    @Test fun `glossary merges serially preserves successes and rejects malformed final response`() = runBlocking {
        val saved = linkedMapOf<String, String>()
        server.enqueue(response("{", "length"))
        server.enqueue(response("""{"glossary_used":{"new":"译名"}}"""))
        repeat(3) { server.enqueue(response("{broken")) }
        try {
            client.extractGlossaryPages(listOf(TextBudgetUnit("p1", "first"), TextBudgetUnit("p2", "second")),
                mapOf("old" to "旧译名"), "prompts/llm_prompts_abstract.json") { saved.putAll(it) }
            fail()
        } catch (e: LlmResponseException) { assertEquals(LlmErrorCode.InvalidFormat, e.errorCode) }
        assertEquals(mapOf("new" to "译名"), saved)
        request(); request()
        val next = user(request())
        assertTrue(next.contains("译名"))
        assertTrue(next.contains("second"))
        assertFalse(next.contains("first"))
    }

    @Test fun `subrequests retain original deadline and cancellation`() = runBlocking {
        server.enqueue(response("{", "length").setBodyDelay(600, TimeUnit.MILLISECONDS))
        server.enqueue(response(translated(1, 2)).setBodyDelay(600, TimeUnit.MILLISECONDS))
        try { client.translateBubbleItems(items(), emptyMap(), requestTimeoutMs = 1000, retryCount = 1, apiSettings = api); fail() }
        catch (e: LlmRequestException) { assertEquals(LlmErrorCode.Timeout, e.errorCode) }
        assertEquals(2, server.requestCount)
    }

    @Test fun `custom output override is rejected before any network call`() = runBlocking {
        store.saveCustomRequestParameters(listOf(CustomRequestParameter("max_tokens", "9999", true)))
        try { client.translateBubbleItems(items(), emptyMap(), apiSettings = api); fail() }
        catch (e: LlmRequestException) { assertEquals(LlmErrorCode.CustomParamConflict, e.errorCode) }
        assertEquals(0, server.requestCount)
    }
}
