package com.manga.translate.rendering

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Stateless preparation shared by View caches and one-shot export rendering. */
internal object BubbleTextLayoutPreparer {
    data class Horizontal(val layout: StaticLayout, val paint: TextPaint)
    data class Vertical(val layout: VerticalTextLayout, val paint: TextPaint)

    fun horizontal(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        fillPercent: Int,
        basePaint: TextPaint,
        alignment: Layout.Alignment,
        lineSpacing: Float = 0f
    ): Horizontal {
        val width = maxWidth.coerceAtLeast(1)
        val height = maxHeight.coerceAtLeast(1)
        val preparedText = BubbleTextScaling.prepareTextForLayout(text)
        // Search layouts are temporary; only the final layout retains this dedicated paint.
        val paint = TextPaint(basePaint)
        fun buildLayout(content: String, availableWidth: Int, size: Float): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(content, 0, content.length, paint, availableWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .setLineSpacing(lineSpacing, 1f)
                .build()
        }
        val textSize = BubbleTextScaling.findAutoHorizontalTextSize(
            text = preparedText,
            maxWidth = width,
            maxHeight = height,
            buildLayout = ::buildLayout,
            textFillPercent = fillPercent,
            layoutFits = BubbleTextScaling::layoutFits
        )
        return Horizontal(buildLayout(preparedText, width, textSize), paint)
    }

    fun vertical(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        fillPercent: Int,
        basePaint: TextPaint
    ): Vertical {
        val width = maxWidth.coerceAtLeast(1)
        val height = maxHeight.coerceAtLeast(1)
        // Preserve original symbols: full-width minus and kana long vowel share a vertical glyph.
        val preparedText = BubbleTextScaling.prepareTextForLayout(text)
        val paint = TextPaint(basePaint)
        val textSize = BubbleTextScaling.findAutoVerticalTextSize(width, height, fillPercent) { size ->
            VerticalTextLayoutCalculator.build(paint, preparedText, width, height, size)
        }
        val layout = VerticalTextLayoutCalculator.build(paint, preparedText, width, height, textSize)
        return Vertical(layout, paint)
    }
}
