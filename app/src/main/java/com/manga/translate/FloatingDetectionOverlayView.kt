package com.manga.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.withTranslation

class FloatingDetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F1F1F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        textSize = resources.displayMetrics.density * resources.configuration.fontScale * 12f
    }
    private val sourceRect = RectF()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var bubbles: List<BubbleTranslation> = emptyList()

    fun setDetections(sourceWidth: Int, sourceHeight: Int, bubbles: List<BubbleTranslation>) {
        this.sourceWidth = sourceWidth.coerceAtLeast(1)
        this.sourceHeight = sourceHeight.coerceAtLeast(1)
        this.bubbles = bubbles
        invalidate()
    }

    fun clearDetections() {
        bubbles = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isEmpty()) return
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        val radius = resources.displayMetrics.density * 8f
        val horizontalPadding = resources.displayMetrics.density * 6f
        val verticalPadding = resources.displayMetrics.density * 5f
        for (bubble in bubbles) {
            val rect = bubble.rect
            sourceRect.set(
                rect.left * scaleX,
                rect.top * scaleY,
                rect.right * scaleX,
                rect.bottom * scaleY
            )
            if (sourceRect.width() < 2f || sourceRect.height() < 2f) continue
            canvas.drawRoundRect(sourceRect, radius, radius, boxPaint)
            canvas.drawRoundRect(sourceRect, radius, radius, borderPaint)
            val text = bubble.text.ifBlank { context.getString(R.string.floating_bubble_placeholder) }
            val availableWidth = (sourceRect.width() - horizontalPadding * 2f).toInt().coerceAtLeast(1)
            val textLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            val drawY = (sourceRect.top + verticalPadding)
                .coerceAtMost(sourceRect.bottom - verticalPadding - textLayout.height)
            canvas.withTranslation(sourceRect.left + horizontalPadding, drawY) {
                textLayout.draw(this)
            }
        }
    }
}
