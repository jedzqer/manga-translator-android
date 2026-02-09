package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.min

class TextMaskDetector(
    private val context: Context,
    private val modelAssetName: String = "Multilingual_PP-OCRv3_det_infer.onnx"
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession()
    private val inputName: String
    private val inputWidth: Int
    private val inputHeight: Int

    init {
        val input = session.inputInfo.entries.first()
        inputName = input.key
        val shape = (input.value.info as TensorInfo).shape
        inputHeight = (shape.getOrNull(2) ?: 960L).toInt().coerceAtLeast(1)
        inputWidth = (shape.getOrNull(3) ?: 960L).toInt().coerceAtLeast(1)
    }

    fun detectMask(bitmap: Bitmap): BooleanArray {
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            return BooleanArray(bitmap.width * bitmap.height)
        }
        val preprocessed = preprocess(bitmap)
        preprocessed.tensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0]
                val outputShape = (output.info as TensorInfo).shape
                val probMap = extractProbMap(output.value, outputShape)
                    ?: return BooleanArray(bitmap.width * bitmap.height)
                val rawMask = buildMask(probMap, preprocessed.outputWidth, preprocessed.outputHeight, PROB_THRESHOLD)
                val dilated = dilateMask(rawMask, preprocessed.outputWidth, preprocessed.outputHeight, DETECTOR_DILATE_ITERATIONS)
                return mapMaskToOriginal(dilated, preprocessed)
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = min(inputWidth.toFloat() / srcW, inputHeight.toFloat() / srcH).coerceAtLeast(1e-6f)
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val padded = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.BLACK)
        val padX = ((inputWidth - newW) / 2f).coerceAtLeast(0f)
        val padY = ((inputHeight - newH) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(resized, padX, padY, null)

        val input = FloatArray(3 * inputWidth * inputHeight)
        var offset = 0
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = padded.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                input[offset] = (b - MEAN[0]) / STD[0]
                input[offset + inputWidth * inputHeight] = (g - MEAN[1]) / STD[1]
                input[offset + 2 * inputWidth * inputHeight] = (r - MEAN[2]) / STD[2]
                offset++
            }
        }
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        )
        return PreprocessResult(
            tensor = tensor,
            outputWidth = inputWidth,
            outputHeight = inputHeight,
            ratioW = newW / srcW.toFloat(),
            ratioH = newH / srcH.toFloat(),
            padW = padX,
            padH = padY,
            originalWidth = srcW,
            originalHeight = srcH
        )
    }

    private fun extractProbMap(raw: Any, shape: LongArray): FloatArray? {
        val h: Int
        val w: Int
        val rows: Array<*>
        when (shape.size) {
            4 -> {
                h = (shape.getOrNull(2) ?: 0L).toInt()
                w = (shape.getOrNull(3) ?: 0L).toInt()
                val batch = raw as? Array<*> ?: return null
                val channel = batch.firstOrNull() as? Array<*> ?: return null
                val rowBlock = channel.firstOrNull() as? Array<*> ?: return null
                rows = rowBlock
            }
            3 -> {
                h = (shape.getOrNull(1) ?: 0L).toInt()
                w = (shape.getOrNull(2) ?: 0L).toInt()
                val batch = raw as? Array<*> ?: return null
                val rowBlock = batch.firstOrNull() as? Array<*> ?: return null
                rows = rowBlock
            }
            else -> return null
        }
        if (h <= 0 || w <= 0 || rows.size < h) return null
        val prob = FloatArray(h * w)
        for (y in 0 until h) {
            val row = rows[y] as? FloatArray ?: return null
            if (row.size < w) return null
            System.arraycopy(row, 0, prob, y * w, w)
        }
        return prob
    }

    private fun buildMask(prob: FloatArray, width: Int, height: Int, threshold: Float): BooleanArray {
        val total = width * height
        val mask = BooleanArray(total)
        for (i in 0 until total) {
            mask[i] = prob[i] > threshold
        }
        return mask
    }

    private fun dilateMask(mask: BooleanArray, width: Int, height: Int, iterations: Int): BooleanArray {
        var current = mask
        repeat(iterations.coerceAtLeast(1)) {
            val out = current.clone()
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    if (!current[rowOffset + x]) continue
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val nOffset = ny * width
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx !in 0 until width) continue
                            out[nOffset + nx] = true
                        }
                    }
                }
            }
            current = out
        }
        return current
    }

    private fun mapMaskToOriginal(mask: BooleanArray, pre: PreprocessResult): BooleanArray {
        val original = BooleanArray(pre.originalWidth * pre.originalHeight)
        if (pre.originalWidth <= 0 || pre.originalHeight <= 0) return original
        val outputWidth = pre.outputWidth
        val outputHeight = pre.outputHeight
        if (mask.size < outputWidth * outputHeight) return original

        for (y in 0 until pre.originalHeight) {
            val py = y * pre.ratioH + pre.padH
            val iy = py.toInt().coerceIn(0, outputHeight - 1)
            val rowOut = y * pre.originalWidth
            val rowMask = iy * outputWidth
            for (x in 0 until pre.originalWidth) {
                val px = x * pre.ratioW + pre.padW
                val ix = px.toInt().coerceIn(0, outputWidth - 1)
                original[rowOut + x] = mask[rowMask + ix]
            }
        }
        return original
    }

    private fun createSession(): OrtSession {
        val modelFile = File(context.cacheDir, modelAssetName)
        if (!modelFile.exists()) {
            context.assets.open(modelAssetName).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    private data class PreprocessResult(
        val tensor: OnnxTensor,
        val outputWidth: Int,
        val outputHeight: Int,
        val ratioW: Float,
        val ratioH: Float,
        val padW: Float,
        val padH: Float,
        val originalWidth: Int,
        val originalHeight: Int
    )

    companion object {
        private val MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        private const val PROB_THRESHOLD = 0.22f
        private const val DETECTOR_DILATE_ITERATIONS = 2
    }
}
