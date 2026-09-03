package com.manga.translate.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.manga.translate.R
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.rendering.BubbleColorSampler
import com.manga.translate.rendering.BubbleFontResolver
import com.manga.translate.rendering.BubbleShapePaths
import com.manga.translate.rendering.BubbleTextColorResolver
import com.manga.translate.rendering.BubbleTextScaling
import com.manga.translate.rendering.VerticalTextLayout
import com.manga.translate.rendering.VerticalTextLayoutCalculator
import com.manga.translate.rendering.VerticalTextRenderer
import com.manga.translate.rendering.VerticalTextSymbolConverter
import com.manga.translate.settings.FloatingBubbleRenderSettings
import com.manga.translate.settings.FloatingBubbleShape
import com.manga.translate.settings.SettingsStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingDetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private companion object {
        private const val DEFAULT_TEXT_COLOR = 0xFF111111.toInt()
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val editBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00ACC1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.8f
    }
    private val deletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2600ACC1
        style = Paint.Style.FILL
    }
    private val previewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00ACC1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_TEXT_COLOR
        textSize = resources.displayMetrics.density * resources.configuration.fontScale * 12f
    }
    private val sourceRect = RectF()
    private val tempRect = RectF()
    private val shapeRect = RectF()
    private val deleteRect = RectF()
    private val drawingRect = RectF()
    private val bubblePath = Path()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var bubbles: List<BubbleTranslation> = emptyList()
    private var bubbleRenderSettings = SettingsStore(context.applicationContext).loadFloatingBubbleRenderSettings()
    private var bubbleOpacity = bubbleRenderSettings.opacityPercent / 100f
    private var sourceBitmap: Bitmap? = null
    private val bubbleColorCache = mutableMapOf<Int, Int>()
    private val viewJob = SupervisorJob()
    private val viewScope = CoroutineScope(Dispatchers.Main + viewJob)
    private var typefaceLoadJob: Job? = null
    private var cachedTypeface: Typeface? = null
    private var cachedTypefaceSignature: String? = null
    private var editMode = false
    private var createBubbleMode = false
    private val touchSlop = 3f * resources.displayMetrics.density
    private val minCreateSize = 24f * resources.displayMetrics.density
    private var draggingBubbleId: Int? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var isDrawing = false
    private var dirty = false
    private var longPressBubbleId: Int? = null
    private var longPressTriggered = false
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressDeleteRunnable = Runnable {
        val id = longPressBubbleId ?: return@Runnable
        longPressTriggered = true
        draggingBubbleId = null
        isDragging = false
        onBubbleDelete?.invoke(id)
        setDirty(true)
    }

    var onBubblesChanged: ((List<BubbleTranslation>) -> Unit)? = null
    var onBubbleDelete: ((Int) -> Unit)? = null
    var onManualBubbleCreated: ((RectF) -> Unit)? = null
    var onEditDirtyChanged: ((Boolean) -> Unit)? = null
    var onCreateBubbleTouchActiveChanged: ((Boolean) -> Unit)? = null

    init {
        applyTypefaceSettings()
        loadTypefaceAsync()
        applyBubbleOpacity()
    }

    fun setTranslationSession(sourceWidth: Int, sourceHeight: Int, bubbles: List<BubbleTranslation>) {
        this.sourceWidth = sourceWidth.coerceAtLeast(1)
        this.sourceHeight = sourceHeight.coerceAtLeast(1)
        this.bubbles = bubbles
        bubbleColorCache.clear()
        draggingBubbleId = null
        isDragging = false
        cancelLongPressDelete()
        invalidate()
    }

    fun setSourceBitmap(bitmap: Bitmap?) {
        if (sourceBitmap === bitmap) return
        sourceBitmap = bitmap
        bubbleColorCache.clear()
        invalidate()
    }

    fun clearDetections() {
        bubbles = emptyList()
        bubbleColorCache.clear()
        draggingBubbleId = null
        isDragging = false
        isDrawing = false
        drawingRect.setEmpty()
        cancelLongPressDelete()
        setDirty(false)
        invalidate()
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        if (!enabled) {
            setCreateBubbleMode(false)
            draggingBubbleId = null
            isDragging = false
            isDrawing = false
            drawingRect.setEmpty()
            cancelLongPressDelete()
            setDirty(false)
        }
        invalidate()
    }

    fun setCreateBubbleMode(enabled: Boolean) {
        if (createBubbleMode == enabled) return
        createBubbleMode = enabled && editMode
        draggingBubbleId = null
        isDragging = false
        isDrawing = false
        drawingRect.setEmpty()
        cancelLongPressDelete()
        onCreateBubbleTouchActiveChanged?.invoke(false)
        invalidate()
    }

    fun setFloatingBubbleRenderSettings(settings: FloatingBubbleRenderSettings) {
        val previousSettings = bubbleRenderSettings
        val settingsChanged = previousSettings != settings
        val nextTypefaceSignature = BubbleFontResolver.resolveTypefaceSignature(
            context.applicationContext,
            settings.font,
            settings.customFontFileName
        )
        val typefaceSourceChanged = cachedTypefaceSignature != nextTypefaceSignature
        if (!settingsChanged && !typefaceSourceChanged) return
        bubbleRenderSettings = settings
        if (typefaceSourceChanged) {
            cachedTypeface = null
        }
        cachedTypefaceSignature = nextTypefaceSignature
        bubbleOpacity = settings.opacityPercent / 100f
        if (settingsChanged) {
            bubbleColorCache.clear()
        }
        applyTypefaceSettings()
        if (typefaceSourceChanged || cachedTypeface == null) {
            loadTypefaceAsync()
        }
        applyBubbleOpacity()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = when {
            editMode -> handleEditTouch(event)
            else -> false
        }
        if (handled && event.actionMasked == MotionEvent.ACTION_UP && !isDragging) {
            performClick()
        }
        return handled
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun handleEditTouch(event: MotionEvent): Boolean {
        val sourceX = event.x / scaleX()
        val sourceY = event.y / scaleY()
        if (createBubbleMode) {
            return handleCreateTouch(event, sourceX, sourceY)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDragging = false
                val bubble = findBubbleAt(sourceX, sourceY)
                if (bubble != null) {
                    draggingBubbleId = bubble.id
                    dragOffsetX = sourceX - bubble.rect.left
                    dragOffsetY = sourceY - bubble.rect.top
                    scheduleLongPressDelete(bubble.id)
                } else {
                    draggingBubbleId = null
                    cancelLongPressDelete()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val id = draggingBubbleId ?: return true
                if (!isDragging) {
                    isDragging = abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop
                    if (isDragging) {
                        cancelLongPressDelete()
                    }
                }
                if (isDragging) {
                    updateBubblePosition(id, sourceX, sourceY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val id = draggingBubbleId
                val didLongPressDelete = longPressTriggered
                cancelLongPressDelete()
                if (didLongPressDelete) {
                    return true
                }
                if (!isDragging && id != null) {
                    val bubble = bubbles.firstOrNull { it.id == id }
                    if (bubble != null) {
                        computeDeleteRect(bubble.rect, deleteRect)
                        if (deleteRect.contains(sourceX, sourceY)) {
                            onBubbleDelete?.invoke(id)
                            setDirty(true)
                        }
                    }
                }
                draggingBubbleId = null
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPressDelete()
                draggingBubbleId = null
                isDragging = false
                return true
            }
        }
        return true
    }

    private fun handleCreateTouch(event: MotionEvent, sourceX: Float, sourceY: Float): Boolean {
        val clampedX = sourceX.coerceIn(0f, sourceWidth.toFloat())
        val clampedY = sourceY.coerceIn(0f, sourceHeight.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                downX = clampedX
                downY = clampedY
                drawingRect.set(clampedX, clampedY, clampedX, clampedY)
                onCreateBubbleTouchActiveChanged?.invoke(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return true
                updateDrawingRect(clampedX, clampedY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDrawing) return true
                updateDrawingRect(clampedX, clampedY)
                val created = RectF(drawingRect)
                isDrawing = false
                drawingRect.setEmpty()
                onCreateBubbleTouchActiveChanged?.invoke(false)
                invalidate()
                if (created.width() * scaleX() >= minCreateSize &&
                    created.height() * scaleY() >= minCreateSize
                ) {
                    onManualBubbleCreated?.invoke(created)
                    setDirty(true)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                drawingRect.setEmpty()
                onCreateBubbleTouchActiveChanged?.invoke(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updateDrawingRect(sourceX: Float, sourceY: Float) {
        drawingRect.set(
            min(downX, sourceX),
            min(downY, sourceY),
            max(downX, sourceX),
            max(downY, sourceY)
        )
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
        val overflowFraction = 0.5f
        val minLeft = -width * overflowFraction
        val maxLeft = sourceWidth.toFloat() - width * (1f - overflowFraction)
        val minTop = -height * overflowFraction
        val maxTop = sourceHeight.toFloat() - height * (1f - overflowFraction)
        val newLeft = (sourceX - dragOffsetX).coerceIn(minLeft, maxLeft)
        val newTop = (sourceY - dragOffsetY).coerceIn(minTop, maxTop)
        val deltaX = newLeft - bubble.rect.left
        val deltaY = newTop - bubble.rect.top
        val newRect = RectF(newLeft, newTop, newLeft + width, newTop + height)
        mutable[index] = bubble.copy(
            rect = newRect,
            maskContour = BubbleShapePaths.translateMaskContour(
                contour = bubble.maskContour,
                deltaX = deltaX,
                deltaY = deltaY,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )
        )
        bubbles = mutable
        bubbleColorCache.remove(id)
        onBubblesChanged?.invoke(mutable)
        setDirty(true)
        invalidate()
    }

    private fun scheduleLongPressDelete(id: Int) {
        cancelLongPressDelete()
        longPressBubbleId = id
        longPressTriggered = false
        postDelayed(longPressDeleteRunnable, longPressTimeoutMs)
    }

    private fun cancelLongPressDelete() {
        removeCallbacks(longPressDeleteRunnable)
        longPressBubbleId = null
        longPressTriggered = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isNotEmpty()) {
            drawBubbles(canvas)
        }
        if (editMode && createBubbleMode && !drawingRect.isEmpty) {
            updateDisplayRect(drawingRect, sourceRect)
            if (bubbleRenderSettings.shape == FloatingBubbleShape.INSCRIBED_ELLIPSE) {
                canvas.drawOval(sourceRect, previewPaint)
                canvas.drawOval(sourceRect, previewStrokePaint)
            } else {
                canvas.drawRoundRect(sourceRect, cornerRadius(), cornerRadius(), previewPaint)
                canvas.drawRoundRect(sourceRect, cornerRadius(), cornerRadius(), previewStrokePaint)
            }
        }
    }

    private fun drawBubbles(canvas: Canvas) {
        val radius = cornerRadius()
        for (bubble in bubbles) {
            val rect = bubble.rect
            resolveBubbleDisplayPath(bubble, radius)
            bubblePath.computeBounds(sourceRect, true)
            if (sourceRect.width() < 2f || sourceRect.height() < 2f) continue
            applyBubbleFillColor(bubble)
            canvas.drawPath(bubblePath, boxPaint)
            if (editMode) {
                canvas.drawPath(bubblePath, editBorderPaint)
                computeDeleteRect(rect, tempRect)
                updateDisplayRect(tempRect, tempRect)
                drawDeleteIcon(canvas, tempRect)
            }
            val text = bubble.text.ifBlank { context.getString(R.string.floating_bubble_placeholder) }
            val textRect = BubbleTextScaling.resolveTextRect(bubblePath)
            if (textRect.width() <= 0f || textRect.height() <= 0f) continue
            if (bubbleRenderSettings.useHorizontalText) {
                val textSize = BubbleTextScaling.findAutoHorizontalTextSize(
                    text = text,
                    maxWidth = textRect.width().toInt().coerceAtLeast(1),
                    maxHeight = textRect.height().toInt().coerceAtLeast(1),
                    buildLayout = { content, width, ts ->
                        buildLayout(text = content, paint = TextPaint(textPaint).apply { this.textSize = ts }, availableWidth = width)
                    },
                    layoutFits = BubbleTextScaling::layoutFits
                )
                val textLayout = buildLayout(
                    text = text,
                    paint = TextPaint(textPaint).apply { this.textSize = textSize },
                    availableWidth = textRect.width().toInt().coerceAtLeast(1)
                )
                canvas.withClip(textRect) {
                    withTranslation(textRect.centerX(), textRect.centerY()) {
                        translate(-textLayout.width / 2f, -textLayout.height / 2f)
                        textLayout.draw(this)
                    }
                }
            } else {
                canvas.withClip(textRect) {
                    drawVerticalTextInRect(
                        this,
                        VerticalTextSymbolConverter.convert(text),
                        textRect
                    )
                }
            }
        }
    }

    /**
     * Builds [bubblePath] in view coordinates.
     * Balloon detections with maskContour use the shared contour path when available;
     * free-text / manual boxes keep floating shape settings (rect / ellipse).
     *
     * IMPORTANT: This is an interactive **editing overlay** — shrinkPercent is hardcoded to 0
     * so users see the full detection bounds they're manipulating, NOT the final shrunk rendering.
     * This intentionally differs from BubbleRenderer / FloatingTranslationView which apply
     * user settings for final display.
     */
    private fun resolveBubbleDisplayPath(bubble: BubbleTranslation, radius: Float) {
        val contour = bubble.maskContour
        if (contour != null && contour.size >= 6 && sourceWidth > 0 && sourceHeight > 0) {
            BubbleShapePaths.buildPath(
                outPath = bubblePath,
                bubble = bubble,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                originX = 0f,
                originY = 0f,
                scaleX = scaleX(),
                scaleY = scaleY(),
                shrinkPercent = 0  // No shrink in edit mode — show full detection bounds
            )
            applySizeAdjustToPath(bubblePath)
            return
        }
        updateDisplayRect(bubble.rect, sourceRect)
        bubblePath.reset()
        if (bubbleRenderSettings.shape == FloatingBubbleShape.INSCRIBED_ELLIPSE) {
            bubblePath.addOval(sourceRect, Path.Direction.CW)
        } else {
            bubblePath.addRoundRect(sourceRect, radius, radius, Path.Direction.CW)
        }
    }

    private fun applySizeAdjustToPath(path: Path) {
        val percent = bubbleRenderSettings.sizeAdjustPercent.coerceIn(-90, 90)
        if (percent == 0) return
        path.computeBounds(shapeRect, true)
        if (shapeRect.width() <= 0f || shapeRect.height() <= 0f) return
        val scale = (100f + percent) / 100f
        val matrix = Matrix()
        matrix.setScale(scale, scale, shapeRect.centerX(), shapeRect.centerY())
        path.transform(matrix)
    }

    private fun drawDeleteIcon(canvas: Canvas, rect: RectF) {
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, deletePaint)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, deletePaint)
    }

    private fun computeDeleteRect(source: RectF, outRect: RectF) {
        val size = (min(source.width(), source.height()) * 0.22f).coerceIn(18f, 42f)
        val padding = (size * 0.2f).coerceAtLeast(4f)
        val left = (source.right - size - padding).coerceAtLeast(source.left)
        val top = (source.top + padding).coerceAtLeast(source.top)
        val right = (left + size).coerceAtMost(source.right)
        val bottom = (top + size).coerceAtMost(source.bottom)
        outRect.set(left, top, right, bottom)
    }

    private fun buildLayout(text: String, paint: TextPaint, availableWidth: Int): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, availableWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun applyBubbleOpacity() {
        boxPaint.color = Color.argb((bubbleOpacity * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        textPaint.color = DEFAULT_TEXT_COLOR
    }

    private fun applyTypefaceSettings() {
        val baseTypeface = cachedTypeface ?: BubbleFontResolver.resolveTypeface(
            context.applicationContext,
            bubbleRenderSettings.font,
            customUrl = bubbleRenderSettings.customFontUrl,
            customFileName = bubbleRenderSettings.customFontFileName,
            tag = "floating"
        )
        val style = if (bubbleRenderSettings.isBold) Typeface.BOLD else Typeface.NORMAL
        textPaint.typeface = Typeface.create(baseTypeface, style)
    }

    private fun loadTypefaceAsync() {
        val font = bubbleRenderSettings.font
        val url = bubbleRenderSettings.customFontUrl
        val customFileName = bubbleRenderSettings.customFontFileName
        val signature = BubbleFontResolver.resolveTypefaceSignature(
            context.applicationContext,
            font,
            customFileName
        )
        typefaceLoadJob?.cancel()
        typefaceLoadJob = viewScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                BubbleFontResolver.ensureTypeface(
                    context.applicationContext,
                    font,
                    url,
                    customFileName,
                    "floating"
                )
            }
            cachedTypeface = resolved
            cachedTypefaceSignature = signature
            applyTypefaceSettings()
            invalidate()
        }
    }

    private fun applyBubbleFillColor(bubble: BubbleTranslation) {
        if (!bubbleRenderSettings.autoAdaptBubbleColor) {
            applyBubbleOpacity()
            return
        }
        val alpha = (bubbleOpacity * 255f).toInt().coerceIn(0, 255)
        val bubbleFillColor = bubbleColorCache.getOrPut(bubble.id) {
            val bmp = sourceBitmap
            val sampleScaleX = if (sourceWidth > 0 && bmp != null) {
                bmp.width.toFloat() / sourceWidth.toFloat()
            } else {
                1f
            }
            val sampleScaleY = if (sourceHeight > 0 && bmp != null) {
                bmp.height.toFloat() / sourceHeight.toFloat()
            } else {
                1f
            }
            BubbleColorSampler.sampleBackgroundColor(
                bmp,
                bubble.rect.left * sampleScaleX,
                bubble.rect.top * sampleScaleY,
                bubble.rect.right * sampleScaleX,
                bubble.rect.bottom * sampleScaleY
            ) ?: Color.WHITE
        }
        textPaint.color = BubbleTextColorResolver.resolveContrastingTextColor(
            backgroundColor = bubbleFillColor,
            darkTextColor = DEFAULT_TEXT_COLOR
        )
        boxPaint.color = Color.argb(
            alpha,
            Color.red(bubbleFillColor),
            Color.green(bubbleFillColor),
            Color.blue(bubbleFillColor)
        )
    }

    private fun updateDisplayRect(source: RectF, outRect: RectF) {
        val adjustScale = (100f + bubbleRenderSettings.sizeAdjustPercent.coerceIn(-90, 90)) / 100f
        val centerX = source.centerX()
        val centerY = source.centerY()
        val adjustedHalfWidth = (source.width() * adjustScale / 2f).coerceAtLeast(1f)
        val adjustedHalfHeight = (source.height() * adjustScale / 2f).coerceAtLeast(1f)
        outRect.set(
            (centerX - adjustedHalfWidth) * scaleX(),
            (centerY - adjustedHalfHeight) * scaleY(),
            (centerX + adjustedHalfWidth) * scaleX(),
            (centerY + adjustedHalfHeight) * scaleY()
        )
    }

    private fun drawVerticalTextInRect(canvas: Canvas, text: String, rect: RectF) {
        val maxWidth = rect.width().toInt().coerceAtLeast(1)
        val maxHeight = rect.height().toInt().coerceAtLeast(1)
        val textSize = findDefaultVerticalTextSize(text, maxWidth, maxHeight)
        val layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        textPaint.textSize = textSize
        VerticalTextRenderer.draw(canvas, text, rect, textPaint, layout)
    }

    private fun findDefaultVerticalTextSize(
        text: String,
        maxWidth: Int,
        maxHeight: Int
    ): Float {
        return BubbleTextScaling.findLargestFittingTextSize(maxWidth, maxHeight) { textSize ->
            val layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
            layout.columnWidth > 0f && layout.lineHeight > 0f && layout.fits
        }
    }

    private fun buildVerticalLayout(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        textSize: Float
    ): VerticalTextLayout {
        return VerticalTextLayoutCalculator.build(textPaint, text, maxWidth, maxHeight, textSize)
    }

    private fun setDirty(value: Boolean) {
        if (dirty == value) return
        dirty = value
        onEditDirtyChanged?.invoke(value)
    }

    private fun scaleX(): Float = width.toFloat().coerceAtLeast(1f) / sourceWidth.coerceAtLeast(1)

    private fun scaleY(): Float = height.toFloat().coerceAtLeast(1f) / sourceHeight.coerceAtLeast(1)

    private fun cornerRadius(): Float = resources.displayMetrics.density * 8f

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        typefaceLoadJob?.cancel()
        viewJob.cancel()
    }

}
