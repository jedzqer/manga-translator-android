package com.manga.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    private val minTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        8f,
        resources.displayMetrics
    )
    private val textSizeStepPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        0.5f,
        resources.displayMetrics
    ).coerceAtLeast(0.5f)
    private val sourceRect = RectF()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var bubbles: List<BubbleTranslation> = emptyList()
    private var bubbleDragEnabled = false
    private val touchSlop = 3f * resources.displayMetrics.density
    private var draggingBubbleId: Int? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false

    fun setDetections(sourceWidth: Int, sourceHeight: Int, bubbles: List<BubbleTranslation>) {
        this.sourceWidth = sourceWidth.coerceAtLeast(1)
        this.sourceHeight = sourceHeight.coerceAtLeast(1)
        this.bubbles = bubbles
        invalidate()
    }

    fun clearDetections() {
        bubbles = emptyList()
        draggingBubbleId = null
        invalidate()
    }

    fun setBubbleDragEnabled(enabled: Boolean) {
        bubbleDragEnabled = enabled
        if (!enabled) {
            draggingBubbleId = null
            isDragging = false
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!bubbleDragEnabled || bubbles.isEmpty()) return false
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        val sourceX = event.x / scaleX
        val sourceY = event.y / scaleY
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val bubble = findBubbleAt(sourceX, sourceY) ?: return false
                draggingBubbleId = bubble.id
                downX = event.x
                downY = event.y
                isDragging = false
                dragOffsetX = sourceX - bubble.rect.left
                dragOffsetY = sourceY - bubble.rect.top
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                val id = draggingBubbleId ?: return false
                val moved = abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                if (moved) {
                    isDragging = true
                }
                if (!isDragging) return true
                updateBubblePosition(id, sourceX, sourceY)
                return true
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                val hadTarget = draggingBubbleId != null
                draggingBubbleId = null
                isDragging = false
                return hadTarget
            }

            else -> return false
        }
    }

    private fun findBubbleAt(x: Float, y: Float): BubbleTranslation? {
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            if (bubble.rect.contains(x, y)) {
                return bubble
            }
        }
        return null
    }

    private fun updateBubblePosition(id: Int, sourceX: Float, sourceY: Float) {
        val mutable = bubbles.toMutableList()
        val index = mutable.indexOfFirst { it.id == id }
        if (index < 0) return
        val bubble = mutable[index]
        val width = bubble.rect.width().coerceAtLeast(1f)
        val height = bubble.rect.height().coerceAtLeast(1f)
        val maxLeft = max(0f, sourceWidth.toFloat() - width)
        val maxTop = max(0f, sourceHeight.toFloat() - height)
        val newLeft = min(max(sourceX - dragOffsetX, 0f), maxLeft)
        val newTop = min(max(sourceY - dragOffsetY, 0f), maxTop)
        val newRect = RectF(
            newLeft,
            newTop,
            newLeft + width,
            newTop + height
        )
        mutable[index] = bubble.copy(rect = newRect)
        bubbles = mutable
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
            val availableHeight = (sourceRect.height() - verticalPadding * 2f).toInt().coerceAtLeast(1)
            val textLayout = buildFittedTextLayout(text, availableWidth, availableHeight)
            val drawY = sourceRect.top + verticalPadding +
                ((availableHeight - textLayout.height) / 2f).coerceAtLeast(0f)
            canvas.withTranslation(sourceRect.left + horizontalPadding, drawY) {
                textLayout.draw(this)
            }
        }
    }

    private fun buildFittedTextLayout(
        text: String,
        availableWidth: Int,
        availableHeight: Int
    ): StaticLayout {
        val probePaint = TextPaint(textPaint)
        var size = textPaint.textSize
        var fitted = buildLayout(text, probePaint, availableWidth)
        while (fitted.height > availableHeight && size > minTextSizePx) {
            size = (size - textSizeStepPx).coerceAtLeast(minTextSizePx)
            probePaint.textSize = size
            fitted = buildLayout(text, probePaint, availableWidth)
            if (size <= minTextSizePx) break
        }
        return fitted
    }

    private fun buildLayout(text: String, paint: TextPaint, availableWidth: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }
}
