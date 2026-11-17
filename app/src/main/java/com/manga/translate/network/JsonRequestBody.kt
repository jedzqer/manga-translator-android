package com.manga.translate.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/** Borrows the caller's encoded image until the request body is prepared, without copying a data URI. */
internal class EncodedImageData(val base64: String, val dataUri: Boolean = false) {
    val prefix: String get() = if (dataUri) "data:image/jpeg;base64," else ""

    // Keep JSONObject inspection/serialization compatible with ordinary string fields.
    override fun toString(): String = prefix + base64
}

/**
 * One immutable, request-scoped serialization, shared by every HTTP attempt. Okio retains segmented
 * bytes, so preparing an image request needs neither a whole JSON String nor a contiguous byte array.
 * Nothing is consumed by writeTo; cancellation can safely race a write or a subsequent attempt.
 */
internal class JsonRequestBody private constructor(private val bytes: ByteString) : RequestBody() {
    override fun contentType() = JSON_MEDIA_TYPE
    override fun contentLength(): Long = bytes.size.toLong()
    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val IMAGE_CHUNK_CHARS = 8192

        fun prepare(payload: JSONObject): JsonRequestBody {
            val buffer = Buffer()
            writeJson(buffer, payload)
            return JsonRequestBody(buffer.readByteString())
        }

        private fun writeJson(buffer: Buffer, value: Any?) {
            when (value) {
                null, JSONObject.NULL -> buffer.writeUtf8("null")
                is EncodedImageData -> {
                    buffer.writeByte('"'.code)
                    writeStringContents(buffer, value.prefix)
                    writeStringContents(buffer, value.base64)
                    buffer.writeByte('"'.code)
                }
                is JSONObject -> {
                    buffer.writeByte('{'.code)
                    val keys = value.keys()
                    var first = true
                    while (keys.hasNext()) {
                        if (!first) buffer.writeByte(','.code)
                        first = false
                        val key = keys.next()
                        buffer.writeUtf8(JSONObject.quote(key)).writeByte(':'.code)
                        writeJson(buffer, value.get(key))
                    }
                    buffer.writeByte('}'.code)
                }
                is JSONArray -> {
                    buffer.writeByte('['.code)
                    for (index in 0 until value.length()) {
                        if (index > 0) buffer.writeByte(','.code)
                        writeJson(buffer, value.get(index))
                    }
                    buffer.writeByte(']'.code)
                }
                is Boolean -> buffer.writeUtf8(value.toString())
                is Number -> buffer.writeUtf8(JSONObject.numberToString(value))
                else -> buffer.writeUtf8(JSONObject.quote(value.toString()))
            }
        }

        /** Bound temporary JSON escaping to one chunk, even for a very large encoded image. */
        private fun writeStringContents(buffer: Buffer, text: String) {
            var start = 0
            while (start < text.length) {
                var end = minOf(start + IMAGE_CHUNK_CHARS, text.length)
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
                    end--
                }
                val quoted = JSONObject.quote(text.substring(start, end))
                buffer.writeUtf8(quoted, 1, quoted.length - 1)
                start = end
            }
        }
    }
}
