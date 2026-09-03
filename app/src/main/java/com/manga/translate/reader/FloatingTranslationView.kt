package com.manga.translate.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.withClip
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationResult
import com.manga.translate.rendering.BubbleColorSampler
import com.manga.translate.rendering.BubbleFontResolver
import com.manga.translate.rendering.BubbleShapePaths
import com.manga.translate.rendering.BubbleTextColorResolver
import com.manga.translate.rendering.BubbleTextPlacement
import com.manga.translate.rendering.BubbleTextScaling
import com.manga.translate.rendering.VerticalTextLayout
import com.manga.translate.rendering.VerticalTextLayoutCalculator
import com.manga.translate.rendering.VerticalTextRenderer
import com.manga.translate.rendering.VerticalTextSymbolConverter
import com.manga.translate.settings.NormalBubbleRenderSettings
import com.manga.translate.settings.SettingsStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class FloatingTranslationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private companion object {
        private const val DEFAULT_TEXT_COLOR = 0xFF1B1B1B.toInt()
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_TEXT_COLOR
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
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
    private val resizeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
        pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(12f * resources.displayMetrics.density, 8f * resources.displayMetrics.density),
            0f
        )
    }
    private val resizeHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.FILL
    }
    private val resizeHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeJoin = Paint.Join.ROUND
    }
    private val previewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2600ACC1
        style = Paint.Style.FILL
    }
    private val previewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00ACC1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private var bubbles: List<BubbleTranslation> = emptyList()
    private var imageWidth = 0
    private var imageHeight = 0
    private var currentImageName: String? = null
    private val displayRect = RectF()
    private val bubbleRect = RectF()
    private val bubbleBounds = RectF()
    private val textRect = RectF()
    private val bubblePath = Path()
    private val hitRect = RectF()
    private val deleteRect = RectF()
    private val resizeRect = RectF()
    private val localVisibleRect = Rect()
    private val cullRect = RectF()
    private val offsets = mutableMapOf<BubbleIdentity, Pair<Float, Float>>()
    private val pathCache = mutableMapOf<BubbleIdentity, CachedBubblePath>()
    private var scaleX = 1f
    private var scaleY = 1f
    private var drawInvalidateScheduled = false
    private val drawInvalidateRunnable = Runnable {
        drawInvalidateScheduled = false
        invalidate()
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeThreshold = touchSlop * 2f
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var activeId: BubbleIdentity? = null
    private var verticalLayoutEnabled = true
    private var contentZoomScale = 1f
    private var swipeTriggered = false
    private var longPressTriggered = false
    private var editMode = false
    private var createBubbleMode = false
    private var isCreatingBubble = false
    private var createDownImageX = 0f
    private var createDownImageY = 0f
    private val createDrawingRect = RectF()
    private val createPreviewRect = RectF()
    private var resizeDragId: BubbleIdentity? = null
    private var resizeDragActive = false
    private var resizeDragBaseRect: RectF? = null
    private val resizeDragWorkingRect = RectF()
    private var resizeModeId: BubbleIdentity? = null
    private var resizeModeAlpha = 0f
    private var resizeModeAnimator: android.animation.ValueAnimator? = null
    private var pendingResizeEntry: BubbleIdentity? = null
    private var touchPassthroughEnabled = false
    private var editScrollThroughEnabled = false
    private var editOverflowTop = 0f
    private var editOverflowBottom = 0f
    private var bubbleRenderSettings = SettingsStore(context.applicationContext).loadNormalBubbleRenderSettings()
    private var sourceBitmap: Bitmap? = null
    private var sourceImageFile: java.io.File? = null
    private val bubbleColorCache = mutableMapOf<BubbleIdentity, Int>()
    private val bubbleColorJobs = mutableMapOf<BubbleIdentity, Job>()
    private val bubbleColorSemaphore = Semaphore(2)
    private var bubbleColorGeneration = 0
    private var viewJob = SupervisorJob()
    private var viewScope = CoroutineScope(Dispatchers.Main + viewJob)
    private var typefaceLoadJob: Job? = null
    private var cachedTypeface: Typeface? = null
    private var cachedTypefaceSignature: String? = null
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    private val longPressRunnable = Runnable {
        val identity = activeId ?: return@Runnable
        if (!editMode || dragging) return@Runnable
        longPressTriggered = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onBubbleLongPress?.invoke(identity.bubbleId)
    }
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingSwipeDirection: Int? = null
    private var hadMultiplePointers = false
    private var interactionActive = false

    private data class CachedBubblePath(
        val signature: Long,
        val path: Path,
        val bounds: RectF
    )

    /** A page-local numeric id is not unique while a prior page spills into this overlay. */
    private data class BubbleIdentity(val ownerImageName: String, val bubbleId: Int)

    init {
        isClickable = true
        isFocusable = true
        applyTypefaceSettings()
        loadTypefaceAsync()
    }

    var onOffsetChanged: ((Int, Float, Float) -> Unit)? = null
    var onTap: ((Float) -> Unit)? = null
    var onDoubleTap: ((Float, Float) -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null
    var onTransformTouch: ((MotionEvent) -> Boolean)? = null
    var onBubbleRemove: ((Int) -> Unit)? = null
    var onBubbleTap: ((Int) -> Unit)? = null
    var onBubbleResizeTap: ((Int) -> Unit)? = null
    var onBubbleLongPress: ((Int) -> Unit)? = null
    var onBubbleCreated: ((RectF) -> Unit)? = null
    var onBubbleResized: ((Int, RectF) -> Unit)? = null
    var onResizeModeChanged: ((Int?) -> Unit)? = null

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        typefaceLoadJob?.cancel()
        viewJob.cancel()
        removeCallbacks(longPressRunnable)
        removeCallbacks(drawInvalidateRunnable)
        drawInvalidateScheduled = false
        pathCache.clear()
        cancelBubbleColorJobs()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (viewJob.isCancelled) {
            viewJob = SupervisorJob()
            viewScope = CoroutineScope(Dispatchers.Main + viewJob)
            loadTypefaceAsync()
            requestFullRedraw()
        }
    }

    fun setGestureInteracting(active: Boolean) {
        if (interactionActive == active) return
        interactionActive = active
        requestFullRedraw()
    }

    fun setTranslations(result: TranslationResult?) {
        val previousBubbles = bubbles.associateBy(::bubbleIdentity)
        val samePageGeometry = imageWidth == (result?.width ?: 0) &&
            imageHeight == (result?.height ?: 0) &&
            currentImageName == result?.imageName
        bubbles = result?.bubbles.orEmpty()
        imageWidth = result?.width ?: 0
        imageHeight = result?.height ?: 0
        currentImageName = result?.imageName
        if (samePageGeometry) {
            val currentBubbles = bubbles.associateBy(::bubbleIdentity)
            val validIdentities = currentBubbles.keys
            pathCache.keys.retainAll(validIdentities)
            val reusableColorIdentities = validIdentities.filterTo(hashSetOf()) { identity ->
                val previous = previousBubbles[identity]
                val current = currentBubbles[identity]
                previous != null && current != null &&
                    previous.source == current.source && previous.rect == current.rect
            }
            bubbleColorCache.keys.removeAll { identity ->
                identity !in reusableColorIdentities
            }
            cancelBubbleColorJobsExcept(reusableColorIdentities)
        } else {
            bubbleColorCache.clear()
            pathCache.clear()
            cancelBubbleColorJobs()
        }
        updateScale()
        requestFullRedraw()
    }

    fun setCurrentImageName(imageName: String?) {
        if (currentImageName == imageName) return
        currentImageName = imageName
        bubbleColorCache.clear()
        pathCache.clear()
        cancelBubbleColorJobs()
    }

    fun setSourceBitmap(bitmap: Bitmap?) {
        if (sourceBitmap === bitmap) return
        sourceBitmap = bitmap
        bubbleColorCache.clear()
        cancelBubbleColorJobs()
        requestFullRedraw()
    }

    fun setSourceImageFile(imageFile: java.io.File?) {
        if (sourceImageFile?.absolutePath == imageFile?.absolutePath) return
        sourceImageFile = imageFile
        bubbleColorCache.clear()
        cancelBubbleColorJobs()
        requestFullRedraw()
    }

    fun setDisplayRect(rect: RectF) {
        if (displayRect == rect) return
        displayRect.set(rect)
        pathCache.clear()
        updateScale()
        requestFullRedraw()
    }

    fun setOffsets(values: Map<Int, Pair<Float, Float>>) {
        val nextOffsets = mutableMapOf<BubbleIdentity, Pair<Float, Float>>()
        values.forEach { (bubbleId, offset) ->
            ownedBubbleIdentity(bubbleId)?.let { identity ->
                nextOffsets[identity] = offset
            }
        }
        if (offsets == nextOffsets) return
        offsets.clear()
        offsets.putAll(nextOffsets)
        pathCache.clear()
        requestFullRedraw()
    }

    fun setVerticalLayoutEnabled(enabled: Boolean) {
        verticalLayoutEnabled = enabled
        requestFullRedraw()
    }

    fun setContentZoomScale(scale: Float) {
        val normalized = scale.coerceAtLeast(1f)
        if (kotlin.math.abs(contentZoomScale - normalized) < 0.001f) return
        contentZoomScale = normalized
        requestFullRedraw()
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        if (!enabled) {
            setCreateBubbleMode(false)
        }
        resizeDragId = null
        resizeDragActive = false
        resizeDragBaseRect = null
        resizeDragWorkingRect.setEmpty()
        exitResizeMode(animate = false)
        dragging = false
        activeId = null
        if (interactionActive) {
            interactionActive = false
        }
        longPressTriggered = false
        removeCallbacks(longPressRunnable)
        parent?.requestDisallowInterceptTouchEvent(false)
        requestFullRedraw()
    }

    fun setCreateBubbleMode(enabled: Boolean) {
        if (createBubbleMode == enabled) return
        createBubbleMode = enabled && editMode
        isCreatingBubble = false
        createDrawingRect.setEmpty()
        createPreviewRect.setEmpty()
        exitResizeMode(animate = false)
        requestFullRedraw()
    }

    fun isInCreateBubbleMode(): Boolean = createBubbleMode

    fun getResizeModeBubbleId(): Int? = resizeModeId?.bubbleId

    fun getSelectedBubbleId(): Int? {
        if (!editMode) return null
        return resizeModeId?.bubbleId ?: activeId?.bubbleId
    }

    fun setNormalBubbleRenderSettings(settings: NormalBubbleRenderSettings) {
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
        if (settingsChanged) {
            bubbleColorCache.clear()
        }
        applyTypefaceSettings()
        if (typefaceSourceChanged || cachedTypeface == null) {
            loadTypefaceAsync()
        }
        pathCache.clear()
        requestFullRedraw()
    }

    fun setTouchPassthroughEnabled(enabled: Boolean) {
        touchPassthroughEnabled = enabled
    }

    fun setEditScrollThroughEnabled(enabled: Boolean) {
        editScrollThroughEnabled = enabled
    }

    fun setEditOverflowBounds(top: Float, bottom: Float) {
        val normalizedTop = top.coerceAtLeast(0f)
        val normalizedBottom = bottom.coerceAtLeast(0f)
        if (editOverflowTop == normalizedTop && editOverflowBottom == normalizedBottom) return
        editOverflowTop = normalizedTop
        editOverflowBottom = normalizedBottom
        requestFullRedraw()
    }

    fun getOffsets(): Map<Int, Pair<Float, Float>> {
        val ownerImageName = currentImageName.orEmpty()
        return offsets.mapNotNull { (identity, offset) ->
            identity.takeIf { it.ownerImageName == ownerImageName }?.bubbleId?.let { it to offset }
        }.toMap()
    }

    fun hasBubbleAt(x: Float, y: Float): Boolean {
        if (!editMode) return false
        return findBubbleAt(x, y) != null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isEmpty() && !createBubbleMode) return
        if (imageWidth <= 0 || imageHeight <= 0) return
        // A hardware-accelerated View can reuse this display list when an ancestor scrolls.
        // Normal draws must therefore record the whole page; otherwise bubbles outside a
        // transient visible rect stay missing until another interaction invalidates the View.
        val lightweightDraw = interactionActive || resizeModeAnimator?.isRunning == true
        val cullForInteraction = lightweightDraw
        if (cullForInteraction) {
            updateCullRect(canvas)
        }
        for (bubble in bubbles) {
            // User-created empty frames stay visible outside edit mode so failed OCR
            // does not look like the bubble vanished.
            if (!bubble.hasDisplayText() && !editMode && bubble.source != BubbleSource.MANUAL) continue
            updateBubbleRect(bubbleRect, bubble)
            if (cullForInteraction && !RectF.intersects(bubbleRect, cullRect)) continue
            drawBubble(canvas, bubble, lightweightDraw)
            if (editMode && bubble.isOwnedBy(currentImageName)) {
                drawDeleteIcon(canvas, bubbleRect)
                if (bubble.supportsResizeEditing() &&
                    bubbleIdentity(bubble) != resizeDragId &&
                    bubbleIdentity(bubble) != resizeModeId
                ) {
                    drawResizeIcon(canvas, bubbleRect)
                }
            }
        }
        if (editMode && resizeModeId != null) {
            val targetBubble = bubbles.firstOrNull {
                bubbleIdentity(it) == resizeModeId
            }
            if (targetBubble != null) {
                updateBubbleRect(bubbleRect, targetBubble)
                if (!cullForInteraction || RectF.intersects(bubbleRect, cullRect)) {
                    drawResizeModeHighlight(canvas, bubbleRect)
                    drawResizeModeHandle(canvas, bubbleRect)
                }
            }
        }
        if (editMode && createBubbleMode && !createPreviewRect.isEmpty) {
            canvas.drawRoundRect(createPreviewRect, 8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density, previewFillPaint)
            canvas.drawRoundRect(createPreviewRect, 8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density, previewStrokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (touchPassthroughEnabled && !editMode) {
            return false
        }
        if (createBubbleMode && editMode) {
            return handleCreateTouch(event)
        }
        val allowParentScrollInEditMode = editMode && editScrollThroughEnabled
        val transformHandled = onTransformTouch?.invoke(event) == true
        if (transformHandled) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                dragging = false
                activeId = null
                longPressTriggered = false
                removeCallbacks(longPressRunnable)
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    pendingSwipeDirection = null
                    hadMultiplePointers = true
                }
            }
            parent?.requestDisallowInterceptTouchEvent(true)
            swipeTriggered = true
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                downX = startX
                downY = startY
                lastX = startX
                lastY = startY
                dragging = false
                swipeTriggered = false
                longPressTriggered = false
                pendingSwipeDirection = null
                hadMultiplePointers = false
                removeCallbacks(longPressRunnable)
                if (editMode) {
                    if (resizeModeId != null) {
                        val handleTarget = findResizeTarget(event.x, event.y)
                        if (handleTarget != null && handleTarget == resizeModeId) {
                            activeId = null
                            pendingResizeEntry = null
                            resizeDragId = handleTarget
                            resizeDragActive = false
                            resizeDragBaseRect = bubbles.firstOrNull {
                                bubbleIdentity(it) == handleTarget
                            }?.let { RectF(it.rect) }
                            resizeDragWorkingRect.setEmpty()
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                    val resizeTarget = findResizeTarget(event.x, event.y)
                    if (resizeTarget != null && resizeModeId == null) {
                        pendingResizeEntry = resizeTarget
                        activeId = resizeTarget
                        resizeDragId = null
                        resizeDragActive = false
                        resizeDragBaseRect = null
                        resizeDragWorkingRect.setEmpty()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        postDelayed(longPressRunnable, longPressTimeout)
                        return true
                    }
                    activeId = findBubbleAt(event.x, event.y)
                } else {
                    activeId = null
                }
                if (allowParentScrollInEditMode && activeId == null) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                if (editMode && activeId != null) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    postDelayed(longPressRunnable, longPressTimeout)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val rid = resizeDragId
                if (rid != null) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!resizeDragActive && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        resizeDragActive = true
                        interactionActive = true
                    }
                    if (resizeDragActive) {
                        applyResizeDrag(rid, event.x, event.y)
                    }
                    return true
                }
                if (editMode && activeId != null) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        interactionActive = true
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
                    val incDx = event.x - lastX
                    val incDy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (abs(dx) > swipeThreshold && abs(incDx) >= abs(incDy)) {
                        swipeTriggered = true
                        pendingSwipeDirection = if (dx > 0f) 1 else -1
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                endInteraction()
                parent?.requestDisallowInterceptTouchEvent(false)
                val rid = resizeDragId
                if (rid != null) {
                    if (resizeDragActive && !resizeDragWorkingRect.isEmpty) {
                        onBubbleResized?.invoke(rid.bubbleId, RectF(resizeDragWorkingRect))
                    }
                    resizeDragId = null
                    resizeDragActive = false
                    resizeDragBaseRect = null
                    resizeDragWorkingRect.setEmpty()
                    activeId = null
                    requestFullRedraw()
                    return true
                }
                if (resizeModeId != null) {
                    if (longPressTriggered) {
                        dragging = false
                        activeId = null
                        pendingResizeEntry = null
                        return true
                    }
                    if (!dragging && !swipeTriggered) {
                        val touchedBubble = findBubbleAt(event.x, event.y)
                        if (touchedBubble == null || touchedBubble != resizeModeId) {
                            exitResizeMode()
                        }
                    }
                    return true
                }
                if (longPressTriggered) {
                    dragging = false
                    activeId = null
                    pendingResizeEntry = null
                    return true
                }
                if (!dragging && !swipeTriggered) {
                    if (editMode) {
                        if (pendingResizeEntry != null) {
                            enterResizeMode(pendingResizeEntry!!.bubbleId)
                            pendingResizeEntry = null
                            activeId = null
                            return true
                        }
                        pendingResizeEntry = null
                        val removeId = findRemoveTarget(event.x, event.y)
                        if (removeId != null) {
                            onBubbleRemove?.invoke(removeId.bubbleId)
                            activeId = null
                            return true
                        }
                        val resizeId = findResizeTarget(event.x, event.y)
                        if (resizeId != null) {
                            onBubbleResizeTap?.invoke(resizeId.bubbleId)
                            activeId = null
                            return true
                        }
                        val bubbleId = findBubbleAt(event.x, event.y)
                        if (bubbleId != null) {
                            onBubbleTap?.invoke(bubbleId.bubbleId)
                            activeId = null
                            return true
                        }
                        activeId = null
                        return true
                    }
                    val now = event.eventTime
                    val isDoubleTap = now - lastTapTime <= doubleTapTimeout &&
                        abs(event.x - lastTapX) <= doubleTapSlop &&
                        abs(event.y - lastTapY) <= doubleTapSlop
                    if (isDoubleTap) {
                        lastTapTime = 0L
                        onDoubleTap?.invoke(event.x, event.y)
                    } else {
                        lastTapTime = now
                        lastTapX = event.x
                        lastTapY = event.y
                        onTap?.invoke(event.x)
                        performClick()
                    }
                }
                val direction = pendingSwipeDirection
                if (direction != null && !hadMultiplePointers) {
                    onSwipe?.invoke(direction)
                }
                pendingSwipeDirection = null
                dragging = false
                activeId = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                endInteraction()
                parent?.requestDisallowInterceptTouchEvent(false)
                pendingSwipeDirection = null
                dragging = false
                activeId = null
                resizeDragId = null
                resizeDragActive = false
                resizeDragBaseRect = null
                resizeDragWorkingRect.setEmpty()
                pendingResizeEntry = null
                requestFullRedraw()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun endInteraction() {
        if (!interactionActive) return
        interactionActive = false
        requestFullRedraw()
    }

    private fun updateOffset(dx: Float, dy: Float) {
        if (!editMode) return
        val identity = activeId ?: return
        if (imageWidth <= 0 || imageHeight <= 0) return
        val bubble = bubbles.firstOrNull { bubbleIdentity(it) == identity } ?: return
        val current = offsets[identity] ?: 0f to 0f
        val deltaX = dx / scaleX
        val deltaY = dy / scaleY
        var newX = current.first + deltaX
        var newY = current.second + deltaY
        val overflowFraction = 0.5f
        val bubbleW = bubble.rect.width()
        val bubbleH = bubble.rect.height()
        val minX = -bubble.rect.left - bubbleW * overflowFraction
        val maxX = imageWidth - bubble.rect.right + bubbleW * overflowFraction
        val minY = -bubble.rect.top - max(bubbleH * overflowFraction, editOverflowTop)
        val maxY = imageHeight - bubble.rect.bottom + max(bubbleH * overflowFraction, editOverflowBottom)
        newX = min(max(newX, minX), maxX)
        newY = min(max(newY, minY), maxY)
        offsets[identity] = newX to newY
        pathCache.remove(identity)
        onOffsetChanged?.invoke(identity.bubbleId, newX, newY)
        requestInteractionRedraw()
    }

    private fun screenToImageX(screenX: Float): Float {
        if (scaleX <= 0f) return 0f
        return ((screenX - displayRect.left) / scaleX).coerceIn(0f, imageWidth.toFloat())
    }

    private fun screenToImageY(screenY: Float): Float {
        if (scaleY <= 0f) return 0f
        return ((screenY - displayRect.top) / scaleY).coerceIn(0f, imageHeight.toFloat())
    }

    private fun imageToScreenX(imageX: Float): Float = displayRect.left + imageX * scaleX
    private fun imageToScreenY(imageY: Float): Float = displayRect.top + imageY * scaleY

    private fun handleCreateTouch(event: MotionEvent): Boolean {
        if (imageWidth <= 0 || imageHeight <= 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isCreatingBubble = true
                interactionActive = true
                createDownImageX = screenToImageX(event.x)
                createDownImageY = screenToImageY(event.y)
                createDrawingRect.set(createDownImageX, createDownImageY, createDownImageX, createDownImageY)
                createPreviewRect.setEmpty()
                parent?.requestDisallowInterceptTouchEvent(true)
                requestInteractionRedraw()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isCreatingBubble) return true
                val imageX = screenToImageX(event.x)
                val imageY = screenToImageY(event.y)
                createDrawingRect.set(
                    min(createDownImageX, imageX),
                    min(createDownImageY, imageY),
                    max(createDownImageX, imageX),
                    max(createDownImageY, imageY)
                )
                createPreviewRect.set(
                    imageToScreenX(createDrawingRect.left),
                    imageToScreenY(createDrawingRect.top),
                    imageToScreenX(createDrawingRect.right),
                    imageToScreenY(createDrawingRect.bottom)
                )
                requestInteractionRedraw()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isCreatingBubble) return true
                isCreatingBubble = false
                endInteraction()
                val created = RectF(createDrawingRect)
                createDrawingRect.setEmpty()
                createPreviewRect.setEmpty()
                parent?.requestDisallowInterceptTouchEvent(false)
                requestFullRedraw()
                val minSize = 24f * resources.displayMetrics.density
                val screenWidth = created.width() * scaleX
                val screenHeight = created.height() * scaleY
                if (screenWidth >= minSize && screenHeight >= minSize) {
                    onBubbleCreated?.invoke(created)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isCreatingBubble = false
                endInteraction()
                createDrawingRect.setEmpty()
                createPreviewRect.setEmpty()
                parent?.requestDisallowInterceptTouchEvent(false)
                requestFullRedraw()
                return true
            }
        }
        return true
    }

    private fun applyResizeDrag(identity: BubbleIdentity, screenX: Float, screenY: Float) {
        val base = resizeDragBaseRect ?: return
        val offset = offsets[identity] ?: 0f to 0f
        var newRight = (screenX - displayRect.left) / scaleX - offset.first
        var newBottom = (screenY - displayRect.top) / scaleY - offset.second
        val minImageSize = 20f / scaleX.coerceAtLeast(1f)
        val overflowW = base.width() * 0.5f
        val overflowH = max(base.height() * 0.5f, editOverflowBottom)
        newRight = max(base.left + minImageSize, newRight).coerceAtMost(imageWidth.toFloat() + overflowW)
        newBottom = max(base.top + minImageSize, newBottom).coerceAtMost(imageHeight.toFloat() + overflowH)
        resizeDragWorkingRect.set(base.left, base.top, newRight, newBottom)
        requestInteractionRedraw()
    }

    fun enterResizeMode(bubbleId: Int) {
        if (!editMode) return
        val identity = ownedBubbleIdentity(bubbleId) ?: return
        if (resizeModeId == identity) return
        exitResizeMode(animate = false)
        resizeModeId = identity
        pendingResizeEntry = null
        resizeDragId = null
        resizeDragActive = false
        resizeDragBaseRect = null
        resizeDragWorkingRect.setEmpty()
        onResizeModeChanged?.invoke(identity.bubbleId)
        animateResizeModeEnter()
    }

    fun exitResizeMode() {
        exitResizeMode(animate = true)
    }

    private fun exitResizeMode(animate: Boolean) {
        val wasActive = resizeModeId != null
        resizeModeId = null
        resizeDragId = null
        resizeDragActive = false
        resizeDragBaseRect = null
        resizeDragWorkingRect.setEmpty()
        pendingResizeEntry = null
        if (wasActive) {
            onResizeModeChanged?.invoke(null)
        }
        resizeModeAnimator?.cancel()
        if (animate && wasActive && resizeModeAlpha > 0f) {
            animateResizeModeExit()
        } else {
            resizeModeAlpha = 0f
            requestFullRedraw()
        }
    }

    private fun animateResizeModeEnter() {
        resizeModeAnimator?.cancel()
        resizeModeAnimator = android.animation.ValueAnimator.ofFloat(resizeModeAlpha, 1f).apply {
            duration = 200L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                resizeModeAlpha = it.animatedValue as Float
                requestInteractionRedraw()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    requestFullRedraw()
                }
            })
            start()
        }
    }

    private fun animateResizeModeExit() {
        resizeModeAnimator?.cancel()
        resizeModeAnimator = android.animation.ValueAnimator.ofFloat(resizeModeAlpha, 0f).apply {
            duration = 150L
            interpolator = android.view.animation.AccelerateInterpolator()
            addUpdateListener {
                resizeModeAlpha = it.animatedValue as Float
                requestInteractionRedraw()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    resizeModeAlpha = 0f
                    requestFullRedraw()
                }
            })
            start()
        }
    }

    private fun drawResizeModeHighlight(canvas: Canvas, bubbleRect: RectF) {
        if (resizeModeAlpha <= 0f) return
        resizeHighlightPaint.alpha = (resizeModeAlpha * 255).toInt()
        val inset = resizeHighlightPaint.strokeWidth / 2f
        canvas.drawRect(
            bubbleRect.left + inset,
            bubbleRect.top + inset,
            bubbleRect.right - inset,
            bubbleRect.bottom - inset,
            resizeHighlightPaint
        )
    }

    private fun drawResizeModeHandle(canvas: Canvas, bubbleRect: RectF) {
        if (resizeModeAlpha <= 0f) return
        val density = resources.displayMetrics.density
        val handleSize = (min(bubbleRect.width(), bubbleRect.height()) * 0.28f).coerceIn(18f * density, 28f * density)
        val cornerX = bubbleRect.right
        val cornerY = bubbleRect.bottom
        val cx = cornerX - handleSize * 0.3f
        val cy = cornerY - handleSize * 0.3f
        val scale = 0.6f + 0.4f * resizeModeAlpha
        canvas.withScale(scale, scale, cornerX, cornerY) {
            val path = android.graphics.Path()
            path.moveTo(cx - handleSize, cy)
            path.lineTo(cx, cy)
            path.lineTo(cx, cy - handleSize)
            path.close()
            resizeHandleFillPaint.alpha = (resizeModeAlpha * 255).toInt()
            resizeHandleStrokePaint.alpha = (resizeModeAlpha * 255).toInt()
            drawPath(path, resizeHandleFillPaint)
            drawPath(path, resizeHandleStrokePaint)
        }
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

    private fun bubbleIdentity(bubble: BubbleTranslation): BubbleIdentity {
        return BubbleIdentity(
            ownerImageName = bubble.resolvedOwnerImageName(currentImageName.orEmpty()),
            bubbleId = bubble.id
        )
    }

    private fun ownedBubbleIdentity(bubbleId: Int): BubbleIdentity? {
        return bubbles.firstOrNull {
            it.id == bubbleId && it.isOwnedBy(currentImageName)
        }?.let(::bubbleIdentity)
    }

    private fun findBubbleAt(x: Float, y: Float): BubbleIdentity? {
        if (!editMode || bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            if (!bubble.isOwnedBy(currentImageName)) continue
            updateBubbleRect(hitRect, bubble)
            if (x in hitRect.left..hitRect.right && y in hitRect.top..hitRect.bottom) {
                return bubbleIdentity(bubble)
            }
        }
        return null
    }

    private fun updateBubbleRect(outRect: RectF, bubble: BubbleTranslation) {
        val offset = offsets[bubbleIdentity(bubble)] ?: 0f to 0f
        val rect = if (bubbleIdentity(bubble) == resizeDragId &&
            resizeDragActive && !resizeDragWorkingRect.isEmpty
        ) {
            resizeDragWorkingRect
        } else {
            bubble.rect
        }
        outRect.set(
            displayRect.left + (rect.left + offset.first) * scaleX,
            displayRect.top + (rect.top + offset.second) * scaleY,
            displayRect.left + (rect.right + offset.first) * scaleX,
            displayRect.top + (rect.bottom + offset.second) * scaleY
        )
    }

    private fun findRemoveTarget(x: Float, y: Float): BubbleIdentity? {
        if (!editMode || bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            if (!bubble.isOwnedBy(currentImageName)) continue
            updateBubbleRect(hitRect, bubble)
            if (!hitRect.contains(x, y)) continue
            computeDeleteRect(hitRect, deleteRect)
            if (deleteRect.contains(x, y)) {
                return bubbleIdentity(bubble)
            }
        }
        return null
    }

    private fun findResizeTarget(x: Float, y: Float): BubbleIdentity? {
        if (!editMode || bubbles.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        for (i in bubbles.indices.reversed()) {
            val bubble = bubbles[i]
            if (!bubble.isOwnedBy(currentImageName)) continue
            if (!bubble.supportsResizeEditing()) continue
            updateBubbleRect(hitRect, bubble)
            if (!hitRect.contains(x, y)) continue
            computeResizeRect(hitRect, resizeRect)
            if (resizeRect.contains(x, y)) {
                return bubbleIdentity(bubble)
            }
        }
        return null
    }

    private fun drawBubble(
        canvas: Canvas,
        bubble: BubbleTranslation,
        lightweightDraw: Boolean
    ) {
        val offset = offsets[bubbleIdentity(bubble)] ?: 0f to 0f
        val shrinkPercent = resolveBubbleShrinkPercent(bubble)
        val opacityAlpha = resolveBubbleOpacityAlpha(bubble)
        resolveBubbleFillColor(bubble, offset, opacityAlpha)
        val drawPath = resolveBubblePath(bubble, offset, shrinkPercent) ?: return
        if (bubbleBounds.width() <= 0f || bubbleBounds.height() <= 0f) return
        val pageClip = pageClipRect()
        val startFromTop = BubbleTextPlacement.spillsAcrossPage(
            RectF(
                bubble.rect.left + offset.first,
                bubble.rect.top + offset.second,
                bubble.rect.right + offset.first,
                bubble.rect.bottom + offset.second
            ),
            imageHeight
        )
        // Empty bubbles still need a filled frame in edit mode; text layout can exit early.
        val hasText = bubble.hasDisplayText()
        if (lightweightDraw || !hasText) {
            drawClippedPath(canvas, drawPath, pageClip)
            return
        }
        val textRect = BubbleTextScaling.resolveTextRect(Path(drawPath))
        drawClippedPath(canvas, drawPath, pageClip)
        if (textRect.width() <= 0f || textRect.height() <= 0f) return
        drawClippedContent(canvas, pageClip) {
            drawTextInRect(this, bubble.text, textRect, startFromTop)
        }
    }

    private fun pageClipRect(): RectF {
        return RectF(0f, 0f, width.toFloat(), height.toFloat())
    }

    private fun drawClippedPath(canvas: Canvas, path: Path, pageClip: RectF) {
        drawClippedContent(canvas, pageClip) {
            drawPath(path, fillPaint)
        }
    }

    private fun drawClippedContent(canvas: Canvas, pageClip: RectF, draw: Canvas.() -> Unit) {
        if (pageClip.width() <= 0f || pageClip.height() <= 0f) {
            canvas.draw()
            return
        }
        canvas.withClip(pageClip) {
            draw()
        }
    }

    private fun resolveBubblePath(
        bubble: BubbleTranslation,
        offset: Pair<Float, Float>,
        shrinkPercent: Int
    ): Path? {
        // Resize drag only updates highlight/handle via working rect; fill path stays on
        // the committed bubble geometry until onBubbleResized commits a new rect.
        val signature = pathCacheSignature(bubble, offset, shrinkPercent)
        val identity = bubbleIdentity(bubble)
        val cached = pathCache[identity]
        if (cached != null && cached.signature == signature) {
            bubbleBounds.set(cached.bounds)
            return cached.path
        }
        val path = Path()
        BubbleShapePaths.buildPath(
            outPath = path,
            bubble = bubble,
            sourceWidth = imageWidth,
            sourceHeight = imageHeight,
            originX = displayRect.left,
            originY = displayRect.top,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = offset.first,
            offsetY = offset.second,
            shrinkPercent = shrinkPercent
        )
        path.computeBounds(bubbleBounds, true)
        if (bubbleBounds.width() <= 0f || bubbleBounds.height() <= 0f) {
            pathCache.remove(identity)
            return null
        }
        pathCache[identity] = CachedBubblePath(
            signature = signature,
            path = path,
            bounds = RectF(bubbleBounds)
        )
        return path
    }

    private fun pathCacheSignature(
        bubble: BubbleTranslation,
        offset: Pair<Float, Float>,
        shrinkPercent: Int
    ): Long {
        var hash = bubble.id.toLong()
        hash = hash * 31 + imageWidth
        hash = hash * 31 + imageHeight
        hash = hash * 31 + floatBits(displayRect.left)
        hash = hash * 31 + floatBits(displayRect.top)
        hash = hash * 31 + floatBits(displayRect.right)
        hash = hash * 31 + floatBits(displayRect.bottom)
        hash = hash * 31 + floatBits(scaleX)
        hash = hash * 31 + floatBits(scaleY)
        hash = hash * 31 + floatBits(offset.first)
        hash = hash * 31 + floatBits(offset.second)
        hash = hash * 31 + shrinkPercent
        hash = hash * 31 + floatBits(contentZoomScale)
        hash = hash * 31 + bubble.text.hashCode()
        hash = hash * 31 + floatBits(bubble.rect.left)
        hash = hash * 31 + floatBits(bubble.rect.top)
        hash = hash * 31 + floatBits(bubble.rect.right)
        hash = hash * 31 + floatBits(bubble.rect.bottom)
        val contour = bubble.maskContour
        hash = hash * 31 + (contour?.size ?: 0)
        if (contour != null && contour.isNotEmpty()) {
            hash = hash * 31 + floatBits(contour[0])
            hash = hash * 31 + floatBits(contour[contour.size / 2])
            hash = hash * 31 + floatBits(contour[contour.lastIndex])
        }
        return hash
    }

    private fun floatBits(value: Float): Int = java.lang.Float.floatToIntBits(value)

    private fun updateCullRect(canvas: Canvas) {
        // Interaction redraws are issued every frame, so viewport culling is safe here and
        // avoids rebuilding/drawing every bubble while dragging on a long image.
        val pad = 64f * resources.displayMetrics.density
        if (getLocalVisibleRect(localVisibleRect)) {
            cullRect.set(
                localVisibleRect.left - pad,
                localVisibleRect.top - pad,
                localVisibleRect.right + pad,
                localVisibleRect.bottom + pad
            )
            return
        }
        val clip = canvas.clipBounds
        if (!clip.isEmpty) {
            cullRect.set(clip)
            return
        }
        cullRect.set(0f, 0f, width.toFloat(), height.toFloat())
    }

    private fun requestFullRedraw() {
        if (drawInvalidateScheduled) {
            removeCallbacks(drawInvalidateRunnable)
            drawInvalidateScheduled = false
        }
        invalidate()
    }

    private fun requestInteractionRedraw() {
        if (drawInvalidateScheduled) return
        drawInvalidateScheduled = true
        postOnAnimation(drawInvalidateRunnable)
    }

    private fun resolveBubbleShrinkPercent(bubble: BubbleTranslation): Int {
        return if (bubble.source.usesFreeBubbleShrink) {
            bubbleRenderSettings.freeBubbleShrinkPercent
        } else if (bubble.source == BubbleSource.MANUAL) {
            0
        } else {
            bubbleRenderSettings.shrinkPercent
        }
    }

    private fun resolveBubbleOpacityAlpha(bubble: BubbleTranslation): Int {
        val opacityPercent = if (bubble.source.isFreeBubble) {
            bubbleRenderSettings.freeBubbleOpacityPercent
        } else {
            bubbleRenderSettings.opacityPercent
        }
        return ((opacityPercent.coerceIn(0, 100) / 100f) * 255f).toInt()
    }

    private fun resolveBubbleFillColor(
        bubble: BubbleTranslation,
        offset: Pair<Float, Float>,
        opacityAlpha: Int
    ) {
        val useAutoAdaptColor = if (bubble.source.isFreeBubble) {
            bubbleRenderSettings.autoAdaptFreeBubbleColor
        } else {
            bubbleRenderSettings.autoAdaptBubbleColor
        }
        val bubbleFillColor = if (useAutoAdaptColor) {
            val identity = bubbleIdentity(bubble)
            bubbleColorCache[identity] ?: run {
                val sampleLeft = bubble.rect.left + offset.first
                val sampleTop = bubble.rect.top + offset.second
                val sampleRight = bubble.rect.right + offset.first
                val sampleBottom = bubble.rect.bottom + offset.second
                // Tiled long pages have no full bitmap; region decoding must stay off onDraw.
                val sampled = if (sourceBitmap == null && sourceImageFile != null) {
                    scheduleBubbleColorSample(
                        identity = identity,
                        left = sampleLeft,
                        top = sampleTop,
                        right = sampleRight,
                        bottom = sampleBottom
                    )
                    null
                } else {
                    BubbleColorSampler.sampleBackgroundColor(
                        bitmap = sourceBitmap,
                        imageFile = sourceImageFile,
                        sourceWidth = imageWidth,
                        sourceHeight = imageHeight,
                        left = sampleLeft,
                        top = sampleTop,
                        right = sampleRight,
                        bottom = sampleBottom
                    )
                }
                val color = sampled ?: Color.WHITE
                // Cache even fallback white once a source was available, to avoid per-frame resamples.
                if (sampled != null || sourceBitmap != null || sourceImageFile != null) {
                    bubbleColorCache[identity] = color
                }
                color
            }
        } else {
            Color.WHITE
        }
        fillPaint.color = bubbleFillColor
        textPaint.color = if (useAutoAdaptColor) {
            BubbleTextColorResolver.resolveContrastingTextColor(
                backgroundColor = bubbleFillColor,
                darkTextColor = DEFAULT_TEXT_COLOR
            )
        } else {
            DEFAULT_TEXT_COLOR
        }
        fillPaint.alpha = opacityAlpha
    }

    private fun scheduleBubbleColorSample(
        identity: BubbleIdentity,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        if (bubbleColorJobs.containsKey(identity)) return
        val imageFile = sourceImageFile ?: return
        val sourceWidth = imageWidth
        val sourceHeight = imageHeight
        val generation = bubbleColorGeneration
        bubbleColorJobs[identity] = viewScope.launch {
            val sampled = bubbleColorSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    runCatching {
                        BubbleColorSampler.sampleBackgroundColorFromFile(
                            imageFile = imageFile,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom
                        )
                    }.getOrNull()
                }
            }
            bubbleColorJobs.remove(identity)
            if (generation != bubbleColorGeneration ||
                bubbles.none { bubbleIdentity(it) == identity }
            ) {
                return@launch
            }
            bubbleColorCache[identity] = sampled ?: Color.WHITE
            if (bubbleColorJobs.isEmpty()) {
                requestFullRedraw()
            }
        }
    }

    private fun cancelBubbleColorJobsExcept(validIdentities: Set<BubbleIdentity>) {
        val obsolete = bubbleColorJobs.keys.filterNot { it in validIdentities }
        if (obsolete.isEmpty()) return
        obsolete.forEach { identity -> bubbleColorJobs.remove(identity)?.cancel() }
    }

    private fun cancelBubbleColorJobs() {
        bubbleColorGeneration += 1
        bubbleColorJobs.values.forEach { it.cancel() }
        bubbleColorJobs.clear()
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

    private fun drawTextInRect(
        canvas: Canvas,
        text: String,
        rect: RectF,
        startFromTop: Boolean
    ) {
        if (verticalLayoutEnabled) {
            canvas.withClip(rect) {
                drawVerticalTextInRect(
                    this,
                    VerticalTextSymbolConverter.convert(text),
                    rect,
                    startFromTop
                )
            }
        } else {
            val textSize = resolveHorizontalTextSize(rect, text)
            val layout = buildLayout(text, rect.width().toInt().coerceAtLeast(1), textSize)
            val left = BubbleTextPlacement.horizontalTextLeft(rect, layout.width)
            val top = BubbleTextPlacement.horizontalTextTop(rect, layout.height, startFromTop)
            canvas.withClip(rect) {
                withTranslation(left, top) {
                    layout.draw(this)
                }
            }
        }
    }

    private fun resolveHorizontalTextSize(rect: RectF, text: String): Float {
        return BubbleTextScaling.findAutoHorizontalTextSize(
            text = text,
            maxWidth = rect.width().toInt().coerceAtLeast(1),
            maxHeight = rect.height().toInt().coerceAtLeast(1),
            buildLayout = ::buildLayout,
            layoutFits = BubbleTextScaling::layoutFits
        )
    }

    private fun buildLayout(text: String, width: Int, textSize: Float): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun applyTypefaceSettings() {
        val baseTypeface = cachedTypeface ?: BubbleFontResolver.resolveTypeface(
            context.applicationContext,
            bubbleRenderSettings.font,
            customUrl = bubbleRenderSettings.customFontUrl,
            customFileName = bubbleRenderSettings.customFontFileName,
            tag = "normal"
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
                    "normal"
                )
            }
            cachedTypeface = resolved
            cachedTypefaceSignature = signature
            applyTypefaceSettings()
            requestFullRedraw()
        }
    }

    private fun drawVerticalTextInRect(
        canvas: Canvas,
        text: String,
        rect: RectF,
        startFromTop: Boolean
    ) {
        val maxWidth = rect.width().toInt().coerceAtLeast(1)
        val maxHeight = rect.height().toInt().coerceAtLeast(1)
        val textSize = findDefaultVerticalTextSize(text, maxWidth, maxHeight)
        val layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        VerticalTextRenderer.draw(canvas, text, rect, textPaint, layout, startFromTop)
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
}
