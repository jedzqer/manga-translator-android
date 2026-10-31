package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.di.appContainer
import com.manga.translate.di.AppContainer
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.OcrMetadata
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.OcrApiSettings
import com.manga.translate.storage.TranslationTaskPersistence
import com.manga.translate.translation.FolderTranslationTask
import com.manga.translate.translation.buildDetectionStrategyTag
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchTranslationDeviceTest {
    @Test
    fun largeBatchRunsInForegroundServiceAndPersistsEveryPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa")) { "Device test requires isolated QA package" }
        val container = context.appContainer
        val settings = container.settingsStore
        val pageCount = 600
        val requestBatch = 5
        val folder = requireNotNull(container.libraryRepository.createFolder("device_qa_${System.nanoTime()}"))
        val images = (0 until pageCount).map { index -> File(folder, "%04d.png".format(index)) }
        val requestItemCounts = ConcurrentLinkedQueue<Int>()
        val serverFailure = AtomicReference<Throwable?>()
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        listener.soTimeout = TimeUnit.MINUTES.toMillis(10).toInt()
        val serverThread = Thread {
            try {
                repeat(pageCount / requestBatch + if (pageCount % requestBatch == 0) 0 else 1) {
                    listener.accept().use { socket ->
                        socket.soTimeout = TimeUnit.SECONDS.toMillis(30).toInt()
                        val input = BufferedInputStream(socket.getInputStream())
                        val headers = StringBuilder()
                        while (true) {
                            val line = readHttpLine(input)
                            if (line.isEmpty()) break
                            headers.append(line).append('\n')
                        }
                        val contentLength = Regex("(?im)^content-length:\\s*(\\d+)")
                            .find(headers)?.groupValues?.get(1)?.toInt() ?: error("Missing Content-Length")
                        val requestBytes = ByteArray(contentLength)
                        var offset = 0
                        while (offset < contentLength) {
                            val count = input.read(requestBytes, offset, contentLength - offset)
                            check(count > 0) { "Unexpected end of request body" }
                            offset += count
                        }
                        val response = mockTranslationResponse(String(requestBytes, StandardCharsets.UTF_8))
                        if (requestItemCounts.isEmpty()) Thread.sleep(1_500)
                        requestItemCounts.add(response.second)
                        Log.i("BatchDeviceTest", "request=${requestItemCounts.size} pages=${requestItemCounts.sum()}")
                        val body = JSONObject().put("choices", JSONArray().put(
                            JSONObject().put("message", JSONObject().put("content", response.first))
                        )).toString().toByteArray(StandardCharsets.UTF_8)
                        BufferedOutputStream(socket.getOutputStream()).use { output ->
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            output.write(body)
                            output.flush()
                        }
                    }
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
                Log.e("BatchDeviceTest", "Mock server failed", failure)
            }
        }.apply { name = "device-translation-mock"; start() }

        try {
            Log.i("BatchDeviceTest", "Seeding $pageCount pages")
            seedSyntheticPages(context, container, images)
            Log.i("BatchDeviceTest", "Seed complete; starting service")
            settings.saveApiRetryCount(0)
            settings.saveMaxConcurrency(1)
            settings.saveTranslationBatchPages(requestBatch)
            settings.saveOcrApiSettings(OcrApiSettings(
                useLocalOcr = false,
                apiUrl = "http://127.0.0.1:${listener.localPort}/v1/chat/completions",
                apiKey = "device-test-key",
                modelName = "device-ocr",
                timeoutSeconds = 30
            ))
            settings.save(ApiSettings(
                apiUrl = "http://127.0.0.1:${listener.localPort}/v1/chat/completions",
                apiKey = "device-test-key",
                modelName = "device-translation",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE
            ))
            val task = FolderTranslationTask(
                folder = folder,
                images = images,
                force = false,
                fullTranslate = false,
                glossaryProcessingEnabled = false,
                useVlDirectTranslate = false,
                language = TranslationLanguage.JA_TO_ZH
            )
            TranslationKeepAliveService.startTranslationTask(
                context,
                TranslationTaskPersistence.fromFolder(task)
            )

            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            var state = com.manga.translate.platform.GlobalTaskProgressStore.state.value
            while (!state.running && !state.terminal && System.nanoTime() < deadline) {
                Thread.sleep(25)
                state = com.manga.translate.platform.GlobalTaskProgressStore.state.value
            }
            assertTrue("Foreground service never took ownership of the task", state.running)
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            )
            var lastReport = 0L
            while (!state.terminal && System.nanoTime() < deadline) {
                serverFailure.get()?.let { throw AssertionError("Mock translation server failed", it) }
                if (System.nanoTime() - lastReport > TimeUnit.SECONDS.toNanos(10)) {
                    Log.i("BatchDeviceTest", "progress=$state requests=${requestItemCounts.size}")
                    lastReport = System.nanoTime()
                }
                Thread.sleep(100)
                state = com.manga.translate.platform.GlobalTaskProgressStore.state.value
            }
            assertTrue("Batch task did not finish: ${state.detail}", state.terminal)
            serverFailure.get()?.let { throw AssertionError("Mock translation server failed", it) }
            assertFalse("Batch task ended with error: ${state.detail}", state.error)
            assertEquals("Expected all pages to be sent", pageCount, requestItemCounts.sum())
            assertTrue("Requests exceeded configured batch size: $requestItemCounts", requestItemCounts.all { it <= requestBatch })
            Log.i("BatchDeviceTest", "Terminal state=$state; validating $pageCount results")
            assertEquals(pageCount, images.count { container.translationStore.translationFileFor(it).isFile })
            images.forEachIndexed { index, image ->
                val result = container.translationStore.load(image)
                assertNotNull("Missing translation for page $index", result)
                assertEquals("translated:device_source_$index", result!!.bubbles.single().translatedText)
            }
        } finally {
            listener.close()
            serverThread.join(TimeUnit.SECONDS.toMillis(10))
            folder.deleteRecursively()
        }
    }

    private fun seedSyntheticPages(
        context: Context,
        container: AppContainer,
        images: List<File>
    ) {
        val bitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888)
        val ocrSettings = OcrApiSettings(
            useLocalOcr = false,
            apiUrl = "http://127.0.0.1/mock",
            apiKey = "device-test-key",
            modelName = "device-ocr",
            timeoutSeconds = 30
        )
        val cacheMode = "api|${buildDetectionStrategyTag(240, 320)}|bubbles_and_text"
        val promptFingerprint = container.customPromptStore.fingerprint(context, "prompts/ocr_prompts.json")
        val engine = "api:${ocrSettings.modelName}|masked-tiles-v1" +
            promptFingerprint.takeIf(String::isNotEmpty)?.let { "|prompt:$it" }.orEmpty()
        try {
            images.forEachIndexed { index, image ->
                image.parentFile?.mkdirs()
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                container.ocrStore.save(image, PageOcrResult(
                    imageFile = image,
                    width = 240,
                    height = 320,
                    bubbles = listOf(OcrBubble(
                        id = 0,
                        rect = RectF(20f, 20f, 220f, 300f),
                        text = "device_source_$index",
                        source = BubbleSource.BUBBLE_DETECTOR
                    )),
                    cacheMode = cacheMode,
                    metadata = OcrMetadata(
                        cacheMode = cacheMode,
                        language = TranslationLanguage.JA_TO_ZH.name,
                        engineModel = engine
                    )
                ))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun mockTranslationResponse(requestJson: String): Pair<String, Int> {
        val request = JSONObject(requestJson)
        val messages = request.getJSONArray("messages")
        val content = messages.getJSONObject(messages.length() - 1).getString("content")
        val payload = JSONObject(content.substring(content.indexOf('{')))
        val items = payload.getJSONArray("items")
        val output = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            output.put(JSONObject()
                .put("id", item.get("id"))
                .put("translation", "translated:${item.getString("text")}"))
        }
        return JSONObject().put("items", output).toString() to items.length()
    }

    private fun readHttpLine(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
        }
        return bytes.toString(StandardCharsets.US_ASCII.name())
    }
}
