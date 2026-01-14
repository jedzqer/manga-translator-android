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

class TextDetector(
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

    fun detect(bitmap: Bitmap): List<RectF> {
        val preprocessed = preprocess(bitmap)
        val tensor = preprocessed.tensor
        tensor.use {
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0]
                val outputShape = (output.info as TensorInfo).shape
                val probMap = extractProbMap(output.value, outputShape) ?: return emptyList()
                val aabbs = extractAabbs(
                    probMap,
                    preprocessed.outputWidth,
                    preprocessed.outputHeight,
                    PROB_THRESHOLD,
                    MIN_COMPONENT_PIXELS
                )
                val scaled = aabbs.mapNotNull { toOriginal(it, preprocessed) }
                val merged = mergeAabbsAggressive(
                    scaled,
                    MERGE_X_GAP_RATIO,
                    MERGE_Y_OVERLAP_RATIO,
                    MERGE_H_RATIO,
                    MERGE_CONTAIN_RATIO,
                    MERGE_MIN_SIZE,
                    MERGE_MIN_AREA,
                    MERGE_EXPAND_RATIO
                )
                return merged.map { expandAabb(it, OUTPUT_EXPAND_RATIO, OUTPUT_EXPAND_MIN, bitmap) }
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
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(resized, 0f, 0f, null)

        val input = FloatArray(3 * inputWidth * inputHeight)
        var offset = 0
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = padded.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                input[offset] = (r - MEAN[0]) / STD[0]
                input[offset + inputWidth * inputHeight] = (g - MEAN[1]) / STD[1]
                input[offset + 2 * inputWidth * inputHeight] = (b - MEAN[2]) / STD[2]
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
            scale = scale,
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

    private fun extractAabbs(
        prob: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        minPixels: Int
    ): List<Aabb> {
        if (width <= 0 || height <= 0) return emptyList()
        val total = width * height
        if (prob.size < total) return emptyList()
        val visited = BooleanArray(total)
        val stack = IntArray(total)
        val results = ArrayList<Aabb>()

        for (i in 0 until total) {
            if (visited[i] || prob[i] <= threshold) continue
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var count = 0
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

                for (ny in y - 1..y + 1) {
                    if (ny < 0 || ny >= height) continue
                    val rowOffset = ny * width
                    for (nx in x - 1..x + 1) {
                        if (nx < 0 || nx >= width) continue
                        val nidx = rowOffset + nx
                        if (!visited[nidx] && prob[nidx] > threshold) {
                            visited[nidx] = true
                            stack[sp++] = nidx
                        }
                    }
                }
            }

            if (count >= minPixels) {
                results.add(Aabb(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat()))
            }
        }
        return results
    }

    private fun toOriginal(aabb: Aabb, pre: PreprocessResult): Aabb? {
        val scale = pre.scale
        if (scale <= 0f) return null
        val left = (aabb.left / scale).coerceIn(0f, pre.originalWidth.toFloat())
        val top = (aabb.top / scale).coerceIn(0f, pre.originalHeight.toFloat())
        val right = (aabb.right / scale).coerceIn(0f, pre.originalWidth.toFloat())
        val bottom = (aabb.bottom / scale).coerceIn(0f, pre.originalHeight.toFloat())
        if (right - left <= 1f || bottom - top <= 1f) return null
        return Aabb(left, top, right, bottom)
    }

    private fun mergeAabbsAggressive(
        aabbs: List<Aabb>,
        xGapRatio: Float,
        yOverlapRatio: Float,
        hRatio: Float,
        containRatio: Float,
        minSize: Float,
        minArea: Float,
        expandRatio: Float
    ): List<Aabb> {
        val filtered = aabbs.filter {
            val w = it.right - it.left
            val h = it.bottom - it.top
            w >= minSize && h >= minSize && w * h >= minArea
        }
        val n = filtered.size
        if (n == 0) return emptyList()

        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) {
                parent[rb] = ra
            }
        }

        fun area(a: Aabb): Float {
            return max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        }

        fun containScore(a: Aabb, b: Aabb): Float {
            val ix1 = max(a.left, b.left)
            val iy1 = max(a.top, b.top)
            val ix2 = min(a.right, b.right)
            val iy2 = min(a.bottom, b.bottom)
            val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
            if (inter <= 0f) return 0f
            return inter / min(area(a), area(b))
        }

        fun expand(box: Aabb): Aabb {
            val h = max(1f, box.bottom - box.top)
            val pad = expandRatio * h
            return Aabb(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        }

        fun overlap(a: Aabb, b: Aabb): Boolean {
            return a.left <= b.right && a.right >= b.left && a.top <= b.bottom && a.bottom >= b.top
        }

        val expanded = filtered.map { expand(it) }
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until n) {
                val a = filtered[i]
                val ha = a.bottom - a.top
                if (ha <= 0f) continue
                for (j in i + 1 until n) {
                    val b = filtered[j]
                    val hb = b.bottom - b.top
                    if (hb <= 0f) continue
                    if (containScore(a, b) >= containRatio) {
                        if (find(i) != find(j)) {
                            union(i, j)
                            changed = true
                        }
                        continue
                    }
                    if (overlap(expanded[i], expanded[j])) {
                        if (find(i) != find(j)) {
                            union(i, j)
                            changed = true
                        }
                        continue
                    }
                    if (min(ha, hb) / max(ha, hb) < hRatio) continue
                    val overlapY = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
                    if (overlapY / min(ha, hb) < yOverlapRatio) continue
                    val gap = max(0f, max(b.left - a.right, a.left - b.right))
                    if (gap > xGapRatio * max(min(ha, hb), minSize)) continue
                    if (find(i) != find(j)) {
                        union(i, j)
                        changed = true
                    }
                }
            }
        }

        val groups = HashMap<Int, ArrayList<Aabb>>()
        for (i in 0 until n) {
            val root = find(i)
            groups.getOrPut(root) { ArrayList() }.add(filtered[i])
        }
        val merged = ArrayList<Aabb>(groups.size)
        for (group in groups.values) {
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = 0f
            var bottom = 0f
            for (box in group) {
                left = min(left, box.left)
                top = min(top, box.top)
                right = max(right, box.right)
                bottom = max(bottom, box.bottom)
            }
            merged.add(Aabb(left, top, right, bottom))
        }
        return merged
    }

    private fun expandAabb(aabb: Aabb, ratio: Float, minExpand: Float, bitmap: Bitmap): RectF {
        val h = max(1f, aabb.bottom - aabb.top)
        val pad = max(minExpand, ratio * h)
        val left = (aabb.left - pad).coerceIn(0f, bitmap.width.toFloat())
        val top = (aabb.top - pad).coerceIn(0f, bitmap.height.toFloat())
        val right = (aabb.right + pad).coerceIn(0f, bitmap.width.toFloat())
        val bottom = (aabb.bottom + pad).coerceIn(0f, bitmap.height.toFloat())
        return RectF(left, top, right, bottom)
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
        val scale: Float,
        val originalWidth: Int,
        val originalHeight: Int
    )

    private data class Aabb(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    companion object {
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val PROB_THRESHOLD = 0.3f
        private const val MIN_COMPONENT_PIXELS = 10
        private const val MERGE_X_GAP_RATIO = 2.0f
        private const val MERGE_Y_OVERLAP_RATIO = 0.2f
        private const val MERGE_H_RATIO = 0.2f
        private const val MERGE_CONTAIN_RATIO = 0.9f
        private const val MERGE_MIN_SIZE = 3.0f
        private const val MERGE_MIN_AREA = 9.0f
        private const val MERGE_EXPAND_RATIO = 0.8f
        private const val OUTPUT_EXPAND_RATIO = 0.15f
        private const val OUTPUT_EXPAND_MIN = 2.0f
    }
}
