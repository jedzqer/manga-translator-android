package com.manga.translate.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Keeps the reader's ImageView coordinate contract while giving SSIV a screen-sized viewport.
 * A webtoon item can be hundreds of screens tall; passing its full bounds to SSIV would decode
 * all of those screens. The child instead follows the local visible rectangle, with the reader's
 * matrix translated into that rectangle. SSIV owns the base layer and all refinement tiles.
 */
class ReadingRegionImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val image = AppCompatImageView(context, attrs)
    private var tiled: TileView? = null
    private var source: ReadingRegionImageSource? = null
    private val viewport = Rect()
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { invalidate() }

    init {
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWillNotDraw(false)
    }

    var scaleType: ImageView.ScaleType
        get() = image.scaleType
        set(value) { image.scaleType = value; invalidate() }
    var adjustViewBounds: Boolean
        get() = image.adjustViewBounds
        set(value) { image.adjustViewBounds = value; requestLayout() }
    var imageMatrix: Matrix
        get() = image.imageMatrix
        set(value) { image.imageMatrix = value; invalidate() }
    val drawable: Drawable? get() = image.drawable

    fun setImageDrawable(drawable: Drawable?) {
        image.setImageDrawable(drawable)
        requestLayout()
        invalidate()
    }

    fun setRegionSource(value: ReadingRegionImageSource?) {
        if (source == value) return
        releaseTiles()
        source = value
        invalidate()
    }

    private fun releaseTiles() {
        tiled?.let { child ->
            child.retire()
            removeView(child)
        }
        tiled = null
    }

    private fun createTiles(value: ReadingRegionImageSource): TileView {
        return TileView(context).apply {
            setPanEnabled(false)
            setZoomEnabled(false)
            setQuickScaleEnabled(false)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE)
            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
            // The permanent base layer fits one screen, even for very tall webtoon pages.
            setMinScale(min(viewport.width().toFloat() / value.sourceWidth,
                viewport.height().toFloat() / value.sourceHeight).coerceAtLeast(0.000001f))
            setMaxScale(32f)
            setMaxTileSize(1024)
            setImage(ImageSource.provider(InputProvider { value.imageFile.inputStream() }))
            addView(this, LayoutParams(viewport.width(), viewport.height()))
        }.also { tiled = it }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Delegate aspect-ratio / adjustViewBounds measurement to the real ImageView.
        image.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(image.measuredWidth, image.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        image.layout(0, 0, width, height)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val value = source
        if (value != null && getLocalVisibleRect(viewport) && !viewport.isEmpty) {
            val child = tiled ?: createTiles(value)
            child.measure(MeasureSpec.makeMeasureSpec(viewport.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(viewport.height(), MeasureSpec.EXACTLY))
            child.layout(viewport.left, viewport.top, viewport.right, viewport.bottom)
            val transform = readingTileTransform(image.imageMatrix, viewport, value.layoutSampleSize)
            if (transform != null) {
                val previous = child.center
                if (child.scale != transform.scale || previous == null ||
                    kotlin.math.abs(previous.x - transform.center.x) > 0.01f ||
                    kotlin.math.abs(previous.y - transform.center.y) > 0.01f) {
                    child.setScaleAndCenter(transform.scale, transform.center)
                }
            }
        }
        super.dispatchDraw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        // RecyclerView may detach a holder temporarily while it remains reusable. Keep the
        // SSIV decoder and its tile cache alive until the holder is actually recycled.
        super.onDetachedFromWindow()
    }

    /** Release the region decoder when the owning holder or view lifecycle truly ends. */
    fun dispose() {
        releaseTiles()
    }

    private class TileView(context: Context) : SubsamplingScaleImageView(context) {
        private var retired = false
        private var pendingTasks = 0

        init {
            setExecutor { task ->
                if (!retired) {
                    pendingTasks++
                    tileExecutor.execute {
                        try {
                            task.run()
                        } finally {
                            // AsyncTask posts its result before returning. Run cleanup after that
                            // callback so an in-flight init cannot abandon its native decoder.
                            mainHandler.post {
                                pendingTasks--
                                if (retired && pendingTasks == 0) recycle()
                            }
                        }
                    }
                }
            }
        }

        fun retire() {
            retired = true
            if (pendingTasks == 0) recycle()
        }

        // Gestures belong to the reader/translation overlay, not this rendering child.
        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        // Bound decoding across all visible pages rather than giving each holder its own pool.
        private val tileExecutor = Executors.newFixedThreadPool(2)
    }
}

internal data class ReadingTileTransform(val scale: Float, val center: PointF)

/** Convert the reader's display coordinates into SSIV's source coordinates. */
internal fun readingTileTransform(matrix: Matrix, viewport: Rect, layoutSampleSize: Int): ReadingTileTransform? {
    val inverse = Matrix()
    if (viewport.isEmpty || !matrix.invert(inverse)) return null
    val point = floatArrayOf(viewport.exactCenterX(), viewport.exactCenterY())
    inverse.mapPoints(point)
    val values = FloatArray(9)
    matrix.getValues(values)
    val sample = layoutSampleSize.coerceAtLeast(1)
    val scale = values[Matrix.MSCALE_X] / sample
    if (!scale.isFinite() || scale <= 0f) return null
    return ReadingTileTransform(scale, PointF(point[0] * sample, point[1] * sample))
}
