package com.manga.translate.rendering

import android.graphics.Paint
import android.text.TextPaint

internal data class VerticalTextLayout(
    val columnWidth: Float,
    val lineHeight: Float,
    val maxRows: Int,
    val columns: Int,
    val totalWidth: Float,
    val totalHeight: Float,
    val fontMetrics: Paint.FontMetrics,
    val fits: Boolean,
    /** Pre-computed glyph positions, so layout and rendering cannot disagree. */
    val glyphs: List<VerticalLineBreaker.Glyph> = emptyList()
)

internal object VerticalTextLayoutCalculator {
    fun build(
        textPaint: TextPaint,
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        textSize: Float
    ): VerticalTextLayout {
        textPaint.textSize = textSize
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
        val maxRows = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        // Kinsoku-aware wrapping: closing punctuation follows the previous glyph,
        // opening brackets stay with the next one, Latin words stay in one column.
        val breaks = VerticalLineBreaker.breakText(text, maxRows)
        // Measure the glyphs actually drawn (single code points), so surrogate pairs
        // are not counted as two narrow halves.
        var maxCharWidth = 0f
        for (glyph in breaks.glyphs) {
            if (glyph.text.isBlank()) continue
            val width = textPaint.measureText(glyph.text)
            if (width > maxCharWidth) {
                maxCharWidth = width
            }
        }
        if (maxCharWidth <= 0f) {
            maxCharWidth = textPaint.measureText("国")
        }
        maxCharWidth = maxCharWidth.coerceAtLeast(1f)
        val totalWidth = breaks.columns * maxCharWidth
        val totalHeight = breaks.usedRows * lineHeight
        val fits = totalWidth <= maxWidth && totalHeight <= maxHeight
        return VerticalTextLayout(
            columnWidth = maxCharWidth,
            lineHeight = lineHeight,
            maxRows = maxRows,
            columns = breaks.columns,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            fontMetrics = fontMetrics,
            fits = fits,
            glyphs = breaks.glyphs
        )
    }
}
