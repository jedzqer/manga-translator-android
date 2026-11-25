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

    @Test fun `blank settings defer sampling output and thinking to all three protocols`() = runBlocking {
        for (format in ApiFormat.entries) {
            api = api.copy(apiFormat = format)
            store.saveLlmParameters(store.loadLlmParameters().copy(maxOutputTokens = null, enableThinking = null))
            server.enqueue(response(translated(1, 2, 3)))
            client.translateBubbleItems(items(), emptyMap(), apiSettings = api)
            val payload = request()
            val config = if (format == ApiFormat.GEMINI) payload.getJSONObject("generationConfig") else payload
            assertFalse(config.has("temperature"))
            assertFalse(config.has("top_p"))
            assertFalse(config.has("topP"))
            assertFalse(config.has("max_tokens"))
            assertFalse(config.has("max_output_tokens"))
            assertFalse(config.has("maxOutputTokens"))
            assertFalse(config.has("enable_thinking"))
            assertFalse(config.has("thinking_budget"))
            assertFalse(config.has("reasoning"))
            assertFalse(config.has("thinkingConfig"))
        }
    }

    @Test fun `full synopsis sends a cap only with an explicit request limit`() = runBlocking {
        assertTrue(store.saveModelContextSettings(store.loadModelContextSettings().copy(
            limits = ModelContextLimits(maxOutputTokens = 12800))))
        val synopsisResponse = """{"glossary_used":{},"synopsis":""}"""
        val pages = listOf(TextBudgetUnit("p1", "first"))
        val prompt = "prompts/llm_prompts_abstract.json"

        store.saveLlmParameters(store.loadLlmParameters().copy(maxOutputTokens = null, enableThinking = null))
        server.enqueue(response(synopsisResponse))
        withContext(FullTranslationSynopsis()) { client.extractGlossaryPages(pages, emptyMap(), prompt) }
        assertFalse(request().has("max_tokens"))

        store.saveLlmParameters(store.loadLlmParameters().copy(maxOutputTokens = 12800))
        server.enqueue(response(synopsisResponse))
        withContext(FullTranslationSynopsis()) { client.extractGlossaryPages(pages, emptyMap(), prompt) }
        assertEquals(12800, outputCap(request()))
    }

    @Test fun `explicit thinking and output settings use each protocol's fields`() = runBlocking {
        for (format in ApiFormat.entries) {
            api = api.copy(apiFormat = format)
            store.saveLlmParameters(store.loadLlmParameters().copy(
                maxOutputTokens = 32768, enableThinking = true, thinkingLength = ThinkingLength.HIGH))
            server.enqueue(response(translated(1, 2, 3)))
            client.translateBubbleItems(items(), emptyMap(), apiSettings = api)
            val payload = request()
            assertEquals(32768, outputCap(payload))
            when (format) {
                ApiFormat.OPENAI_COMPATIBLE -> {
                    assertTrue(payload.getBoolean("enable_thinking"))
                    assertEquals(8192, payload.getInt("thinking_budget"))
                }
                ApiFormat.OPENAI_RESPONSES -> {
                    assertEquals("high", payload.getJSONObject("reasoning").getString("effort"))
                    assertFalse(payload.has("enable_thinking"))
                    assertFalse(payload.has("thinking_budget"))
                }
                ApiFormat.GEMINI -> assertEquals(24576, payload.getJSONObject("generationConfig")
                    .getJSONObject("thinkingConfig").getInt("thinkingBudget"))
            }
        }
    }

    @Test fun `each protocol sends a setting for explicitly disabled thinking`() = runBlocking {
        store.saveLlmParameters(store.loadLlmParameters().copy(enableThinking = false))
        for (format in ApiFormat.entries) {
            api = api.copy(apiFormat = format)
            server.enqueue(response(translated(1, 2, 3)))
            client.translateBubbleItems(items(), emptyMap(), apiSettings = api)
            val payload = request()
            when (format) {
                ApiFormat.OPENAI_COMPATIBLE -> {
                    assertFalse(payload.getBoolean("enable_thinking"))
                    assertFalse(payload.has("thinking_budget"))
                }
                ApiFormat.OPENAI_RESPONSES ->
                    assertEquals("none", payload.getJSONObject("reasoning").getString("effort"))
                ApiFormat.GEMINI -> assertEquals(0, payload.getJSONObject("generationConfig")
                    .getJSONObject("thinkingConfig").getInt("thinkingBudget"))
            }
        }
    }

    @Test fun `enabling Responses thinking without picking a length sends medium effort`() = runBlocking {
        api = api.copy(apiFormat = ApiFormat.OPENAI_RESPONSES)
        val parameters = store.loadLlmParameters()
        assertEquals(ThinkingLength.AUTO, parameters.thinkingLength)
        store.saveLlmParameters(parameters.copy(enableThinking = true))
        server.enqueue(response(translated(1, 2, 3)))

        client.translateBubbleItems(items(), emptyMap(), apiSettings = api)

        assertEquals("medium", request().getJSONObject("reasoning").getString("effort"))
    }

    @Test fun `blank output cap preserves fixed Gemini thinking during single page preflight`() = runBlocking {
        api = api.copy(apiFormat = ApiFormat.GEMINI)
        store.save(api)
        store.saveLlmParameters(store.loadLlmParameters().copy(
            maxOutputTokens = null, enableThinking = true, thinkingLength = ThinkingLength.HIGH))
        val capability = store.loadModelContextSettings()
        assertTrue(store.saveModelContextSettings(capability.copy(
            limits = ModelContextLimits(maxOutputTokens = 8192, contextWindowTokens = 26000))))
        server.enqueue(response(translated(1)))
        val page = items().take(1)

        try {
            client.translateBubbleItems(page, emptyMap(), apiSettings = api)
            fail("The fixed thinking budget must survive the smaller single-page estimate")
        } catch (e: LlmResponseException) {
            assertEquals("TEXT_BUDGET_EXCEEDED", e.errorCode.value)
            assertTrue(e.responseContent.startsWith("CONTEXT_EXHAUSTED:"))
        }
        assertEquals(0, server.requestCount)

        assertTrue(store.saveModelContextSettings(capability.copy(
            limits = ModelContextLimits(maxOutputTokens = 8192, contextWindowTokens = 100000))))
        client.translateBubbleItems(page, emptyMap(), apiSettings = api)
        val config = request().getJSONObject("generationConfig")
        assertFalse(config.has("maxOutputTokens"))
        assertEquals(24576, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test fun `model capability rejects a conflicting explicit cap before sending`() = runBlocking {
        for (format in ApiFormat.entries) {
            api = api.copy(apiFormat = format)
            store.save(api)
            store.saveLlmParameters(store.loadLlmParameters().copy(maxOutputTokens = 8193))
            assertTrue(store.saveModelContextSettings(store.loadModelContextSettings().copy(
                limits = ModelContextLimits(maxOutputTokens = 8192))))
            try {
                client.translateBubbleItems(items(), emptyMap(), apiSettings = api)
                fail()
            } catch (e: LlmResponseException) {
                assertEquals("TEXT_BUDGET_EXCEEDED", e.errorCode.value)
                assertFalse(e.retryable)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `one page can use less estimated output room in a small context`() = runBlocking {
        store.saveLlmParameters(store.loadLlmParameters().copy(maxOutputTokens = null, enableThinking = null))
        assertTrue(store.saveModelContextSettings(store.loadModelContextSettings().copy(
            limits = ModelContextLimits(contextWindowTokens = 12000))))
        server.enqueue(response(translated(1)))
        client.translateBubbleItems(items().take(1), emptyMap(), apiSettings = api)
        assertFalse(request().has("max_tokens"))
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

    @Test fun `stress 500 pages preserve every bubble and unicode through bounded requests`() = runBlocking {
        val source = (0 until 500).flatMap { page ->
            (0 until 3).map { bubble -> LlmBubbleTranslationRequestItem(
                page * 3 + bubble, ("頁$page 氣泡$bubble 漢字😀\\\"\n").repeat(30), "page-$page") }
        }
        assertTrue(store.saveModelContextSettings(store.loadModelContextSettings().copy(
            limits = ModelContextLimits(18000, 512, 20000))))
        val received = java.util.Collections.synchronizedList(mutableListOf<Int>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val raw = request.body.readUtf8()
                assertTrue(raw.toByteArray(Charsets.UTF_8).size + 256 <= 18000)
                val text = user(JSONObject(raw))
                val sent = JSONObject(text.substring(text.indexOf('{'))).getJSONArray("items")
                assertEquals(0, sent.length() % 3)
                val ids = (0 until sent.length()).map { index ->
                    val item = sent.getJSONObject(index)
                    val id = item.getInt("id")
                    assertEquals(source[id].text, item.getString("text"))
                    id
                }
                assertEquals(0, ids.first() % 3)
                received.addAll(ids)
                return response(translated(*ids.toIntArray()))
            }
        }
        val result = client.translateBubbleItems(source, emptyMap(), requestTimeoutMs = 120000, apiSettings = api)!!
        assertEquals(source.map { it.id }, result.items.map { it.id })
        assertEquals(source.map { it.id }, received.toList())
        assertTrue(server.requestCount in 2..500)
        println("CONTEXT_STRESS pages=500 bubbles=1500 requests=${server.requestCount} unicode_preserved=true")
    }

    @Test fun `stress unknown context recursively shrinks 64 pages without partial page requests`() = runBlocking {
        val source = (0 until 64).flatMap { page -> (0 until 2).map { bubble ->
            LlmBubbleTranslationRequestItem(page * 2 + bubble, "page-$page bubble-$bubble", "page-$page")
        } }
        val succeeded = java.util.Collections.synchronizedList(mutableListOf<Int>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val text = user(JSONObject(request.body.readUtf8()))
                val sent = JSONObject(text.substring(text.indexOf('{'))).getJSONArray("items")
                val ids = (0 until sent.length()).map { sent.getJSONObject(it).getInt("id") }
                assertEquals(0, ids.first() % 2)
                assertEquals(0, ids.size % 2)
                if (ids.size > 8) return MockResponse().setResponseCode(400)
                    .setBody("""{"error":{"code":"context_length_exceeded"}}""")
                succeeded.addAll(ids)
                return response(translated(*ids.toIntArray()))
            }
        }
        val result = client.translateBubbleItems(source, emptyMap(), requestTimeoutMs = 120000, apiSettings = api)!!
        assertEquals(source.map { it.id }, result.items.map { it.id })
        assertEquals(source.map { it.id }, succeeded.toList())
        assertTrue(server.requestCount in 31..127)
        println("CONTEXT_STRESS unknown_limit pages=64 requests=${server.requestCount} complete=true")
    }

    @Test fun `stress repeated truncation exhausts shared attempts without outer retry reset`() = runBlocking {
        val source = (0 until 500).map { LlmBubbleTranslationRequestItem(it, "page-$it", "p$it") }
        var singles = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val text = user(JSONObject(request.body.readUtf8()))
                val sent = JSONObject(text.substring(text.indexOf('{'))).getJSONArray("items")
                if (sent.length() > 1) return response("{", "length")
                singles++
                return response(translated(sent.getJSONObject(0).getInt("id")))
            }
        }
        try {
            executeWithModelResponseRetries("Stress") {
                client.translateBubbleItems(source, emptyMap(), requestTimeoutMs = 120000, apiSettings = api)
            }
            fail("999 requests must not fit the shared 512-attempt ceiling")
        } catch (expected: LlmRequestException) {
            assertEquals("TEXT_ATTEMPTS_EXHAUSTED", expected.errorCode.value)
        }
        assertEquals(512, server.requestCount)
        assertTrue(singles in 1 until 500)
        println("CONTEXT_STRESS truncation pages=500 requests=${server.requestCount} partial_success_not_returned=true")
    }

}
