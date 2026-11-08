package com.manga.translate.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Scales the reading viewport as one surface; segment Views stay bounded and recyclable. */
class WebtoonRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    internal var contentScale = 1f
        private set
    internal var contentOffsetX = 0f
        private set
    var zoomEnabled = true
        set(value) {
            field = value
            if (!value) setContentScale(1f, 0f, 0f)
        }
    private val baseBottomPadding = paddingBottom
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false
    private var pinching = false
    private val detector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setContentScale(contentScale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    internal fun setContentScale(scale: Float, focusX: Float, focusY: Float) {
        val next = scale.coerceIn(1f, 3f)
        val old = contentScale
        contentOffsetX = (focusX - (focusX - contentOffsetX) * next / old)
            .coerceIn(width * (1f - next), 0f)
        contentScale = next
        // Supply enough scroll range to reach the final pixel through the scaled viewport.
        setPadding(paddingLeft, paddingTop, paddingRight,
            baseBottomPadding + (height * (1f - 1f / next)).roundToInt())
        scrollBy(0, (focusY / old - focusY / next).roundToInt())
        invalidate()
    }

    fun toggleZoomAtContentPoint(x: Float, y: Float) {
        if (zoomEnabled) setContentScale(if (contentScale > 1.01f) 1f else 2f,
            x * contentScale + contentOffsetX, y * contentScale)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(contentOffsetX, 0f)
        canvas.scale(contentScale, contentScale)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private fun dispatchMapped(event: MotionEvent, cancel: Boolean = false): Boolean {
        val mapped = MotionEvent.obtain(event)
        return try {
            if (cancel) mapped.action = MotionEvent.ACTION_CANCEL
            val inverse = Matrix().apply {
                setTranslate(-contentOffsetX, 0f)
                postScale(1f / contentScale, 1f / contentScale)
            }
            mapped.transform(inverse)
            super.dispatchTouchEvent(mapped)
        } finally { mapped.recycle() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!zoomEnabled) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x; downY = event.y
            lastX = event.x; lastY = event.y
            panning = false; pinching = false
        }
        if (event.pointerCount > 1 && !pinching) {
            dispatchMapped(event, cancel = true)
            stopScroll()
            pinching = true
        }
        detector.onTouchEvent(event)
        if (pinching) return true // Start a fresh scroll gesture after all fingers are lifted.
        if (contentScale > 1.01f && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (!panning && (abs(event.x - downX) > slop || abs(event.y - downY) > slop)) {
                dispatchMapped(event, cancel = true)
                panning = true
            }
            if (panning) {
                contentOffsetX = (contentOffsetX + event.x - lastX)
                    .coerceIn(width * (1f - contentScale), 0f)
                scrollBy(0, ((lastY - event.y) / contentScale).roundToInt())
                invalidate()
            }
            lastX = event.x; lastY = event.y
        }
        return if (panning) true else dispatchMapped(event)
    }
}
