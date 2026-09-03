package com.manga.translate.rendering

import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout

internal object BubbleTextScaling {
    private const val MIN_TEXT_SIZE_PX = 0.5f
    private const val TEXT_SIZE_PRECISION_PX = 0.25f

    fun layoutFits(layout: StaticLayout, maxWidth: Int, maxHeight: Int): Boolean {
        if (layout.height > maxHeight) return false
        for (line in 0 until layout.lineCount) {
            if (layout.getLineWidth(line) > maxWidth + 0.5f) {
                return false
            }
        }
        return true
    }

    /** Returns the text-safe bounds without changing the user's bubble geometry. */
    fun resolveTextRect(path: Path): RectF {
        val textRect = RectF()
        BubbleShapePaths.insetTextBounds(path, textRect)
        return textRect
    }

    fun findAutoHorizontalTextSize(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        buildLayout: (String, Int, Float) -> StaticLayout,
        layoutFits: (StaticLayout, Int, Int) -> Boolean
    ): Float {
        return findLargestFittingTextSize(maxWidth, maxHeight) { textSize ->
            layoutFits(buildLayout(text, maxWidth, textSize), maxWidth, maxHeight)
        }
    }

    fun findLargestFittingTextSize(
        maxWidth: Int,
        maxHeight: Int,
        fits: (Float) -> Boolean
    ): Float {
        var bestSize = MIN_TEXT_SIZE_PX
        var low = MIN_TEXT_SIZE_PX
        var high = maxOf(low, maxWidth.toFloat(), maxHeight.toFloat())
        if (!fits(low)) return low

        while (high - low > TEXT_SIZE_PRECISION_PX) {
            val mid = (low + high) / 2f
            if (fits(mid)) {
                bestSize = mid
                low = mid
            } else {
                high = mid
            }
        }
        return bestSize
    }
}
