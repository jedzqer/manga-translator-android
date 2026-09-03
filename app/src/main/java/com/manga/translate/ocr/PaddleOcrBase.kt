package com.manga.translate.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.manga.translate.detection.OnnxRuntimeSupport
import com.manga.translate.detection.OnnxThreadProfile
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.recycleSafely
import com.manga.translate.settings.SettingsStore
import java.nio.FloatBuffer

abstract class PaddleOcrBase(
    private val context: Context,
    private val modelAssetName: String,
    private val logTag: String,
    private val threadProfile: OnnxThreadProfile = OnnxThreadProfile.LIGHT,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val dictAssetName: String? = null,
    // No default: every subclass must state its XNNPACK choice explicitly.
    private val useXnnpack: Boolean
) : OcrEngine {
    private val env = OnnxRuntimeSupport.environment()
    private val session: OrtSession = createSession(modelAssetName)
    private val charset: List<String> = readCharset()
    private val inputName: String = session.inputInfo.keys.first()

    override fun recognize(bitmap: Bitmap): String {
        return recognizeWithScore(bitmap).text
    }

    @Synchronized
    override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?): OcrEngine.OcrEngineResult {
        val preprocessed = if (rect != null) preprocess(bitmap, rect) else preprocess(bitmap)
        return preprocessed.use { tensor ->
            try {
                session.run(mapOf(inputName to tensor)).use { outputs ->
                    val output = outputs[0]
                    val outputShape = (output.info as TensorInfo).shape
                    val decoded = ctcDecodeWithScore(output.value, outputShape)
                    if (settingsStore.loadModelIoLogging()) {
                        val dims = if (rect != null) {
                            "rect (${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()})"
                        } else {
                            "${bitmap.width}x${bitmap.height}"
                        }
                        AppLogger.log(logTag, "Input $dims, output: ${decoded.text}")
                    }
                    decoded
                }
            } catch (e: OrtException) {
                AppLogger.log(logTag, "ONNX inference failed", e)
                OcrEngine.OcrEngineResult("", 0f)
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val imgH = 48
        val imgW = 320

        val h = bitmap.height
        val w = bitmap.width
        val ratio = w.toFloat() / h.toFloat()
        var targetW = (imgH * ratio).toInt()
        targetW = targetW.coerceAtMost(imgW)

        val resized = bitmap.scale(targetW, imgH)
        return try {
            val input = FloatArray(3 * imgH * imgW)
            for (y in 0 until imgH) {
                for (x in 0 until targetW) {
                    val pixel = resized[x, y]
                    val r = ((pixel shr 16) and 0xFF) / 255f
                    val g = ((pixel shr 8) and 0xFF) / 255f
                    val b = (pixel and 0xFF) / 255f
                    val bNorm = (b - 0.5f) / 0.5f
                    val gNorm = (g - 0.5f) / 0.5f
                    val rNorm = (r - 0.5f) / 0.5f
                    val base = y * imgW + x
                    input[base] = bNorm
                    input[base + imgH * imgW] = gNorm
                    input[base + 2 * imgH * imgW] = rNorm
                }
            }

            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, imgH.toLong(), imgW.toLong())
            )
        } finally {
            if (resized !== bitmap) {
                resized.recycleSafely()
            }
        }
    }

    private fun preprocess(source: Bitmap, rect: RectF): OnnxTensor {
        val imgH = 48
        val imgW = 320
        val clamped = PipelineBitmapDecoder.clampRect(rect, source.width, source.height)
            ?: return preprocess(source)
        val cropWidth = clamped.width().coerceAtLeast(1f)
        val cropHeight = clamped.height().coerceAtLeast(1f)
        val ratio = cropWidth / cropHeight
        val targetW = (imgH * ratio).toInt().coerceIn(1, imgW)

        val input = FloatArray(3 * imgH * imgW)
        for (y in 0 until imgH) {
            val srcY = mapToSourceCoordinate(y, imgH, clamped.top, cropHeight, source.height)
            for (x in 0 until targetW) {
                val srcX = mapToSourceCoordinate(x, targetW, clamped.left, cropWidth, source.width)
                val pixel = source[srcX, srcY]
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val bNorm = (b - 0.5f) / 0.5f
                val gNorm = (g - 0.5f) / 0.5f
                val rNorm = (r - 0.5f) / 0.5f
                val base = y * imgW + x
                input[base] = bNorm
                input[base + imgH * imgW] = gNorm
                input[base + 2 * imgH * imgW] = rNorm
            }
        }

        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, imgH.toLong(), imgW.toLong())
        )
    }

    private fun mapToSourceCoordinate(
        targetIndex: Int,
        targetSize: Int,
        sourceStart: Float,
        sourceSize: Float,
        sourceLimit: Int
    ): Int {
        val normalized = (targetIndex + 0.5f) / targetSize.toFloat()
        val source = sourceStart + normalized * sourceSize
        return source.toInt().coerceIn(0, sourceLimit - 1)
    }

    protected open fun ctcDecodeWithScore(raw: Any, shape: LongArray): OcrEngine.OcrEngineResult {
        val batch = raw as? Array<*> ?: return OcrEngine.OcrEngineResult("", 0f)
        val first = batch.firstOrNull() as? Array<*> ?: return OcrEngine.OcrEngineResult("", 0f)
        val firstVec = first.firstOrNull()
        if (firstVec !is FloatArray) return OcrEngine.OcrEngineResult("", 0f)

        val tokens = ArrayList<OcrToken>()
        var prevIdx = -1

        fun appendIndex(maxIdx: Int, maxProb: Float) {
            if (maxIdx == prevIdx) {
                prevIdx = maxIdx
                return
            }
            prevIdx = maxIdx

            if (maxIdx == 0) {
                return
            }

            if (maxIdx < charset.size) {
                tokens.add(OcrToken(charset[maxIdx], maxProb))
            }
        }

        fun argmax(probs: FloatArray): Int {
            var maxIdx = 0
            var maxProb = probs[0]
            for (i in 1 until probs.size) {
                if (probs[i] > maxProb) {
                    maxProb = probs[i]
                    maxIdx = i
                }
            }
            return maxIdx
        }

        fun maxProb(probs: FloatArray, maxIdx: Int): Float {
            return if (maxIdx in probs.indices) probs[maxIdx] else 0f
        }

        val dim0 = batch.size
        val dim1 = first.size
        val dim2 = firstVec.size
        val looksLikeClasses: (Int) -> Boolean = { value ->
            value == charset.size || value == charset.size - 1 || value == charset.size + 1
        }

        when {
            dim1 == 1 && dim0 > 1 -> {
                for (t in 0 until dim0) {
                    val inner = batch[t] as? Array<*> ?: continue
                    val probs = inner.firstOrNull() as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            dim0 == 1 && looksLikeClasses(dim2) -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            dim0 == 1 && looksLikeClasses(dim1) -> {
                val classArrays = first.mapNotNull { it as? FloatArray }
                val timeCount = classArrays.firstOrNull()?.size ?: 0
                for (t in 0 until timeCount) {
                    var maxIdx = 0
                    var maxProb = Float.NEGATIVE_INFINITY
                    for (c in classArrays.indices) {
                        val arr = classArrays[c]
                        if (t >= arr.size) continue
                        val v = arr[t]
                        if (v > maxProb) {
                            maxProb = v
                            maxIdx = c
                        }
                    }
                    appendIndex(maxIdx, maxProb)
                }
            }
            shape.size == 3 && shape[0] == 1L -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
            else -> {
                for (step in first) {
                    val probs = step as? FloatArray ?: continue
                    val maxIdx = argmax(probs)
                    appendIndex(maxIdx, maxProb(probs, maxIdx))
                }
            }
        }

        val trimmedTokens = trimLowConfidenceTail(tokens)
        val score = if (trimmedTokens.isEmpty()) {
            0f
        } else {
            trimmedTokens.sumOf { it.score.toDouble() }.toFloat() / trimmedTokens.size
        }
        return OcrEngine.OcrEngineResult(
            text = trimmedTokens.joinToString(separator = "") { it.text },
            score = score
        )
    }

    protected open fun trimLowConfidenceTail(tokens: List<OcrToken>): List<OcrToken> = tokens

    protected open fun shouldTrimTailToken(token: OcrToken): Boolean = false

    protected open fun isSuspiciousTailChar(char: Char): Boolean = false

    private fun readCharset(): List<String> {
        try {
            val meta = session.metadata
            val customMetadata = meta.customMetadata
            val charString = customMetadata["character"]
            if (charString != null) {
                val charList = buildCharset(charString.lines())
                AppLogger.log(logTag, "Loaded ${charList.size} characters from model metadata")
                return charList
            }
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to read charset from session metadata", e)
        }
        readDictAsset()?.let { charList ->
            AppLogger.log(logTag, "Loaded ${charList.size} characters from dict asset $dictAssetName")
            return charList
        }
        readCharsetFromAssetMetadata()?.let { charList ->
            AppLogger.log(logTag, "Loaded ${charList.size} characters from asset metadata fallback")
            return charList
        }
        AppLogger.log(logTag, "Falling back to built-in charset")
        return getDefaultCharset()
    }

    private fun readDictAsset(): List<String>? {
        val name = dictAssetName ?: return null
        return runCatching {
            context.assets.open(name).bufferedReader(Charsets.UTF_8).useLines { lines ->
                val raw = lines.map { it }.toList()
                if (raw.isEmpty()) return@useLines null
                buildCharset(raw)
            }
        }.getOrNull()
    }

    protected abstract fun getDefaultCharset(): List<String>

    private fun readCharsetFromAssetMetadata(): List<String>? {
        return runCatching {
            context.assets.open(modelAssetName).use { input ->
                val text = input.readBytes().toString(Charsets.UTF_8)
                val markerIndex = text.indexOf("character")
                if (markerIndex < 0) return@runCatching null
                val rawLines = text.substring(markerIndex + "character".length).split('\n')
                val chars = ArrayList<String>()
                for ((index, line) in rawLines.withIndex()) {
                    val normalized = when (index) {
                        0 -> line.lastOrNull { it.code >= 0x20 }?.toString()
                        else -> line
                    } ?: continue
                    if (normalized.length != 1) {
                        if (chars.isNotEmpty()) break
                        continue
                    }
                    chars += normalized
                }
                if (chars.isEmpty()) return@runCatching null
                buildCharset(chars)
            }
        }.getOrNull()
    }

    private fun buildCharset(lines: List<String>): List<String> {
        val charList = lines.filter { it.isNotEmpty() }.toMutableList()
        charList.add(0, "blank")
        if (charList.lastOrNull() != " ") {
            charList.add(" ")
        }
        return charList
    }

    private fun createSession(assetName: String): OrtSession {
        return OnnxRuntimeSupport.getOrCreateSession(
            cacheDir = context.cacheDir,
            assetProvider = context.assets::open,
            assetName = assetName,
            threadProfile = threadProfile,
            useXnnpack = useXnnpack
        )
    }

    protected data class OcrToken(
        val text: String,
        val score: Float
    )
}
