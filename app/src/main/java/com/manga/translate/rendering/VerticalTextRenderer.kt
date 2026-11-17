package com.manga.translate.rendering

import android.graphics.Canvas
import android.graphics.RectF
import android.text.TextPaint

/** Shared vertical glyph placement used by export, reader and floating overlays. */
internal object VerticalTextRenderer {
    fun draw(
        canvas: Canvas,
        rect: RectF,
        textPaint: TextPaint,
        layout: VerticalTextLayout,
        startFromTop: Boolean = false
    ) {
        val extraTop = BubbleTextPlacement.verticalExtraTopPadding(
            rect = rect,
            layoutHeight = layout.totalHeight,
            startFromTop = startFromTop
        )
        val dx = rect.right - ((rect.width() - layout.totalWidth) / 2f) - layout.columnWidth
        val dy = rect.top + extraTop - layout.fontMetrics.ascent
        // Positions come from the layout, so kinsoku wrapping and rendering agree.
        for (glyph in layout.glyphs) {
            val glyphWidth = textPaint.measureText(glyph.text)
            val x = dx - glyph.column * layout.columnWidth +
                (layout.columnWidth - glyphWidth) / 2f
            val y = dy + glyph.row * layout.lineHeight
            canvas.drawText(glyph.text, x, y, textPaint)
        }
    }
}
