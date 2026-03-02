package com.manga.translate

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object RectGeometryDeduplicator {
    fun mergeSupplementRects(
        rects: List<RectF>,
        imageWidth: Int,
        imageHeight: Int
    ): List<RectF> {
        if (rects.size <= 1) return rects
        val imageArea = (imageWidth.toFloat() * imageHeight.toFloat()).coerceAtLeast(1f)
        val mergedRects = rects.map { RectF(it) }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            for (i in 0 until mergedRects.size) {
                var j = i + 1
                while (j < mergedRects.size) {
                    if (shouldMergeRects(mergedRects[i], mergedRects[j], imageArea)) {
                        mergedRects[i] = unionRects(mergedRects[i], mergedRects[j])
                        mergedRects.removeAt(j)
                        merged = true
                    } else {
                        j++
                    }
                }
            }
        }
        return mergedRects
    }

    private fun shouldMergeRects(a: RectF, b: RectF, imageArea: Float): Boolean {
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        if (areaA <= 0f || areaB <= 0f) return false
        val minArea = min(areaA, areaB)
        val overlapOverMin = overlapOverMinArea(a, b, minArea)
        if (overlapOverMin >= MERGE_OVERLAP_MIN_RATIO) return true

        val sizeT = sqrt((minArea / imageArea) / MERGE_SIZE_REF_AREA).coerceIn(0f, 1f)
        val pad = lerp(MERGE_PAD_MAX, MERGE_PAD_MIN, sizeT)
        val iouThreshold = lerp(MERGE_IOU_SMALL, MERGE_IOU_LARGE, sizeT)

        val union = unionRects(a, b)
        val unionArea = max(0f, union.width()) * max(0f, union.height())
        if (unionArea / imageArea >= MERGE_MAX_UNION_FRACTION) return false

        val centerAY = (a.top + a.bottom) * 0.5f
        val centerBY = (b.top + b.bottom) * 0.5f
        val yGap = abs(centerAY - centerBY)
        val yGapLimit = lerp(MERGE_Y_GAP_MAX, MERGE_Y_GAP_MIN, sizeT)
        if (yGap > yGapLimit) return false

        if (iou(a, b) >= iouThreshold) return true
        val expandedA = RectF(a.left - pad, a.top - pad, a.right + pad, a.bottom + pad)
        val expandedB = RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
        return RectF.intersects(expandedA, b) || RectF.intersects(expandedB, a)
    }

    private fun overlapOverMinArea(a: RectF, b: RectF, minArea: Float): Float {
        if (minArea <= 0f) return 0f
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        return inter / minArea
    }

    private fun unionRects(a: RectF, b: RectF): RectF {
        return RectF(
            min(a.left, b.left),
            min(a.top, b.top),
            max(a.right, b.right),
            max(a.bottom, b.bottom)
        )
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

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t.coerceIn(0f, 1f)
    }

    private const val MERGE_PAD_MAX = 40f
    private const val MERGE_PAD_MIN = 8f
    private const val MERGE_SIZE_REF_AREA = 0.02f
    private const val MERGE_IOU_SMALL = 0.07f
    private const val MERGE_IOU_LARGE = 0.28f
    private const val MERGE_OVERLAP_MIN_RATIO = 0.2f
    private const val MERGE_MAX_UNION_FRACTION = 0.2f
    private const val MERGE_Y_GAP_MAX = 140f
    private const val MERGE_Y_GAP_MIN = 36f
}
