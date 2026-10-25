package com.manga.translate.rendering

import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout

internal object BubbleTextScaling {
    private const val MIN_TEXT_SIZE_PX = 0.5f
    private const val TEXT_SIZE_PRECISION_PX = 0.25f
    private const val PREFERRED_TEXT_SIZE_RATIO = 0.8f

    // Translation/OCR line wrapping describes the source, not the resized bubble.
    // Reflow single line breaks while retaining explicit paragraph boundaries.
    fun prepareTextForLayout(text: String): String {
        return text.replace("\r\n", "\n").replace('\r', '\n').trim()
            .split(Regex("\\n[ \t]*\\n+"))
            .joinToString("\n\n") { paragraph ->
                paragraph.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    .reduceOrNull { left, right ->
                        val separator = if (isCjk(left.last()) && isCjk(right.first())) "" else " "
                        left + separator + right
                    }.orEmpty()
            }
    }

    private fun isCjk(char: Char): Boolean =
        char in '\u2E80'..'\u9FFF' || char in '\uAC00'..'\uD7AF' ||
            char in '\uF900'..'\uFAFF' || char in '\uFF00'..'\uFFEF'

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
        layoutFits: (StaticLayout, Int, Int) -> Boolean,
        textFillPercent: Int = 100
    ): Float {
        return findAutoTextSizePreferringFewerUnits(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            buildLayout = { textSize -> buildLayout(text, maxWidth, textSize) },
            layoutFits = { layout -> layoutFits(layout, maxWidth, maxHeight) },
            unitCount = { layout -> layout.lineCount }
        ).let { scaleTextSize(it, textFillPercent) }
    }

    /** Fit with a preference for fewer columns, then apply the user's font-size percentage. */
    fun findAutoVerticalTextSize(
        maxWidth: Int,
        maxHeight: Int,
        textFillPercent: Int = 100,
        buildLayout: (Float) -> VerticalTextLayout
    ): Float {
        return findAutoTextSizePreferringFewerUnits(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            buildLayout = buildLayout,
            layoutFits = { layout -> layout.fits },
            unitCount = { layout -> layout.columns }
        ).let { scaleTextSize(it, textFillPercent) }
    }

    // Scale the fitted font, not the bubble geometry or the available line width.
    private fun scaleTextSize(autoSize: Float, percent: Int): Float =
        (autoSize * percent.coerceIn(20, 100) / 100f).coerceAtLeast(MIN_TEXT_SIZE_PX)

    private fun <T> findAutoTextSizePreferringFewerUnits(
        maxWidth: Int,
        maxHeight: Int,
        buildLayout: (Float) -> T,
        layoutFits: (T) -> Boolean,
        unitCount: (T) -> Int
    ): Float {
        val largest = findLargestFittingTextSize(maxWidth, maxHeight) { size ->
            layoutFits(buildLayout(size))
        }
        val largestLayout = buildLayout(largest)
        val largestUnitCount = unitCount(largestLayout)
        if (!layoutFits(largestLayout) || largestUnitCount <= 1) return largest

        val minimumSize = (largest * PREFERRED_TEXT_SIZE_RATIO).coerceAtLeast(MIN_TEXT_SIZE_PX)
        val preferredLayout = buildLayout(minimumSize)
        val preferredUnitCount = unitCount(preferredLayout)
        if (!layoutFits(preferredLayout) || preferredUnitCount >= largestUnitCount) return largest

        var low = minimumSize
        var high = largest
        while (high - low > TEXT_SIZE_PRECISION_PX) {
            val mid = (low + high) / 2f
            val layout = buildLayout(mid)
            if (layoutFits(layout) && unitCount(layout) <= preferredUnitCount) {
                low = mid
            } else {
                high = mid
            }
        }
        return low
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
