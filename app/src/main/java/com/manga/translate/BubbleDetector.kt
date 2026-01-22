package com.manga.translate

import android.content.Context
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

data class BubbleDetection(
    val rect: RectF,
    val confidence: Float,
    val classId: Int
)

class BubbleDetector(
    private val context: Context,
    private val modelAssetName: String = "comic-speech-bubble-detector.onnx"
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession()
    private val inputName: String
    private val inputShape: LongArray
    private val settingsStore = SettingsStore(context.applicationContext)

    init {
        val input = session.inputInfo.entries.first()
        inputName = input.key
        inputShape = (input.value.info as TensorInfo).shape
    }

    fun detect(bitmap: android.graphics.Bitmap): List<BubbleDetection> {
        val inputHeight = (inputShape.getOrNull(2) ?: 640L).toInt().coerceAtLeast(1)
        val inputWidth = (inputShape.getOrNull(3) ?: 640L).toInt().coerceAtLeast(1)
        val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val inputBuffer = FloatArray(3 * inputWidth * inputHeight)
        var offset = 0
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = resized.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                inputBuffer[offset] = r
                inputBuffer[offset + inputWidth * inputHeight] = g
                inputBuffer[offset + 2 * inputWidth * inputHeight] = b
                offset++
            }
        }

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuffer),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        )
        tensor.use {
            val outputs = session.run(mapOf(inputName to tensor))
            outputs.use {
                val output = outputs[0]
                val outputShape = (output.info as TensorInfo).shape
                val detections = parseDetections(output.value, outputShape)
                val filtered = filterByNms(
                    detections,
                    0.25f,
                    0.5f,
                    bitmap.width,
                    bitmap.height,
                    inputWidth,
                    inputHeight
                )
                if (settingsStore.loadModelIoLogging()) {
                    AppLogger.log(
                        "BubbleDetector",
                        "Input ${bitmap.width}x${bitmap.height}, output ${filtered.size} boxes: ${describeDetections(filtered)}"
                    )
                }
                return filtered
            }
        }
    }

    private fun parseDetections(raw: Any, shape: LongArray): List<RawDetection> {
        if (shape.size != 3) {
            return emptyList()
        }
        val n = max(shape[1], shape[2]).toInt()
        val c = min(shape[1], shape[2]).toInt()
        if (c < 5) {
            return emptyList()
        }

        val batch = raw as? Array<*> ?: return emptyList()
        val first = batch.firstOrNull() as? Array<*> ?: return emptyList()
        val data = first.mapNotNull { it as? FloatArray }
        if (data.size != first.size) {
            return emptyList()
        }

        val results = ArrayList<RawDetection>(n)
        if (shape[1] <= shape[2]) {
            if (data.any { it.size < n }) {
                return emptyList()
            }
            if (c == 6) {
                for (i in 0 until n) {
                    results.add(
                        RawDetection(
                            data[0][i],
                            data[1][i],
                            data[2][i],
                            data[3][i],
                            data[4][i],
                            data[5][i].toInt()
                        )
                    )
                }
            } else {
                for (i in 0 until n) {
                    val (classId, conf) = bestClass(data, i)
                    results.add(
                        RawDetection(
                            data[0][i],
                            data[1][i],
                            data[2][i],
                            data[3][i],
                            conf,
                            classId
                        )
                    )
                }
            }
        } else {
            if (data.size < n) {
                return emptyList()
            }
            for (i in 0 until n) {
                val row = data[i]
                if (row.size < 5) continue
                val (classId, conf) = if (row.size == 6) {
                    Pair(row[5].toInt(), row[4])
                } else {
                    bestClassRow(row)
                }
                results.add(RawDetection(row[0], row[1], row[2], row[3], conf, classId))
            }
        }
        return results
    }

    private fun bestClass(data: List<FloatArray>, index: Int): Pair<Int, Float> {
        var best = 0f
        var bestId = 0
        for (i in 4 until data.size) {
            val v = data[i][index]
            if (v > best) {
                best = v
                bestId = i - 4
            }
        }
        return Pair(bestId, best)
    }

    private fun bestClassRow(row: FloatArray): Pair<Int, Float> {
        var best = 0f
        var bestId = 0
        for (i in 4 until row.size) {
            val v = row[i]
            if (v > best) {
                best = v
                bestId = i - 4
            }
        }
        return Pair(bestId, best)
    }

    private fun filterByNms(
        detections: List<RawDetection>,
        confThreshold: Float,
        iouThreshold: Float,
        originalWidth: Int,
        originalHeight: Int,
        inputWidth: Int,
        inputHeight: Int
    ): List<BubbleDetection> {
        val filtered = detections.filter { it.confidence >= confThreshold }
            .sortedByDescending { it.confidence }
        val selected = ArrayList<BubbleDetection>()
        val taken = BooleanArray(filtered.size)

        for (i in filtered.indices) {
            if (taken[i]) continue
            val det = filtered[i]
            val rect = det.toRect(inputWidth, inputHeight, originalWidth, originalHeight)
            selected.add(BubbleDetection(rect, det.confidence, det.classId))
            for (j in i + 1 until filtered.size) {
                if (taken[j]) continue
                val other = filtered[j]
                val iou = iou(rect, other.toRect(inputWidth, inputHeight, originalWidth, originalHeight))
                if (iou > iouThreshold) {
                    taken[j] = true
                }
            }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
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

    private fun describeDetections(detections: List<BubbleDetection>, limit: Int = 3): String {
        if (detections.isEmpty()) return "[]"
        val preview = detections.take(limit).joinToString(prefix = "[", postfix = "]") { det ->
            val r = det.rect
            "(${r.left.toInt()},${r.top.toInt()},${r.right.toInt()},${r.bottom.toInt()},c=${"%.2f".format(det.confidence)})"
        }
        return if (detections.size > limit) "$preview..." else preview
    }
}

private data class RawDetection(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val confidence: Float,
    val classId: Int
) {
    fun toRect(
        inputWidth: Int,
        inputHeight: Int,
        originalWidth: Int,
        originalHeight: Int
    ): RectF {
        val normalized = w <= 1.5f && h <= 1.5f && cx <= 1.5f && cy <= 1.5f
        val xCenter = if (normalized) cx * inputWidth else cx
        val yCenter = if (normalized) cy * inputHeight else cy
        val width = if (normalized) w * inputWidth else w
        val height = if (normalized) h * inputHeight else h
        val left = ((xCenter - width / 2f) / inputWidth) * originalWidth
        val top = ((yCenter - height / 2f) / inputHeight) * originalHeight
        val right = ((xCenter + width / 2f) / inputWidth) * originalWidth
        val bottom = ((yCenter + height / 2f) / inputHeight) * originalHeight
        return RectF(left, top, right, bottom)
    }
}
