package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max

class TextDetector(
    context: Context,
    modelAssetName: String = "ysgyolo_1.2_OS1.0.onnx"
) {
    private val detector = YsgYoloTextDetector(context, modelAssetName)
    private val settingsStore = SettingsStore(context.applicationContext)

    fun detect(bitmap: Bitmap): List<RectF> {
        val detections = detector.detect(
            bitmap = bitmap,
            confThreshold = YSG_CONF_THRESHOLD,
            iouThreshold = YSG_NMS_IOU_THRESHOLD
        )
        val expanded = detections.map { expandRect(it.aabb, OUTPUT_EXPAND_RATIO, OUTPUT_EXPAND_MIN, bitmap) }
        if (settingsStore.loadModelIoLogging()) {
            AppLogger.log(
                "TextDetector",
                "Input ${bitmap.width}x${bitmap.height}, output ${expanded.size} boxes: ${describeRects(expanded)}"
            )
        }
        return expanded
    }

    private fun expandRect(rect: RectF, ratio: Float, minExpand: Float, bitmap: Bitmap): RectF {
        val h = max(1f, rect.height())
        val pad = max(minExpand, ratio * h)
        val left = (rect.left - pad).coerceIn(0f, bitmap.width.toFloat())
        val top = (rect.top - pad).coerceIn(0f, bitmap.height.toFloat())
        val right = (rect.right + pad).coerceIn(0f, bitmap.width.toFloat())
        val bottom = (rect.bottom + pad).coerceIn(0f, bitmap.height.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun describeRects(rects: List<RectF>, limit: Int = 3): String {
        if (rects.isEmpty()) return "[]"
        val preview = rects.take(limit).joinToString(prefix = "[", postfix = "]") { rect ->
            "(${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()})"
        }
        return if (rects.size > limit) "$preview..." else preview
    }

    companion object {
        private const val YSG_CONF_THRESHOLD = 0.4f
        private const val YSG_NMS_IOU_THRESHOLD = 0.5f
        private const val OUTPUT_EXPAND_RATIO = 0.08f
        private const val OUTPUT_EXPAND_MIN = 1.0f
    }
}
