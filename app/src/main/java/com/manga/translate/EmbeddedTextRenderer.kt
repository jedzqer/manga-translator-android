package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min

class EmbeddedTextRenderer {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt()
    }
    private val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEEFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    fun render(
        source: Bitmap,
        translation: TranslationResult,
        verticalLayoutEnabled: Boolean
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scaleX = if (translation.width > 0) {
            output.width.toFloat() / translation.width.toFloat()
        } else {
            1f
        }
        val scaleY = if (translation.height > 0) {
            output.height.toFloat() / translation.height.toFloat()
        } else {
            1f
        }
        val rect = RectF()
        for (bubble in translation.bubbles) {
            val text = bubble.text.trim()
            if (text.isBlank()) continue
            rect.set(
                bubble.rect.left * scaleX,
                bubble.rect.top * scaleY,
                bubble.rect.right * scaleX,
                bubble.rect.bottom * scaleY
            )
            drawTextInRect(canvas, text, rect, verticalLayoutEnabled)
        }
        return output
    }

    private fun drawTextInRect(
        canvas: Canvas,
        text: String,
        rect: RectF,
        verticalLayoutEnabled: Boolean
    ) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val pad = (min(rect.width(), rect.height()) * 0.08f).coerceAtLeast(4f)
        val textRect = RectF(rect)
        textRect.inset(pad, pad)

        if (verticalLayoutEnabled) {
            drawVerticalTextInRect(canvas, text, textRect)
        } else {
            val maxWidth = textRect.width().toInt().coerceAtLeast(1)
            val maxHeight = textRect.height().toInt().coerceAtLeast(1)
            var textSize = (textRect.height() / 3f).coerceIn(12f, 42f)
            var layout = buildLayout(text, maxWidth, textSize)
            while (layout.height > maxHeight && textSize > 10f) {
                textSize *= 0.9f
                layout = buildLayout(text, maxWidth, textSize)
            }
            val dx = textRect.left
            val dy = textRect.top + ((textRect.height() - layout.height) / 2f).coerceAtLeast(0f)
            drawHorizontalPerGlyph(canvas, text, layout, dx, dy, textRect)
        }
    }

    private fun buildLayout(text: String, width: Int, textSize: Float): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun drawVerticalTextInRect(canvas: Canvas, text: String, rect: RectF) {
        val maxWidth = rect.width().toInt().coerceAtLeast(1)
        val maxHeight = rect.height().toInt().coerceAtLeast(1)
        var textSize = (rect.width() / 2.2f).coerceIn(12f, 42f)
        var layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        while ((layout.columnWidth <= 0f || layout.lineHeight <= 0f || !layout.fits) && textSize > 10f) {
            textSize *= 0.9f
            layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        }
        val dx = rect.right - ((rect.width() - layout.totalWidth) / 2f) - layout.columnWidth
        val dy = rect.top + ((rect.height() - layout.totalHeight) / 2f) - layout.fontMetrics.ascent
        var col = 0
        var row = 0
        for (ch in text) {
            if (ch == '\n') {
                col += 1
                row = 0
                continue
            }
            if (row >= layout.maxRows) {
                col += 1
                row = 0
            }
            if (col >= layout.columns) break
            val glyph = ch.toString()
            val charWidth = textPaint.measureText(glyph)
            val x = dx - col * layout.columnWidth + (layout.columnWidth - charWidth) / 2f
            val y = dy + row * layout.lineHeight
            drawGlyphBackground(
                canvas = canvas,
                x = x,
                baseline = y,
                charWidth = charWidth,
                fontMetrics = layout.fontMetrics,
                maxRect = rect
            )
            canvas.drawText(glyph, x, y, textPaint)
            row += 1
        }
    }

    private fun drawHorizontalPerGlyph(
        canvas: Canvas,
        text: String,
        layout: StaticLayout,
        dx: Float,
        dy: Float,
        maxRect: RectF
    ) {
        val fm = textPaint.fontMetrics
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line).coerceAtMost(text.length)
            val baseline = dy + layout.getLineBaseline(line)
            for (i in start until end) {
                val ch = text[i]
                if (ch == '\n') continue
                val glyphWidth = textPaint.measureText(text, i, i + 1)
                if (glyphWidth <= 0f) continue
                val x = dx + layout.getPrimaryHorizontal(i)
                if (!ch.isWhitespace()) {
                    drawGlyphBackground(
                        canvas = canvas,
                        x = x,
                        baseline = baseline,
                        charWidth = glyphWidth,
                        fontMetrics = fm,
                        maxRect = maxRect
                    )
                }
                canvas.drawText(text, i, i + 1, x, baseline, textPaint)
            }
        }
    }

    private fun buildVerticalLayout(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        textSize: Float
    ): VerticalLayout {
        textPaint.textSize = textSize
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
        val maxRows = (maxHeight / lineHeight).toInt().coerceAtLeast(1)
        val charCount = text.count { it != '\n' }.coerceAtLeast(1)
        var maxCharWidth = 0f
        for (ch in text) {
            if (ch == '\n') continue
            val width = textPaint.measureText(ch.toString())
            if (width > maxCharWidth) {
                maxCharWidth = width
            }
        }
        if (maxCharWidth <= 0f) {
            maxCharWidth = textPaint.measureText("国")
        }
        maxCharWidth = maxCharWidth.coerceAtLeast(1f)
        val columns = ((charCount + maxRows - 1) / maxRows).coerceAtLeast(1)
        val totalWidth = columns * maxCharWidth
        val totalHeight = maxRows * lineHeight
        val fits = totalWidth <= maxWidth && totalHeight <= maxHeight
        return VerticalLayout(
            columnWidth = maxCharWidth,
            lineHeight = lineHeight,
            maxRows = maxRows,
            columns = columns,
            totalWidth = totalWidth,
            totalHeight = totalHeight,
            fontMetrics = fontMetrics,
            fits = fits
        )
    }

    private fun drawGlyphBackground(
        canvas: Canvas,
        x: Float,
        baseline: Float,
        charWidth: Float,
        fontMetrics: Paint.FontMetrics,
        maxRect: RectF
    ) {
        val left = x
        val right = x + charWidth
        val top = baseline + fontMetrics.ascent
        val bottom = baseline + fontMetrics.descent
        if (right <= left || bottom <= top) return
        val pad = (min(maxRect.width(), maxRect.height()) * 0.025f).coerceAtLeast(1.5f)
        val bg = RectF(
            (left - pad).coerceAtLeast(maxRect.left),
            (top - pad).coerceAtLeast(maxRect.top),
            (right + pad).coerceAtMost(maxRect.right),
            (bottom + pad).coerceAtMost(maxRect.bottom)
        )
        val radius = (min(bg.width(), bg.height()) * 0.2f).coerceAtLeast(2f)
        canvas.drawRoundRect(bg, radius, radius, textBackgroundPaint)
    }

    private data class VerticalLayout(
        val columnWidth: Float,
        val lineHeight: Float,
        val maxRows: Int,
        val columns: Int,
        val totalWidth: Float,
        val totalHeight: Float,
        val fontMetrics: Paint.FontMetrics,
        val fits: Boolean
    )
}
