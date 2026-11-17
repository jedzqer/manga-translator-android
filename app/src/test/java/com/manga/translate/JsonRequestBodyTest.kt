package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.ApiFormat
import com.manga.translate.network.EncodedImageData
import com.manga.translate.network.JsonRequestBody
import com.manga.translate.network.PayloadBuilder
import com.manga.translate.settings.SettingsStore
import java.io.IOException
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JsonRequestBodyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsStore(context)
    private val builder = PayloadBuilder(context, settings)

    @Test
    fun `image protocols match legacy JSON byte for byte and redact before serialization`() {
        for (format in ApiFormat.entries) {
            val payload = builder.buildImageTranslationPayload(settings.load().copy(apiFormat = format),
                "vision", "first+/=", "prompts/vl_page_prompts.json", format,
                glossary = mapOf("名" to "Name"), glossaryProcessingEnabled = true,
                requestedIds = listOf(0, 1), imagesBase64 = listOf("first+/=", "second+/="))
            assertReplayMatchesJson(payload)
            val log = builder.sanitizeModelIoForLog(payload)
            assertFalse(log.contains("first+/="))
            assertFalse(log.contains("second+/="))
            assertTrue(log.contains("<base64 omitted>"))
            assertTrue(log.contains("Name"))
        }
    }

    @Test
    fun `OCR body uses the same repeatable serialization`() {
        val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val payload = builder.buildImageOcrPayload(settings.loadOcrApiSettings(), image, listOf(2, 5))
            assertReplayMatchesJson(payload)
            val log = builder.sanitizeModelIoForLog(payload)
            assertTrue(log.contains("<base64 omitted>"))
            assertTrue(log.length < payload.toString().length)
        } finally {
            image.recycle()
        }
    }

    @Test
    fun `nested custom values and chunk boundaries preserve JSON escaping and UTF8 length`() {
        val text = "x".repeat(8191) + "😀/\"\\\n\t\b\r\u0000\u2028" + "y".repeat(8190) + "終"
        val payload = JSONObject().put("key/\"中文", JSONArray()
            .put(JSONObject.NULL).put(true).put(false).put(42).put(-0.0).put(1.25)
            .put(text).put(EncodedImageData(text, dataUri = true))
            .put(JSONObject().put("image", EncodedImageData(text))))
        assertReplayMatchesJson(payload)
    }

    @Test
    fun `preparation freezes mutable payload and replay does not serialize again`() {
        var serializations = 0
        val value = object {
            override fun toString(): String { serializations++; return "original" }
        }
        val payload = JSONObject().put("value", value)
        val body = JsonRequestBody.prepare(payload)
        assertEquals(1, serializations)
        payload.put("value", "changed")
        repeat(3) {
            assertEquals("original", JSONObject(Buffer().also(body::writeTo).readUtf8()).getString("value"))
        }
        assertEquals(1, serializations)
        assertFalse(body.isOneShot())
    }

    @Test
    fun `interrupted upload does not consume bytes needed by the next attempt`() {
        val payload = JSONObject().put("image", EncodedImageData("a+/=".repeat(20_000), true))
        val body = JsonRequestBody.prepare(payload)
        val broken = object : Sink {
            override fun write(source: Buffer, byteCount: Long) {
                source.skip(minOf(byteCount, 8192))
                throw IOException("upload interrupted")
            }
            override fun timeout() = Timeout.NONE
            override fun flush() = Unit
            override fun close() = Unit
        }.buffer()
        try {
            body.writeTo(broken)
            fail("Expected interrupted upload")
        } catch (expected: IOException) {
            assertEquals("upload interrupted", expected.message)
        }
        assertEquals(payload.toString(), Buffer().also(body::writeTo).readUtf8())
    }

    private fun assertReplayMatchesJson(payload: JSONObject) {
        val expected = payload.toString()
        val body = JsonRequestBody.prepare(payload)
        assertEquals("application/json; charset=utf-8", body.contentType().toString())
        repeat(2) {
            val buffer = Buffer().also(body::writeTo)
            assertEquals(body.contentLength(), buffer.size)
            assertEquals(expected, buffer.readUtf8())
        }
    }
}
