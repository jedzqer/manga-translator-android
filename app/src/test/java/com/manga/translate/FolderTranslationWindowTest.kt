package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Looper
import com.manga.translate.di.appContainer
import com.manga.translate.library.LibraryUiCallbacks
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.AppLanguage
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.OcrMetadata
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.platform.PromptAssetResolver
import com.manga.translate.translation.FolderTranslationTask
import com.manga.translate.translation.TRANSLATION_WINDOW_PAGES
import com.manga.translate.translation.buildDetectionStrategyTag
import com.manga.translate.settings.ApiSettings
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class FolderTranslationWindowTest {
    @Test
    fun `folder saves first window before requesting next glossary and preserves existing results`() = checkTask(false)

    @Test
    fun `batch uses the same windows with cumulative glossary and completed page counts`() = checkTask(true)

    @Test
    fun `folder expands automatic windows for larger batches`() = checkTask(false, batchSize = 200)

    @Test
    fun `batch expands automatic windows for larger batches`() = checkTask(true, batchSize = 200)

    @Test
    fun `standard batches preserve page order partial refill and disabled glossary context`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val container = context.appContainer
        val settings = container.settingsStore
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings.saveAppLanguage(AppLanguage.SIMPLIFIED_CHINESE)
        settings.saveApiRetryCount(0)
        settings.saveMaxConcurrency(1)
        settings.saveTranslationBatchPages(2)
        val folder = requireNotNull(container.libraryRepository.createFolder("standard_${System.nanoTime()}"))
        val images = List(8) { File(folder, "%04d.png".format(it)) }
        val pipeline = container.createTranslationPipeline()
        val targetKey = PromptAssetResolver.translationTargetKey(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ui = TestUi()
        val server = MockWebServer()
        val serverError = AtomicReference<Throwable?>()
        val translatedGroups = mutableListOf<List<String>>()
        val cacheMode = "api|${buildDetectionStrategyTag(100, 100)}|bubbles_and_text"
        try {
            server.start()
            settings.save(ApiSettings(server.url("/").toString(), "test-key", "test-model", ApiFormat.OPENAI_COMPATIBLE))
            settings.saveOcrApiSettings(settings.loadOcrApiSettings().copy(
                useLocalOcr = false, apiUrl = server.url("/").toString(), apiKey = "key", modelName = "ocr-test"
            ))
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                for ((index, image) in images.withIndex()) {
                    image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    container.ocrStore.save(image, PageOcrResult(
                        image, 100, 100, listOf(OcrBubble(0, RectF(10f, 10f, 90f, 90f), "source_$index")),
                        cacheMode = cacheMode,
                        metadata = OcrMetadata(cacheMode = cacheMode, language = TranslationLanguage.JA_TO_ZH.name, engineModel = "api:ocr-test|masked-tiles-v1")
                    ))
                }
            } finally { bitmap.recycle() }
            val existing = pipeline.buildBlankTranslationResult(
                PageOcrResult(images.first(), 100, 100, emptyList()),
                TranslationMetadata.MODE_STANDARD, "prompts/llm_prompts.json", TranslationLanguage.JA_TO_ZH
            )
            pipeline.saveResult(images.first(), existing)
            val existingFile = container.translationStore.translationFileFor(images.first())
            val existingBytes = existingFile.readBytes()
            val partial = existing.copy(imageName = images[3].name, bubbles = listOf(
                com.manga.translate.model.BubbleTranslation(5, RectF(1f, 1f, 30f, 30f), "old", "preserved"),
                com.manga.translate.model.BubbleTranslation.pending(6, RectF(40f, 40f, 90f, 90f), "pending")
            ), metadata = existing.metadata.copy(status = com.manga.translate.model.PageTranslationStatus.PARTIAL))
            pipeline.saveResult(images[3], partial)
            container.glossaryStore.save(folder, mapOf("existing" to "保留"), targetKey)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = try {
                    val body = JSONObject(request.body.readUtf8())
                    val messages = body.getJSONArray("messages")
                    val content = messages.getJSONObject(messages.length() - 1).get("content")
                    val response = if (content is JSONArray) {
                        "refill_source"
                    } else {
                        assertTrue(body.toString().contains("Glossary extraction is disabled"))
                        val text = content as String
                        val payload = JSONObject(text.substring(text.indexOf('{')))
                        assertEquals("保留", payload.getJSONObject("glossary").getString("existing"))
                        val items = payload.getJSONArray("items")
                        translatedGroups.add((0 until items.length()).map { items.getJSONObject(it).getString("text") })
                        val output = JSONArray()
                        for (index in 0 until items.length()) {
                            val item = items.getJSONObject(index)
                            output.put(JSONObject().put("id", item.getInt("id")).put("translation", "translated:${item.getString("text")}"))
                        }
                        JSONObject().put("items", output).put("glossary_used", JSONObject().put("unwanted", "new term")).toString()
                    }
                    MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
                        JSONObject().put("message", JSONObject().put("content", response))
                    )).toString())
                } catch (error: Throwable) {
                    serverError.compareAndSet(null, error)
                    MockResponse().setResponseCode(400).setBody("test assertion failed")
                }
            }
            val coordinator = container.createFolderTranslationCoordinator(pipeline, ui,
                com.manga.translate.platform.GlobalTaskProgressStore.begin(java.util.UUID.randomUUID().toString(), "Translation", "Preparing"))
            val task = requireNotNull(coordinator.translateFolder(
                scope, folder, images, false, false, false, false, TranslationLanguage.JA_TO_ZH
            ) { })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (!task.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Translation timed out: ${ui.error.get()}", task.isCompleted)
            serverError.get()?.let { throw AssertionError("Invalid request", it) }
            assertNull(ui.error.get())
            assertEquals(context.getString(R.string.translation_done), com.manga.translate.platform.GlobalTaskProgressStore.state.value.detail)
            assertEquals(listOf(listOf("source_1", "source_2"), listOf("refill_source"),
                listOf("source_4", "source_5"), listOf("source_6", "source_7")), translatedGroups)
            assertEquals(mapOf("existing" to "保留"), container.glossaryStore.load(folder, targetKey))
            assertEquals(listOf("preserved", "translated:refill_source"),
                container.translationStore.load(images[3])?.bubbles?.map { it.translatedText })
            assertArrayEquals(existingBytes, existingFile.readBytes())
            images.forEach { assertNotNull(container.translationStore.load(it)) }
        } finally {
            scope.cancel()
            server.shutdown()
            container.libraryPreferencesGateway.clearFolderSettings(folder)
            folder.deleteRecursively()
        }
    }

    @Test
    fun `legacy processing preference does not limit request batches`() = checkTask(false)

    private fun checkTask(batch: Boolean, batchSize: Int = TRANSLATION_WINDOW_PAGES) {
        val context: Context = RuntimeEnvironment.getApplication()
        val container = context.appContainer
        val settings = container.settingsStore
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings.saveAppLanguage(AppLanguage.SIMPLIFIED_CHINESE)
        settings.saveApiRetryCount(0)
        settings.saveMaxConcurrency(1)
        settings.saveTranslationBatchPages(batchSize)
        // A previously saved manual window must no longer affect scheduling.
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE).edit()
            .putInt("translation_processing_pages", 20).commit()
        val windowSize = batchSize
        val effectiveBatchSize = settings.loadTranslationBatchPages()
        assertEquals(batchSize, effectiveBatchSize)
        settings.saveOcrApiSettings(settings.loadOcrApiSettings().copy(useLocalOcr = false, modelName = "ocr-test"))
        val folder = requireNotNull(container.libraryRepository.createFolder("windows_${System.nanoTime()}"))
        val images = List(windowSize + 2) { File(folder, "%04d.png".format(it)) }
        val pipeline = container.createTranslationPipeline()
        val targetKey = PromptAssetResolver.translationTargetKey(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ui = TestUi()
        val server = MockWebServer()
        val serverError = AtomicReference<Throwable?>()
        var glossaryRequests = 0
        fun expectedGlossary() = mapOf("existing" to "保留") +
            (1..glossaryRequests).associate { "term$it" to "译名$it" }
        val translatedSources = mutableListOf<String>()
        val requestSizes = mutableListOf<Int>()
        val cacheMode = "api|${buildDetectionStrategyTag(100, 100)}|bubbles_and_text"
        fun source(index: Int) = "source_${index}_".padEnd(320, 'a')
        val firstWindowGlossaryRequests = 1
        try {
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                for ((index, image) in images.withIndex()) {
                    image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    container.ocrStore.save(image, PageOcrResult(
                        imageFile = image, width = 100, height = 100,
                        bubbles = listOf(OcrBubble(0, RectF(10f, 10f, 90f, 90f), source(index))),
                        cacheMode = cacheMode,
                        metadata = OcrMetadata(cacheMode = cacheMode, language = TranslationLanguage.JA_TO_ZH.name, engineModel = "api:ocr-test|masked-tiles-v1")
                    ))
                }
            } finally {
                bitmap.recycle()
            }
            val existing = pipeline.buildBlankTranslationResult(
                PageOcrResult(images.first(), 100, 100, emptyList()),
                TranslationMetadata.MODE_FULL_PAGE, "prompts/llm_prompts_FullTrans.json", TranslationLanguage.JA_TO_ZH
            )
            pipeline.saveResult(images.first(), existing)
            val existingFile = container.translationStore.translationFileFor(images.first())
            val existingBytes = existingFile.readBytes()
            val originalOcr = images.associateWith { container.ocrStore.ocrFileFor(it).readBytes() }
            container.glossaryStore.save(folder, mapOf("existing" to "保留"), targetKey)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = try {
                    val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                    val content = messages.getJSONObject(messages.length() - 1).getString("content")
                    val payload = JSONObject(content.substring(content.indexOf('{')))
                    val glossary = payload.getJSONObject("glossary")
                    assertEquals("保留", glossary.getString("existing"))
                    for (index in 1..glossaryRequests) assertEquals("译名$index", glossary.getString("term$index"))
                    val response = if (payload.has("text")) {
                        assertEquals(if (glossaryRequests == 0) "" else "synopsis$glossaryRequests",
                            payload.getString("synopsis"))
                        val text = payload.getString("text")
                        assertTrue(Regex("<b>").findAll(text).count() <= windowSize)
                        assertEquals(Regex("<b>").findAll(text).count(), Regex("</b>").findAll(text).count())
                        assertFalse(text.contains("source_0_"))
                        if (text.contains("source_${windowSize + 1}_")) {
                            assertEquals(windowSize, translatedSources.size)
                            assertArrayEquals(originalOcr.getValue(images.last()), container.ocrStore.ocrFileFor(images.last()).readBytes())
                            for (image in images.subList(1, windowSize + 1)) {
                                assertTrue("First window must be saved before the next request", container.translationStore.translationFileFor(image).exists())
                            }
                        } else {
                            assertTrue(translatedSources.isEmpty())
                            for (image in images.subList(1, windowSize + 1)) {
                                assertArrayEquals(originalOcr.getValue(image), container.ocrStore.ocrFileFor(image).readBytes())
                            }
                        }
                        glossaryRequests++
                        JSONObject().put("synopsis", "synopsis$glossaryRequests").put("glossary_used", JSONObject()
                            .put("existing", "不应覆盖")
                            .put("term$glossaryRequests", "译名$glossaryRequests"))
                    } else {
                        assertEquals("synopsis$glossaryRequests", payload.getString("synopsis"))
                        assertEquals("Extracted glossary must be durable before translation",
                            expectedGlossary(), container.glossaryStore.load(folder, targetKey))
                        val items = payload.getJSONArray("items")
                        requestSizes.add(items.length())
                        assertTrue(items.length() <= effectiveBatchSize)
                        assertEquals(firstWindowGlossaryRequests + requestSizes.size - 1, glossaryRequests)
                        val output = JSONArray()
                        for (index in 0 until items.length()) {
                            val item = items.getJSONObject(index)
                            translatedSources.add(item.getString("text"))
                            output.put(JSONObject().put("id", item.getInt("id")).put("translation", "translated:${item.getString("text")}"))
                        }
                        JSONObject().put("items", output)
                    }
                    MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
                        JSONObject().put("message", JSONObject().put("content", response.toString()))
                    )).toString())
                } catch (error: Throwable) {
                    serverError.compareAndSet(null, error)
                    MockResponse().setResponseCode(400).setBody("test assertion failed")
                }
            }
            server.start()
            settings.save(ApiSettings(server.url("/").toString(), "test-key", "test-model", ApiFormat.OPENAI_COMPATIBLE))
            val coordinator = container.createFolderTranslationCoordinator(pipeline, ui,
                com.manga.translate.platform.GlobalTaskProgressStore.begin(java.util.UUID.randomUUID().toString(), "Translation", "Preparing"))
            val task = if (batch) {
                coordinator.translateBatch(scope, listOf(FolderTranslationTask(
                    folder, images, false, true, true, false, TranslationLanguage.JA_TO_ZH
                ))) { }
            } else {
                coordinator.translateFolder(scope, folder, images, false, true, true, false, TranslationLanguage.JA_TO_ZH) { }
            }
            requireNotNull(task)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (!task.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("Translation task timed out: ${ui.error.get()}", task.isCompleted)
            serverError.get()?.let { throw AssertionError("Request ordering or payload failed", it) }
            assertNull(ui.error.get())
            assertEquals(context.getString(R.string.translation_done), com.manga.translate.platform.GlobalTaskProgressStore.state.value.detail)
            assertEquals(listOf(windowSize, 1), requestSizes)
            assertEquals((1..windowSize + 1).map(::source), translatedSources)
            assertEquals(firstWindowGlossaryRequests + 1, glossaryRequests)
            assertEquals(expectedGlossary(), container.glossaryStore.load(folder, targetKey))
            assertEquals(images.drop(1).map { it.name }.toSet(), container.extractStateStore.load(folder, targetKey))
            assertArrayEquals(existingBytes, existingFile.readBytes())
            for (image in images) {
                assertNotNull(container.translationStore.load(image))
            }
        } finally {
            scope.cancel()
            server.shutdown()
            container.libraryPreferencesGateway.clearFolderSettings(folder)
            folder.deleteRecursively()
        }
    }

    private class TestUi : LibraryUiCallbacks {
        val error = AtomicReference<String?>()
        override fun setFolderStatus(left: String, right: String) = Unit
        override fun clearFolderStatus() = Unit
        override fun setTranslationActionsEnabled(enabled: Boolean) = Unit
        override fun showToast(resId: Int) = Unit
        override fun showToastMessage(message: String) = Unit
        override fun showApiError(code: String, detail: String?) { error.set("$code: $detail") }
        override fun showModelError(content: String, useSystemOverlay: Boolean, onRetry: (() -> Unit)?, onSkip: (() -> Unit)?) {
            error.set(content)
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
