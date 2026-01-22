package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class EnglishLineDetector(
    private val context: Context,
    private val modelAssetName: String = "Multilingual_PP-OCRv3_det_infer.onnx"
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession()
    private val inputName: String
    private val inputWidth: Int
    private val inputHeight: Int
    private val settingsStore = SettingsStore(context.applicationContext)

    init {
        val input = session.inputInfo.entries.first()
        inputName = input.key
        val shape = (input.value.info as TensorInfo).shape
        inputHeight = (shape.getOrNull(2) ?: 960L).toInt().coerceAtLeast(1)
        inputWidth = (shape.getOrNull(3) ?: 960L).toInt().coerceAtLeast(1)
    }

    fun detectLines(bitmap: Bitmap): List<RectF> {
        val preprocessed = preprocess(bitmap)
        val tensor = preprocessed.tensor
        tensor.use {
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0]
                val outputShape = (output.info as TensorInfo).shape
                val probMap = extractProbMap(output.value, outputShape) ?: return emptyList()
                val rects = extractLineRects(probMap, preprocessed)
                val sorted = sortBoxesReadingOrder(rects)
                if (settingsStore.loadModelIoLogging()) {
                    AppLogger.log(
                        "EnglishLineDetector",
                        "Input ${bitmap.width}x${bitmap.height}, lines ${sorted.size}: ${describeRects(sorted)}"
                    )
                }
                return sorted
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

    private fun extractLineRects(prob: FloatArray, pre: PreprocessResult): List<RectF> {
        val width = pre.outputWidth
        val height = pre.outputHeight
        if (width <= 0 || height <= 0) return emptyList()
        val total = width * height
        if (prob.size < total) return emptyList()
        val visited = BooleanArray(total)
        val stack = IntArray(total)
        val results = ArrayList<RectF>()

        for (i in 0 until total) {
            if (visited[i] || prob[i] <= PROB_THRESHOLD) continue
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var count = 0
            var sum = 0f
            var sp = 0
            stack[sp++] = i
            visited[i] = true

            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % width
                val y = idx / width
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                count++
                sum += prob[idx]

                for (ny in y - 1..y + 1) {
                    if (ny < 0 || ny >= height) continue
                    val rowOffset = ny * width
                    for (nx in x - 1..x + 1) {
                        if (nx < 0 || nx >= width) continue
                        val nidx = rowOffset + nx
                        if (!visited[nidx] && prob[nidx] > PROB_THRESHOLD) {
                            visited[nidx] = true
                            stack[sp++] = nidx
                        }
                    }
                }
            }

            if (count < MIN_COMPONENT_PIXELS) continue
            val score = sum / count
            if (score < BOX_THRESHOLD) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            if (boxW < MIN_SIZE || boxH < MIN_SIZE) continue

            val distance = (boxW * boxH) / (2f * (boxW + boxH)) * UNCLIP_RATIO
            val left = (minX - distance).coerceIn(0f, width.toFloat())
            val top = (minY - distance).coerceIn(0f, height.toFloat())
            val right = (maxX + 1 + distance).coerceIn(0f, width.toFloat())
            val bottom = (maxY + 1 + distance).coerceIn(0f, height.toFloat())

            val leftOrig = ((left - pre.padW) / pre.ratioW).coerceIn(0f, pre.originalWidth.toFloat())
            val topOrig = ((top - pre.padH) / pre.ratioH).coerceIn(0f, pre.originalHeight.toFloat())
            val rightOrig = ((right - pre.padW) / pre.ratioW).coerceIn(0f, pre.originalWidth.toFloat())
            val bottomOrig = ((bottom - pre.padH) / pre.ratioH).coerceIn(0f, pre.originalHeight.toFloat())
            if (rightOrig - leftOrig <= MIN_ORIGINAL_SIZE || bottomOrig - topOrig <= MIN_ORIGINAL_SIZE) {
                continue
            }
            results.add(RectF(leftOrig, topOrig, rightOrig, bottomOrig))
        }
        return results
    }

    private fun sortBoxesReadingOrder(rects: List<RectF>): List<RectF> {
        if (rects.isEmpty()) return emptyList()
        val yCoords = rects.map { it.top }
        val indices = yCoords.indices.sortedWith(compareBy({ yCoords[it] }, { it }))
        val ySorted = indices.map { yCoords[it] }
        val lineIds = IntArray(indices.size)
        for (i in 1 until indices.size) {
            val dy = ySorted[i] - ySorted[i - 1]
            lineIds[i] = lineIds[i - 1] + if (dy >= BOX_SORT_Y_THRESHOLD) 1 else 0
        }
        return indices
            .withIndex()
            .sortedWith(compareBy({ lineIds[it.index] }, { rects[it.value].left }))
            .map { rects[it.value] }
    }

    private fun describeRects(rects: List<RectF>, limit: Int = 3): String {
        if (rects.isEmpty()) return "[]"
        val preview = rects.take(limit).joinToString(prefix = "[", postfix = "]") { rect ->
            "(${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()})"
        }
        return if (rects.size > limit) "$preview..." else preview
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
        private const val PROB_THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.5f
        private const val UNCLIP_RATIO = 1.6f
        private const val MIN_COMPONENT_PIXELS = 3
        private const val MIN_SIZE = 3
        private const val MIN_ORIGINAL_SIZE = 3f
        private const val BOX_SORT_Y_THRESHOLD = 10f
    }
}
