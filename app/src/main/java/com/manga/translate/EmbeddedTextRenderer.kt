package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

class EmbeddedTextRenderer {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt()
    }
    private val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val maxPaddingRatio = 0.12f

    fun render(
        source: Bitmap,
        translation: TranslationResult,
        verticalLayoutEnabled: Boolean,
        shouldDrawTextBackground: (BubbleTranslation) -> Boolean = { true }
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
            drawTextInRect(
                canvas = canvas,
                text = text,
                rect = rect,
                verticalLayoutEnabled = verticalLayoutEnabled,
                drawBackground = shouldDrawTextBackground(bubble)
            )
        }
        return output
    }

    private fun drawTextInRect(
        canvas: Canvas,
        text: String,
        rect: RectF,
        verticalLayoutEnabled: Boolean,
        drawBackground: Boolean
    ) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val pad = (min(rect.width(), rect.height()) * 0.08f).coerceAtLeast(4f)
        val textRect = RectF(rect)
        textRect.inset(pad, pad)

        if (verticalLayoutEnabled) {
            val converted = VerticalTextSymbolConverter.convert(text)
            drawVerticalTextInRect(canvas, converted, textRect, drawBackground)
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
            drawHorizontalPerGlyph(canvas, text, layout, dx, dy, textRect, drawBackground)
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

    private fun drawVerticalTextInRect(canvas: Canvas, text: String, rect: RectF, drawBackground: Boolean) {
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
            if (drawBackground) {
                drawGlyphBackground(
                    canvas = canvas,
                    x = x,
                    baseline = y,
                    charWidth = charWidth,
                    fontMetrics = layout.fontMetrics,
                    maxRect = rect,
                    maxNeighborGap = computeVerticalNeighborGap(layout, charWidth)
                )
            }
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
        maxRect: RectF,
        drawBackground: Boolean
    ) {
        val fm = textPaint.fontMetrics
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line).coerceAtMost(text.length)
            val baseline = dy + layout.getLineBaseline(line)
            val lineGaps = computeHorizontalLineGaps(text, layout, line, dx)
            for (i in start until end) {
                val ch = text[i]
                if (ch == '\n') continue
                val glyphWidth = textPaint.measureText(text, i, i + 1)
                if (glyphWidth <= 0f) continue
                val x = dx + layout.getPrimaryHorizontal(i)
                if (drawBackground && !ch.isWhitespace()) {
                    drawGlyphBackground(
                        canvas = canvas,
                        x = x,
                        baseline = baseline,
                        charWidth = glyphWidth,
                        fontMetrics = fm,
                        maxRect = maxRect,
                        maxNeighborGap = lineGaps[i] ?: 0f
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
        maxRect: RectF,
        maxNeighborGap: Float
    ) {
        val left = x
        val right = x + charWidth
        val top = baseline + fontMetrics.ascent
        val bottom = baseline + fontMetrics.descent
        if (right <= left || bottom <= top) return
        val glyphHeight = bottom - top
        val neighborPad = (maxNeighborGap.coerceAtLeast(0f) * 0.2f).coerceAtLeast(0f)
        val basePadX = max(charWidth * 0.08f, 0.6f) + neighborPad
        val basePadY = max(glyphHeight * 0.06f, 0.6f)
        val minPadding = computeMinPadding(maxRect)
        val maxPadX = (charWidth * maxPaddingRatio).coerceAtLeast(minPadding)
        val maxPadY = (glyphHeight * maxPaddingRatio).coerceAtLeast(minPadding)
        val padX = basePadX.coerceIn(minPadding * 0.5f, maxPadX)
        val padY = basePadY.coerceIn(minPadding * 0.5f, maxPadY)
        val bg = RectF(
            (left - padX).coerceAtLeast(maxRect.left),
            (top - padY).coerceAtLeast(maxRect.top),
            (right + padX).coerceAtMost(maxRect.right),
            (bottom + padY).coerceAtMost(maxRect.bottom)
        )
        val radius = (min(bg.width(), bg.height()) * 0.12f).coerceAtLeast(1f)
        canvas.drawRoundRect(bg, radius, radius, textBackgroundPaint)
    }

    private fun computeMinPadding(maxRect: RectF): Float {
        val scaled = (min(maxRect.width(), maxRect.height()) / 600f) * 1.8f
        return scaled.coerceIn(0.8f, 2f)
    }

    private fun computeHorizontalLineGaps(
        text: String,
        layout: StaticLayout,
        line: Int,
        dx: Float
    ): Map<Int, Float> {
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line).coerceAtMost(text.length)
        val glyphs = mutableListOf<LineGlyph>()
        for (i in start until end) {
            val ch = text[i]
            if (ch == '\n' || ch.isWhitespace()) continue
            val width = textPaint.measureText(text, i, i + 1)
            if (width <= 0f) continue
            val left = dx + layout.getPrimaryHorizontal(i)
            glyphs.add(LineGlyph(index = i, left = left, right = left + width))
        }
        if (glyphs.isEmpty()) return emptyMap()
        val gaps = mutableMapOf<Int, Float>()
        for (i in glyphs.indices) {
            var maxGap = 0f
            if (i > 0) {
                maxGap = max(maxGap, glyphs[i].left - glyphs[i - 1].right)
            }
            if (i < glyphs.lastIndex) {
                maxGap = max(maxGap, glyphs[i + 1].left - glyphs[i].right)
            }
            gaps[glyphs[i].index] = maxGap.coerceAtLeast(0f)
        }
        return gaps
    }

    private fun computeVerticalNeighborGap(layout: VerticalLayout, charWidth: Float): Float {
        val horizontalGap = (layout.columnWidth - charWidth).coerceAtLeast(0f)
        val glyphHeight = (layout.fontMetrics.descent - layout.fontMetrics.ascent).coerceAtLeast(0f)
        val verticalGap = (layout.lineHeight - glyphHeight).coerceAtLeast(0f)
        return max(horizontalGap, verticalGap)
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

    private data class LineGlyph(
        val index: Int,
        val left: Float,
        val right: Float
    )
}
