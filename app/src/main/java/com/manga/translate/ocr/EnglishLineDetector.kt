package com.manga.translate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import kotlin.math.min

class PaddleTextLineDetector(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) : AutoCloseable {
    private val inputWidth = 640
    private val inputHeight = 640
    private val model = LiteRtOcrModel(
        context,
        "models/detection/ppocrv6_small_det_640_fp32.tflite",
        listOf(1, 3, 640, 640),
        listOf(1, 1, 640, 640)
    )

    @Synchronized
    override fun close() = model.close()

    @Synchronized
    fun detectLines(bitmap: Bitmap): List<RectF> {
        return detectLines(bitmap, throwOnFailure = false)
    }

    @Synchronized
    fun detectLinesOrThrow(bitmap: Bitmap): List<RectF> {
        return detectLines(bitmap, throwOnFailure = true)
    }

    private fun detectLines(bitmap: Bitmap, throwOnFailure: Boolean): List<RectF> {
        if (bitmap.width < MIN_CROP_SIZE || bitmap.height < MIN_CROP_SIZE) {
            return emptyList()
        }
        val preprocessed = preprocess(bitmap)
        try {
            val detections = extractLineRects(model.run(preprocessed.tensor), preprocessed)
            val sorted = sortBoxesReadingOrder(detections)
            if (settingsStore.loadModelIoLogging()) {
                AppLogger.log("PaddleTextLineDetector", "Input ${bitmap.width}x${bitmap.height}, lines ${sorted.size}: ${describeRects(sorted)}")
            }
            return sorted
        } catch (e: Exception) {
            if (throwOnFailure) throw e
            AppLogger.log("PaddleTextLineDetector", "Line detection failed", e)
            return emptyList()
        }
    }

    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = min(inputWidth.toFloat() / srcW, inputHeight.toFloat() / srcH).coerceAtLeast(1e-6f)
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        val resized = bitmap.scale(newW, newH)
        val padded = createBitmap(inputWidth, inputHeight)
        val padX = ((inputWidth - newW) / 2f).coerceAtLeast(0f)
        val padY = ((inputHeight - newH) / 2f).coerceAtLeast(0f)
        val input = FloatArray(3 * inputWidth * inputHeight)
        try {
            val canvas = Canvas(padded)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(resized, padX, padY, null)

            var offset = 0
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val pixel = padded[x, y]
                    val r = ((pixel shr 16) and 0xFF).toFloat()
                    val g = ((pixel shr 8) and 0xFF).toFloat()
                    val b = (pixel and 0xFF).toFloat()
                    input[offset] = (b - MEAN[0] * 255f) / (STD[0] * 255f)
                    input[offset + inputWidth * inputHeight] = (g - MEAN[1] * 255f) / (STD[1] * 255f)
                    input[offset + 2 * inputWidth * inputHeight] = (r - MEAN[2] * 255f) / (STD[2] * 255f)
                    offset++
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
            padded.recycle()
        }
        return PreprocessResult(
            tensor = input,
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

    private data class PreprocessResult(
        val tensor: FloatArray,
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
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val PROB_THRESHOLD = 0.2f
        private const val BOX_THRESHOLD = 0.45f
        private const val UNCLIP_RATIO = 1.4f
        private const val MIN_COMPONENT_PIXELS = 3
        private const val MIN_SIZE = 3
        private const val MIN_ORIGINAL_SIZE = 3f
        private const val BOX_SORT_Y_THRESHOLD = 10f
        private const val MIN_CROP_SIZE = 32
    }
}

typealias EnglishLineDetector = PaddleTextLineDetector
