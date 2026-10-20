package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Looper
import com.manga.translate.di.appContainer
import com.manga.translate.library.LibraryUiCallbacks
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.AppLanguage
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.OcrMetadata
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.platform.PromptAssetResolver
import com.manga.translate.platform.TranslationCancellationRegistry
import com.manga.translate.settings.ApiSettings
import com.manga.translate.translation.buildDetectionStrategyTag
import com.manga.translate.translation.FolderOcrPreparation
import com.manga.translate.translation.FolderPageTranslationExecutor
import com.manga.translate.translation.FolderTranslationGlossary
import com.manga.translate.translation.FolderTranslationScheduler
import com.manga.translate.translation.PreparedStandardPage
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FolderTranslationBehaviorTest {
    @Test
    fun `single and merged requests bound concurrency and merge independent glossary snapshots`() {
        for ((full, batchSize) in listOf(false to 1, false to 2, true to 2)) {
            withFixture(6, concurrency = 2, batchSize = batchSize) { f ->
                val entered = CountDownLatch(2)
                val release = CountDownLatch(1)
                val releaseSecond = CountDownLatch(1)
                val active = AtomicInteger()
                val peak = AtomicInteger()
                val requests = AtomicInteger()
                val blockedPage = AtomicInteger(-1)
                val secondPage = AtomicInteger(-1)
                val firstSnapshots = Collections.synchronizedList(mutableListOf<Set<String>>())
                f.dispatch { payload ->
                    if (payload.has("text")) return@dispatch f.response(JSONObject().put("glossary_used", JSONObject()))
                    val inFlight = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, inFlight) }
                    try {
                        val request = requests.incrementAndGet()
                        if (request <= 2) {
                            val page = payload.getJSONArray("items").getJSONObject(0)
                                .getString("text").substringAfterLast('_').toInt()
                            (if (request == 1) blockedPage else secondPage).set(page)
                            firstSnapshots.add(payload.getJSONObject("glossary").keys().asSequence().toSet())
                            entered.countDown()
                            check((if (request == 1) release else releaseSecond).await(20, TimeUnit.SECONDS))
                        }
                        // Every already-saved page must have durable glossary additions.
                        // Observe pages before the glossary so concurrent commits cannot
                        // compare a newly saved page against an older glossary snapshot.
                        val savedPages = f.images.withIndex().filter {
                            f.container.translationStore.load(it.value) != null
                        }
                        val durable = f.container.glossaryStore.load(f.folder, f.targetKey)
                        savedPages.forEach { (index, _) ->
                            assertEquals("译名$index", durable["term_source_$index"])
                        }
                        f.translationResponse(payload)
                    } finally {
                        active.decrementAndGet()
                    }
                }
                try {
                    val task = f.start(full)
                    f.await { entered.count == 0L }
                    assertEquals(2, peak.get())
                    assertEquals(listOf(setOf("existing"), setOf("existing")), firstSnapshots.toList())
                    releaseSecond.countDown()
                    // Force the later request to be saved before the first completes.
                    f.await { f.container.translationStore.load(f.images[secondPage.get()]) != null }
                    assertNull(f.container.translationStore.load(f.images[blockedPage.get()]))
                    release.countDown()
                    f.finish(task)
                    assertTrue(peak.get() <= 2)
                    assertEquals(if (batchSize == 1) 6 else 3, requests.get())
                    assertEquals(mapOf("existing" to "保留") + (0..5).associate { "term_source_$it" to "译名$it" },
                        f.container.glossaryStore.load(f.folder, f.targetKey))
                    f.images.forEach { assertNotNull(f.container.translationStore.load(it)) }
                    assertEquals(f.context.getString(R.string.translation_done), com.manga.translate.platform.GlobalTaskProgressStore.state.value.detail)
                } finally {
                    release.countDown()
                    releaseSecond.countDown()
                }
            }
        }
    }

    @Test
    fun `user cancellation stops queued work preserves completed data and releases task ownership`() {
        withFixture(120, concurrency = 2) { f ->
            val existingBytes = f.saveExisting()
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            f.dispatch { payload ->
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                f.translationResponse(payload)
            }
            try {
                val task = f.start()
                f.await { entered.count == 0L }
                assertTrue(TranslationCancellationRegistry.requestCancel())
                f.finish(task)
                assertTrue(task.isCancelled)
                assertEquals(f.context.getString(R.string.translation_canceled), com.manga.translate.platform.GlobalTaskProgressStore.state.value.detail)
                assertTrue(f.ui.enabled.get())
                assertFalse(TranslationCancellationRegistry.requestCancel())
                assertEquals(2, f.server.requestCount)
                assertArrayEquals(existingBytes, f.container.translationStore.translationFileFor(f.images.first()).readBytes())
                f.images.drop(1).forEach { assertNull(f.container.translationStore.load(it)) }
                assertEquals(mapOf("existing" to "保留"), f.container.glossaryStore.load(f.folder, f.targetKey))
                assertTrue(f.container.translationProgressStore.fileFor(f.folder).exists())
                // The same coordinator accepts another task after cancellation cleanup.
                val next = f.start(images = f.images.take(1))
                f.finish(next)
                assertFalse(next.isCancelled)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `request failure keeps earlier success and prior translation and aborts later requests`() {
        withFixture(5) { f ->
            val existingBytes = f.saveExisting()
            f.dispatch { payload ->
                val source = payload.getJSONArray("items").getJSONObject(0).getString("text")
                if (source == "source_2") MockResponse().setResponseCode(401).setBody("denied")
                else f.translationResponse(payload)
            }
            f.finish(f.start())
            assertEquals(2, f.server.requestCount)
            assertNotNull(f.ui.error.get())
            assertEquals(f.context.getString(R.string.translation_failed), com.manga.translate.platform.GlobalTaskProgressStore.state.value.detail)
            assertArrayEquals(existingBytes, f.container.translationStore.translationFileFor(f.images.first()).readBytes())
            assertEquals("translated:source_1", f.container.translationStore.load(f.images[1])?.bubbles?.single()?.translatedText)
            f.images.drop(2).forEach { assertNull(f.container.translationStore.load(it)) }
            assertEquals(mapOf("existing" to "保留", "term_source_1" to "译名1"),
                f.container.glossaryStore.load(f.folder, f.targetKey))
            val progress = runBlocking { f.container.translationProgressStore.load(f.folder) }
            assertEquals(com.manga.translate.storage.PageProgressStatus.SAVED, progress[f.images[1].name]?.status)
            f.images.drop(2).forEach { assertEquals(com.manga.translate.storage.PageProgressStatus.FAILED, progress[it.name]?.status) }
        }
    }

    @Test
    fun `invalid model response skips only its group and completes the tail`() {
        for ((full, batchSize) in listOf(false to 1, false to 2, true to 1, true to 2)) {
            withFixture(5, batchSize = batchSize) { f ->
                f.dispatch { payload ->
                    if (payload.has("text")) return@dispatch f.response(JSONObject().put("glossary_used", JSONObject()))
                    val source = payload.getJSONArray("items").getJSONObject(0).getString("text")
                    if (source == "source_0") f.response(JSONObject().put("items", JSONArray()))
                    else f.translationResponse(payload)
                }
                val task = f.start(full)
                f.finish(task)
                assertFalse(task.isCancelled)
                assertNull(f.ui.error.get())
                assertEquals(1, f.ui.modelErrors.get())
                assertEquals(f.context.getString(R.string.translation_done), com.manga.translate.platform.GlobalTaskProgressStore.state.value.detail)
                val progress = runBlocking { f.container.translationProgressStore.load(f.folder) }
                f.images.forEachIndexed { index, image ->
                    val result = requireNotNull(f.container.translationStore.load(image))
                    if (index < batchSize) {
                        assertEquals(PageTranslationStatus.SKIPPED, result.metadata.status)
                        assertEquals(com.manga.translate.storage.PageProgressStatus.SKIPPED, progress[image.name]?.status)
                    } else {
                        assertEquals("translated:source_$index", result.bubbles.single().translatedText)
                        assertEquals(com.manga.translate.storage.PageProgressStatus.SAVED, progress[image.name]?.status)
                    }
                }
            }
        }
    }

    @Test
    fun `partial refill failure preserves completed bubbles and continues later pages`() {
        for (full in listOf(false, true)) {
            withFixture(3, batchSize = 2) { f ->
                val image = f.images.first()
                val partial = f.pipeline.buildBlankTranslationResult(
                    PageOcrResult(image, 100, 100, emptyList()), TranslationMetadata.MODE_STANDARD,
                    "prompts/llm_prompts.json", TranslationLanguage.JA_TO_ZH
                ).copy(bubbles = listOf(
                    BubbleTranslation(5, RectF(1f, 1f, 30f, 30f), "old", "preserved"),
                    BubbleTranslation.pending(6, RectF(40f, 40f, 90f, 90f), "pending")
                )).let { it.copy(metadata = it.metadata.copy(status = PageTranslationStatus.PARTIAL)) }
                runBlocking { f.pipeline.saveResult(image, partial) }
                val before = f.container.translationStore.translationFileFor(image).readBytes()
                val refills = AtomicInteger()
                f.dispatch { payload ->
                    if (payload.has("text")) return@dispatch f.response(JSONObject().put("glossary_used", JSONObject()))
                    val items = payload.getJSONArray("items")
                    if (items.getJSONObject(0).getString("text") == "refill_source") {
                        refills.incrementAndGet()
                        assertEquals(1, items.length())
                        f.response(JSONObject().put("items", JSONArray()))
                    } else f.translationResponse(payload)
                }
                f.finish(f.start(full))
                assertTrue(refills.get() > 0)
                assertArrayEquals(before, f.container.translationStore.translationFileFor(image).readBytes())
                assertEquals(PageTranslationStatus.PARTIAL, f.container.translationStore.load(image)?.metadata?.status)
                f.images.drop(1).forEach { assertNotNull(f.container.translationStore.load(it)) }
                assertNull(f.ui.error.get())
                assertEquals(0, f.ui.modelErrors.get())
                assertEquals(f.context.getString(R.string.translation_done), com.manga.translate.platform.GlobalTaskProgressStore.state.value.detail)
            }
        }
    }

    @Test
    fun `forced prepared batch replaces complete and partial results with new page geometry`() {
        withFixture(2, batchSize = 2) { f ->
            f.saveExisting()
            val existing = requireNotNull(f.container.translationStore.load(f.images.first()))
            runBlocking {
                f.pipeline.saveResult(f.images[1], existing.copy(
                    imageName = f.images[1].name,
                    bubbles = listOf(
                        BubbleTranslation(5, RectF(1f, 1f, 30f, 30f), "old", "preserved"),
                        BubbleTranslation.pending(6, RectF(40f, 40f, 90f, 90f), "pending")
                    ),
                    metadata = existing.metadata.copy(status = PageTranslationStatus.PARTIAL)
                ))
            }
            f.dispatch { payload ->
                val items = payload.getJSONArray("items")
                assertEquals(listOf("source_0", "source_1"),
                    (0 until items.length()).map { items.getJSONObject(it).getString("text") })
                f.translationResponse(payload)
            }
            val glossary = FolderTranslationGlossary(
                f.container.glossaryStore, f.container.extractStateStore, f.container.llmClient
            ) { f.targetKey }
            val preparation = FolderOcrPreparation(f.context, f.pipeline, f.settings) { _, _ -> }
            val executor = FolderPageTranslationExecutor(
                f.pipeline, f.settings, f.container.libraryPreferencesGateway, f.ui,
                f.container.createPendingBubbleRetranslator(), glossary
            ) { error("Unexpected model error: $it") }
            val scheduler = FolderTranslationScheduler(
                f.pipeline, f.settings, f.container.translationProgressStore, preparation, executor, glossary
            )
            val counts = mutableListOf<Pair<Int, Int>>()
            // Feed newly prepared OCR at the extraction boundary, without invoking native detection.
            val summary = runBlocking {
                scheduler.executePreparedStandardPages(
                    pages = f.images,
                    preparedPages = f.images.mapIndexed { index, image ->
                        PreparedStandardPage(image, PageOcrResult(image, 100, 100,
                            listOf(OcrBubble(0, RectF(10f, 10f, 90f, 90f), "source_$index"))))
                    },
                    folder = f.folder,
                    force = true,
                    glossaryProcessingEnabled = true,
                    useVlDirectTranslate = false,
                    language = TranslationLanguage.JA_TO_ZH,
                    glossary = glossary.loadScopedGlossary(f.folder),
                    glossaryMutex = Mutex()
                ) { processed, failed -> counts.add(processed to failed) }
            }
            f.serverError.get()?.let { throw AssertionError("Request assertion failed", it) }
            assertFalse(summary.hasFailures)
            assertEquals(0, summary.failedCount)
            assertEquals(2 to 0, counts.last())
            assertEquals(1, f.server.requestCount)
            f.images.forEachIndexed { index, image ->
                val result = requireNotNull(f.container.translationStore.load(image))
                assertEquals(PageTranslationStatus.SUCCESS, result.metadata.status)
                val bubble = result.bubbles.single()
                assertEquals(0, bubble.id)
                assertEquals(RectF(10f, 10f, 90f, 90f), bubble.rect)
                assertEquals("translated:source_$index", bubble.translatedText)
            }
        }
    }

    private fun withFixture(count: Int, concurrency: Int = 1, batchSize: Int = 1, action: (Fixture) -> Unit) {
        Fixture(count, concurrency, batchSize).use(action)
    }

    private class Fixture(count: Int, concurrency: Int, batchSize: Int) : AutoCloseable {
        val context: Context = RuntimeEnvironment.getApplication()
        val container = context.appContainer
        val settings = container.settingsStore
        val server = MockWebServer()
        val folder: File
        val images: List<File>
        val pipeline = container.createTranslationPipeline()
        val targetKey: String
        val ui = TestUi()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val serverError = AtomicReference<Throwable?>()
        val coordinator by lazy { container.createFolderTranslationCoordinator(pipeline, ui,
                com.manga.translate.platform.GlobalTaskProgressStore.begin(java.util.UUID.randomUUID().toString(), "Translation", "Preparing")) }

        init {
            context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE).edit().clear().commit()
            settings.saveAppLanguage(AppLanguage.SIMPLIFIED_CHINESE)
            settings.saveApiRetryCount(0)
            settings.saveMaxConcurrency(concurrency)
            settings.saveTranslationBatchPages(batchSize)
            server.start()
            settings.save(ApiSettings(server.url("/").toString(), "key", "model", ApiFormat.OPENAI_COMPATIBLE))
            settings.saveOcrApiSettings(settings.loadOcrApiSettings().copy(
                useLocalOcr = false, apiUrl = server.url("/").toString(), apiKey = "key", modelName = "ocr-test"
            ))
            targetKey = PromptAssetResolver.translationTargetKey(context)
            folder = requireNotNull(container.libraryRepository.createFolder("behavior_${System.nanoTime()}"))
            images = List(count) { File(folder, "%04d.png".format(it)) }
            val cacheMode = "api|${buildDetectionStrategyTag(100, 100)}|bubbles_and_text"
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                images.forEachIndexed { index, image ->
                    image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    container.ocrStore.save(image, PageOcrResult(image, 100, 100,
                        listOf(OcrBubble(0, RectF(10f, 10f, 90f, 90f), "source_$index")), cacheMode,
                        OcrMetadata(cacheMode = cacheMode, language = TranslationLanguage.JA_TO_ZH.name, engineModel = "api:ocr-test|masked-tiles-v1")))
                }
            } finally { bitmap.recycle() }
            container.glossaryStore.save(folder, mapOf("existing" to "保留"), targetKey)
        }

        fun dispatch(handler: (JSONObject) -> MockResponse) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = try {
                    val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                    val content = messages.getJSONObject(messages.length() - 1).get("content")
                    if (content is JSONArray) {
                        MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
                            JSONObject().put("message", JSONObject().put("content", "refill_source")))).toString())
                    } else {
                        val text = content as String
                        handler(JSONObject(text.substring(text.indexOf('{'))))
                    }
                } catch (error: Throwable) {
                    serverError.compareAndSet(null, error)
                    MockResponse().setResponseCode(400)
                }
            }
        }

        fun response(content: JSONObject) = MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", content.toString())))).toString())

        fun translationResponse(payload: JSONObject): MockResponse {
            val items = payload.getJSONArray("items")
            val output = JSONArray()
            val glossary = JSONObject()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val source = item.getString("text")
                output.put(JSONObject().put("id", item.getInt("id")).put("translation", "translated:$source"))
                glossary.put("term_$source", "译名${source.substringAfterLast('_')}")
            }
            return response(JSONObject().put("items", output).put("glossary_used", glossary))
        }

        fun saveExisting(): ByteArray = runBlocking {
            pipeline.saveResult(images.first(), pipeline.buildBlankTranslationResult(
                PageOcrResult(images.first(), 100, 100, emptyList()), TranslationMetadata.MODE_STANDARD,
                "prompts/llm_prompts.json", TranslationLanguage.JA_TO_ZH))
            container.translationStore.translationFileFor(images.first()).readBytes()
        }

        fun start(full: Boolean = false, images: List<File> = this.images): Job = requireNotNull(coordinator.translateFolder(
            scope, folder, images, false, full, true, false, TranslationLanguage.JA_TO_ZH) { ui.enabled.set(it) })

        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!condition() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            serverError.get()?.let { throw AssertionError("Request assertion failed", it) }
            assertTrue("Task timed out: ${ui.error.get()}", condition())
        }

        fun finish(task: Job) = await { task.isCompleted }

        override fun close() {
            scope.cancel()
            server.shutdown()
            container.libraryPreferencesGateway.clearFolderSettings(folder)
            folder.deleteRecursively()
        }
    }

    private class TestUi : LibraryUiCallbacks {
        val error = AtomicReference<String?>()
        val enabled = AtomicReference(true)
        val modelErrors = AtomicInteger()
        override fun setFolderStatus(left: String, right: String) = Unit
        override fun clearFolderStatus() = Unit
        override fun setTranslationActionsEnabled(enabled: Boolean) = Unit
        override fun showToast(resId: Int) = Unit
        override fun showToastMessage(message: String) = Unit
        override fun showApiError(code: String, detail: String?) { error.set("$code: $detail") }
        override fun showModelError(content: String, useSystemOverlay: Boolean, onRetry: (() -> Unit)?, onSkip: (() -> Unit)?) {
            modelErrors.incrementAndGet()
            onSkip?.invoke()
        }
        override fun refreshFolders() = Unit
        override fun refreshImages(folder: File) = Unit
        override fun isUiAttached() = true
        override fun isFragmentActive() = true
        override fun isAppInForeground() = true
        override fun isLibraryInForeground() = true
        override fun canShowSystemOverlay() = false
    }
}
