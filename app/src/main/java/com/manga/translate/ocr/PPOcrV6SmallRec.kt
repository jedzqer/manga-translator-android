package com.manga.translate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.scale
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.recycleSafely
import com.manga.translate.settings.SettingsStore
import org.json.JSONArray
import kotlin.math.ceil

class PPOcrV6SmallRec(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) : OcrEngine, AutoCloseable {
    private val context = context.applicationContext
    private val charset = context.assets.open("models/ocr/characters.json").bufferedReader().use {
        val json = JSONArray(it.readText())
        List(json.length()) { index -> json.getString(index) }.also { chars ->
            require(chars.size == 18710 && chars.first() == "blank" && chars.last() == " ")
        }
    }
    private val models = mutableMapOf<Int, LiteRtOcrModel>()
    private var closed = false

    override fun recognize(bitmap: Bitmap): String = recognizeWithScore(bitmap).text

    @Synchronized
    override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?): OcrEngine.OcrEngineResult {
        check(!closed) { "OCR engine is closed" }
        return if (rect == null) recognizeLine(bitmap) else
            withBitmapCrop(bitmap, rect) { recognizeLine(it) } ?: OcrEngine.OcrEngineResult("", 0f)
    }

    private fun recognizeLine(bitmap: Bitmap): OcrEngine.OcrEngineResult {
        val size = resolveLiteRtOcrInputSize(bitmap.width, bitmap.height)
        val resized = bitmap.scale(size.contentWidth, 48)
        val input = try {
            val pixels = IntArray(size.contentWidth * 48)
            resized.getPixels(pixels, 0, size.contentWidth, 0, 0, size.contentWidth, 48)
            recognitionInput(pixels, size.contentWidth, size.width)
        } finally {
            if (resized !== bitmap) resized.recycleSafely()
        }
        val model = models.getOrPut(size.width) {
            LiteRtOcrModel(context, "models/ocr/ppocrv6_small_rec_${size.width}_fp32.tflite",
                listOf(1, 3, 48, size.width), listOf(1, size.width / 8, charset.size))
        }
        val decoded = decodeLiteRtCtc(model.run(input), charset)
        if (settingsStore.loadModelIoLogging()) {
            AppLogger.log("PPOcrV6SmallRec", "Input ${bitmap.width}x${bitmap.height}, model width=${size.width}, output: ${decoded.text}")
        }
        return decoded
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { models.values.forEach { it.close() } } finally { models.clear() }
    }
}

internal fun resolveLiteRtOcrInputSize(width: Int, height: Int): PaddleOcrInputSize {
    require(width > 0 && height > 0)
    val required = ceil(48.0 * width / height).toInt().coerceAtLeast(1)
    val bucket = when { required <= 320 -> 320; required <= 640 -> 640; else -> 960 }
    return PaddleOcrInputSize(48, bucket, required.coerceAtMost(bucket))
}

/** NCHW BGR normalized to [-1,1]; right padding stays zero in normalized space. */
internal fun recognitionInput(pixels: IntArray, contentWidth: Int, width: Int): FloatArray {
    require(contentWidth in 1..width && pixels.size == contentWidth * 48)
    val plane = 48 * width
    val result = FloatArray(plane * 3)
    for (y in 0 until 48) for (x in 0 until contentWidth) {
        val pixel = pixels[y * contentWidth + x]
        val index = y * width + x
        result[index] = ((pixel and 255) - 127.5f) / 127.5f
        result[index + plane] = (((pixel ushr 8) and 255) - 127.5f) / 127.5f
        result[index + 2 * plane] = (((pixel ushr 16) and 255) - 127.5f) / 127.5f
    }
    return result
}

internal fun decodeLiteRtCtc(probabilities: FloatArray, charset: List<String>): OcrEngine.OcrEngineResult {
    require(charset.isNotEmpty() && probabilities.size % charset.size == 0)
    val text = StringBuilder()
    var previous = -1
    var score = 0.0
    var count = 0
    for (offset in probabilities.indices step charset.size) {
        var best = 0
        for (index in charset.indices) {
            require(probabilities[offset + index].isFinite()) { "Non-finite OCR probability" }
            if (probabilities[offset + index] > probabilities[offset + best]) best = index
        }
        if (best != 0 && best != previous) {
            text.append(charset[best])
            score += probabilities[offset + best]
            count++
        }
        previous = best
    }
    return OcrEngine.OcrEngineResult(text.toString(), if (count == 0) 0f else (score / count).toFloat())
}
