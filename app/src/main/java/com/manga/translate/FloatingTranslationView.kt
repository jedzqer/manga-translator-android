package com.manga.translate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
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
    private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val resizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }

    private var bubbles: List<BubbleTranslation> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0
    private val displayRect = RectF()
    private val bubbleRect = RectF()
    private val hitRect = RectF()
    private val deleteRect = RectF()
    private val resizeRect = RectF()
    private val offsets = mutableMapOf<Int, Pair<Float, Float>>()
    private var scaleX = 1f
    private var scaleY = 1f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeThreshold = touchSlop * 2f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var activeId: Int? = null
    private var verticalLayoutEnabled = true
    private var swipeTriggered = false
    private var longPressTriggered = false
    private var editMode = false
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressRunnable = Runnable {
        val id = activeId ?: return@Runnable
        if (!editMode || dragging) return@Runnable
        longPressTriggered = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onBubbleLongPress?.invoke(id)
    }

    var onOffsetChanged: ((Float, Float) -> Unit)? = null
    var onTap: ((Float) -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null
    var onTransformTouch: ((MotionEvent) -> Boolean)? = null
    var onBubbleRemove: ((Int) -> Unit)? = null
    var onBubbleTap: ((Int) -> Unit)? = null
    var onBubbleResizeTap: ((Int) -> Unit)? = null
    var onBubbleLongPress: ((Int) -> Unit)? = null

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

    fun setVerticalLayoutEnabled(enabled: Boolean) {
        verticalLayoutEnabled = enabled
        invalidate()
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        dragging = false
        activeId = null
        longPressTriggered = false
        removeCallbacks(longPressRunnable)
        invalidate()
    }

    fun getOffsets(): Map<Int, Pair<Float, Float>> {
        return offsets.toMap()
    }

    fun hasBubbleAt(x: Float, y: Float): Boolean {
        if (!editMode) return false
        return findBubbleAt(x, y) != null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return
        for (bubble in bubbles) {
            if (bubble.text.isBlank() && !editMode) continue
            updateBubbleRect(bubbleRect, bubble)
            drawBubble(canvas, bubble.text, bubbleRect)
            if (editMode) {
                drawDeleteIcon(canvas, bubbleRect)
                drawResizeIcon(canvas, bubbleRect)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val transformHandled = onTransformTouch?.invoke(event) == true
        if (transformHandled) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                dragging = false
                activeId = null
                longPressTriggered = false
                removeCallbacks(longPressRunnable)
            }
            swipeTriggered = true
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                downX = startX
                downY = startY
                activeId = if (editMode) findBubbleAt(event.x, event.y) else null
                dragging = false
                swipeTriggered = false
                longPressTriggered = false
                removeCallbacks(longPressRunnable)
                if (editMode && activeId != null) {
                    postDelayed(longPressRunnable, longPressTimeout)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (editMode && activeId != null) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        updateOffset(dx, dy)
                        downX = event.x
                        downY = event.y
                    }
                } else if (!swipeTriggered) {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.3f) {
                        swipeTriggered = true
                        onSwipe?.invoke(if (dx > 0f) 1 else -1)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (longPressTriggered) {
                    dragging = false
                    activeId = null
                    return true
                }
                if (!dragging && !swipeTriggered) {
                    if (editMode) {
                        val removeId = findRemoveTarget(event.x, event.y)
                        if (removeId != null) {
                            onBubbleRemove?.invoke(removeId)
                            activeId = null
                            return true
                        }
                        val resizeId = findResizeTarget(event.x, event.y)
                        if (resizeId != null) {
                            onBubbleResizeTap?.invoke(resizeId)
                            activeId = null
                            return true
                        }
                        val bubbleId = findBubbleAt(event.x, event.y)
                        if (bubbleId != null) {
                            onBubbleTap?.invoke(bubbleId)
                            activeId = null
                            return true
                        }
                        activeId = null
                        return true
                    }
                    onTap?.invoke(event.x)
                    performClick()
                }
                dragging = false
                activeId = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
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
        if (!editMode) return
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
        if (!editMode || bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            updateBubbleRect(hitRect, bubble)
            if (x in hitRect.left..hitRect.right && y in hitRect.top..hitRect.bottom) {
                return bubble.id
            }
        }
        return null
    }

    private fun updateBubbleRect(outRect: RectF, bubble: BubbleTranslation) {
        val offset = offsets[bubble.id] ?: 0f to 0f
        outRect.set(
            displayRect.left + (bubble.rect.left + offset.first) * scaleX,
            displayRect.top + (bubble.rect.top + offset.second) * scaleY,
            displayRect.left + (bubble.rect.right + offset.first) * scaleX,
            displayRect.top + (bubble.rect.bottom + offset.second) * scaleY
        )
    }

    private fun findRemoveTarget(x: Float, y: Float): Int? {
        if (!editMode || bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            updateBubbleRect(hitRect, bubble)
            if (!hitRect.contains(x, y)) continue
            computeDeleteRect(hitRect, deleteRect)
            if (deleteRect.contains(x, y)) {
                return bubble.id
            }
        }
        return null
    }

    private fun findResizeTarget(x: Float, y: Float): Int? {
        if (!editMode || bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            updateBubbleRect(hitRect, bubble)
            if (!hitRect.contains(x, y)) continue
            computeResizeRect(hitRect, resizeRect)
            if (resizeRect.contains(x, y)) {
                return bubble.id
            }
        }
        return null
    }

    private fun drawBubble(canvas: Canvas, text: String, rect: RectF) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val pad = (min(rect.width(), rect.height()) * 0.08f).coerceAtLeast(6f)
        val textRect = RectF(rect)
        textRect.inset(pad, pad)
        canvas.drawRoundRect(rect, 6f, 6f, fillPaint)
        canvas.drawRoundRect(rect, 6f, 6f, strokePaint)
        drawTextInRect(canvas, text, textRect)
    }

    private fun drawDeleteIcon(canvas: Canvas, rect: RectF) {
        computeDeleteRect(rect, deleteRect)
        if (deleteRect.width() <= 0f || deleteRect.height() <= 0f) return
        canvas.drawLine(deleteRect.left, deleteRect.top, deleteRect.right, deleteRect.bottom, deletePaint)
        canvas.drawLine(deleteRect.right, deleteRect.top, deleteRect.left, deleteRect.bottom, deletePaint)
    }

    private fun drawResizeIcon(canvas: Canvas, rect: RectF) {
        computeResizeRect(rect, resizeRect)
        if (resizeRect.width() <= 0f || resizeRect.height() <= 0f) return
        val centerX = resizeRect.centerX()
        val centerY = resizeRect.centerY()
        val half = resizeRect.width() * 0.35f
        canvas.drawLine(centerX - half, centerY, centerX + half, centerY, resizePaint)
        canvas.drawLine(centerX, centerY - half, centerX, centerY + half, resizePaint)
    }

    private fun computeDeleteRect(source: RectF, outRect: RectF) {
        val density = resources.displayMetrics.density
        val size = (min(source.width(), source.height()) * 0.22f).coerceIn(8f * density, 16f * density)
        val padding = (size * 0.2f).coerceAtLeast(2f * density)
        val left = (source.right - size - padding).coerceAtLeast(source.left)
        val top = (source.top + padding).coerceAtLeast(source.top)
        val right = (left + size).coerceAtMost(source.right)
        val bottom = (top + size).coerceAtMost(source.bottom)
        outRect.set(left, top, right, bottom)
    }

    private fun computeResizeRect(source: RectF, outRect: RectF) {
        val density = resources.displayMetrics.density
        val size = (min(source.width(), source.height()) * 0.22f).coerceIn(8f * density, 16f * density)
        val padding = (size * 0.2f).coerceAtLeast(2f * density)
        val right = (source.right - padding).coerceAtMost(source.right)
        val bottom = (source.bottom - padding).coerceAtMost(source.bottom)
        val left = (right - size).coerceAtLeast(source.left)
        val top = (bottom - size).coerceAtLeast(source.top)
        outRect.set(left, top, right, bottom)
    }

    private fun drawTextInRect(canvas: Canvas, text: String, rect: RectF) {
        if (verticalLayoutEnabled) {
            drawVerticalTextInRect(canvas, text, rect)
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
