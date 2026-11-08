package com.manga.translate

import android.content.Context
import android.graphics.Matrix
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.reader.FloatingTranslationView
import com.manga.translate.reader.ReadingImageTransformController
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ReadingTapDetectorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun waitFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun send(overlay: FloatingTranslationView, action: Int, x: Float = 300f, y: Float = 400f) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { overlay.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun doubleTapDoesNotAlsoTriggerSingleTap() {
        val overlay = FloatingTranslationView(context)
        var taps = 0
        var doubles = 0
        overlay.onTap = { taps++ }
        overlay.onDoubleTap = { _, _ -> doubles++ }
        send(overlay, MotionEvent.ACTION_DOWN)
        waitFor(30)
        send(overlay, MotionEvent.ACTION_UP)
        assertEquals(0, taps)
        waitFor(80)
        send(overlay, MotionEvent.ACTION_DOWN)
        waitFor(30)
        send(overlay, MotionEvent.ACTION_UP)
        waitFor(400)
        assertEquals(0, taps)
        assertEquals(1, doubles)
    }

    @Test fun dragAndCancelDoNotTriggerSingleTap() {
        val overlay = FloatingTranslationView(context)
        var taps = 0
        overlay.onTap = { taps++ }
        send(overlay, MotionEvent.ACTION_DOWN)
        send(overlay, MotionEvent.ACTION_MOVE, y = 200f)
        send(overlay, MotionEvent.ACTION_UP, y = 200f)
        waitFor(400)
        assertEquals(0, taps)
        send(overlay, MotionEvent.ACTION_DOWN)
        send(overlay, MotionEvent.ACTION_CANCEL)
        waitFor(400)
        assertEquals(0, taps)
        send(overlay, MotionEvent.ACTION_DOWN)
        send(overlay, MotionEvent.ACTION_UP)
        waitFor(400)
        assertEquals(1, taps)
    }

    @Test fun editingCancelsPendingSingleTap() {
        val overlay = FloatingTranslationView(context)
        var taps = 0
        overlay.onTap = { taps++ }
        send(overlay, MotionEvent.ACTION_DOWN)
        send(overlay, MotionEvent.ACTION_UP)
        overlay.setEditMode(true)
        overlay.setEditMode(false)
        waitFor(400)
        assertEquals(0, taps)
    }

    @Test fun zoomedLongPagePansThenTransfersRemainingDragToParent() {
        val image = ImageView(context).apply {
            setImageBitmap(android.graphics.Bitmap.createBitmap(600, 1600, android.graphics.Bitmap.Config.ARGB_8888))
            layout(0, 0, 600, 1600)
        }
        var remaining = 0f
        val transform = ReadingImageTransformController(context, image, { _, _ -> false }, {},
            allowPanWhenOverflowing = false, onUnconsumedVerticalDrag = { remaining += it; -it })
        transform.resetContent(600, 1600, ReadingDisplayMode.FIT_WIDTH)
        transform.setVerticalPanEnabled(false)
        transform.toggleDoubleTapZoom(300f, 400f)
        val overlay = FloatingTranslationView(context)
        overlay.onTransformTouch = transform::handleTouch
        var taps = 0
        overlay.onTap = { taps++ }
        fun top(): Float = FloatArray(9).also { image.imageMatrix.getValues(it) }[Matrix.MTRANS_Y]
        val before = top()
        send(overlay, MotionEvent.ACTION_DOWN)
        send(overlay, MotionEvent.ACTION_MOVE, y = 200f)
        assertTrue(top() <= before)
        send(overlay, MotionEvent.ACTION_MOVE, y = -2600f)
        assertTrue(remaining < 0f)
        send(overlay, MotionEvent.ACTION_UP, y = -2600f)
        waitFor(400)
        assertEquals(0, taps)
        send(overlay, MotionEvent.ACTION_DOWN)
        send(overlay, MotionEvent.ACTION_UP)
        waitFor(400)
        assertEquals(1, taps)
    }
}
