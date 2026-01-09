package com.manga.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingTranslationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
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
        strokeWidth = resources.displayMetrics.density
    }

    private var bubbles: List<BubbleTranslation> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0
    private val displayRect = RectF()
    private val offsets = mutableMapOf<Int, Pair<Float, Float>>()
    private var scaleX = 1f
    private var scaleY = 1f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var activeId: Int? = null

    var onOffsetChanged: ((Float, Float) -> Unit)? = null
    var onTap: ((Float) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setTranslations(result: TranslationResult?) {
        bubbles = result?.bubbles.orEmpty()
        imageWidth = result?.width ?: 0
        imageHeight = result?.height ?: 0
        updateScale()
        invalidate()
    }

    fun setDisplayRect(rect: RectF) {
        displayRect.set(rect)
        updateScale()
        invalidate()
    }

    fun setOffsets(values: Map<Int, Pair<Float, Float>>) {
        offsets.clear()
        offsets.putAll(values)
        invalidate()
    }

    fun getOffsets(): Map<Int, Pair<Float, Float>> {
        return offsets.toMap()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return
        for (bubble in bubbles) {
            if (bubble.text.isBlank()) continue
            val offset = offsets[bubble.id] ?: 0f to 0f
            val rect = RectF(
                displayRect.left + (bubble.rect.left + offset.first) * scaleX,
                displayRect.top + (bubble.rect.top + offset.second) * scaleY,
                displayRect.left + (bubble.rect.right + offset.first) * scaleX,
                displayRect.top + (bubble.rect.bottom + offset.second) * scaleY
            )
            drawBubble(canvas, bubble.text, rect)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                activeId = findBubbleAt(event.x, event.y)
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                }
                if (dragging) {
                    updateOffset(dx, dy)
                    downX = event.x
                    downY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    onTap?.invoke(event.x)
                    performClick()
                }
                dragging = false
                activeId = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                activeId = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateOffset(dx: Float, dy: Float) {
        val id = activeId ?: return
        if (imageWidth <= 0 || imageHeight <= 0) return
        val bubble = bubbles.firstOrNull { it.id == id } ?: return
        val current = offsets[id] ?: 0f to 0f
        val deltaX = dx / scaleX
        val deltaY = dy / scaleY
        var newX = current.first + deltaX
        var newY = current.second + deltaY
        val minX = -bubble.rect.left
        val maxX = imageWidth - bubble.rect.right
        val minY = -bubble.rect.top
        val maxY = imageHeight - bubble.rect.bottom
        newX = min(max(newX, minX), maxX)
        newY = min(max(newY, minY), maxY)
        offsets[id] = newX to newY
        onOffsetChanged?.invoke(newX, newY)
        invalidate()
    }

    private fun updateScale() {
        if (imageWidth <= 0 || imageHeight <= 0 || displayRect.width() <= 0f || displayRect.height() <= 0f) {
            scaleX = 1f
            scaleY = 1f
            return
        }
        scaleX = displayRect.width() / imageWidth
        scaleY = displayRect.height() / imageHeight
    }

    private fun findBubbleAt(x: Float, y: Float): Int? {
        if (bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            val offset = offsets[bubble.id] ?: 0f to 0f
            val left = displayRect.left + (bubble.rect.left + offset.first) * scaleX
            val top = displayRect.top + (bubble.rect.top + offset.second) * scaleY
            val right = displayRect.left + (bubble.rect.right + offset.first) * scaleX
            val bottom = displayRect.top + (bubble.rect.bottom + offset.second) * scaleY
            if (x in left..right && y in top..bottom) {
                return bubble.id
            }
        }
        return null
    }

    private fun drawBubble(canvas: Canvas, text: String, rect: RectF) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val padded = RectF(rect)
        val pad = (min(rect.width(), rect.height()) * 0.08f).coerceAtLeast(6f)
        padded.inset(pad, pad)
        canvas.drawRoundRect(padded, 6f, 6f, fillPaint)
        canvas.drawRoundRect(padded, 6f, 6f, strokePaint)
        drawTextInRect(canvas, text, padded)
    }

    private fun drawTextInRect(canvas: Canvas, text: String, rect: RectF) {
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

    private fun buildLayout(text: String, width: Int, textSize: Float): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }
}
