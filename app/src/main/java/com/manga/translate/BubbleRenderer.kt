package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min

class BubbleRenderer(context: Context) {
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2D2D2D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
    }
    private val bubbleRect = RectF()

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
        for (bubble in translation.bubbles) {
            val text = bubble.text.trim()
            if (text.isBlank()) continue
            bubbleRect.set(
                bubble.rect.left * scaleX,
                bubble.rect.top * scaleY,
                bubble.rect.right * scaleX,
                bubble.rect.bottom * scaleY
            )
            drawBubble(canvas, text, bubbleRect, verticalLayoutEnabled)
        }
        return output
    }

    private fun drawBubble(canvas: Canvas, text: String, rect: RectF, verticalLayoutEnabled: Boolean) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val pad = (min(rect.width(), rect.height()) * 0.08f).coerceAtLeast(6f)
        val textRect = RectF(rect)
        textRect.inset(pad, pad)
        canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
        canvas.drawRoundRect(rect, 6f, 6f, strokePaint)
        drawTextInRect(canvas, text, textRect, verticalLayoutEnabled)
    }

    private fun drawTextInRect(
        canvas: Canvas,
        text: String,
        rect: RectF,
        verticalLayoutEnabled: Boolean
    ) {
        if (verticalLayoutEnabled) {
            drawVerticalTextInRect(canvas, VerticalTextSymbolConverter.convert(text), rect)
        } else {
            val maxWidth = rect.width().toInt().coerceAtLeast(1)
            val maxHeight = rect.height().toInt().coerceAtLeast(1)
            var textSize = (rect.height() / 3f).coerceIn(12f, 42f)
            var layout = buildLayout(text, maxWidth, textSize)
            while (layout.height > maxHeight && textSize > 10f) {
                textSize *= 0.9f
                layout = buildLayout(text, maxWidth, textSize)
            }
            val dx = rect.left
            val dy = rect.top + ((rect.height() - layout.height) / 2f).coerceAtLeast(0f)
            canvas.save()
            canvas.translate(dx, dy)
            layout.draw(canvas)
            canvas.restore()
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
            canvas.drawText(glyph, x, y, textPaint)
            row += 1
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
