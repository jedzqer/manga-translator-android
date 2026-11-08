package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.model.ReadingPageAnimationMode
import com.manga.translate.reader.FloatingTranslationView
import com.manga.translate.reader.ReadingImageTransformController
import com.manga.translate.reader.ReadingPageTransitionController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingGestureRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `pan ending releases parent interception and next gesture can tap`() {
        for (endAction in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            val parent = TrackingParent(context)
            val overlay = FloatingTranslationView(context)
            parent.addView(overlay)
            val image = ImageView(context).apply { layout(0, 0, 600, 800) }
            val transform = ReadingImageTransformController(context, image, { _, _ -> false }, {})
            transform.resetContent(600, 1600, ReadingDisplayMode.FIT_WIDTH)
            overlay.onTransformTouch = transform::handleTouch
            var taps = 0
            overlay.onTap = { taps++ }
            touch(overlay, MotionEvent.ACTION_DOWN, 300f, 400f)
            touch(overlay, MotionEvent.ACTION_MOVE, 300f, 200f)
            assertTrue(parent.disallowed)
            touch(overlay, endAction, 300f, 200f)
            assertFalse("Pan must release parent on $endAction", parent.disallowed)
            assertEquals(0, taps)
            touch(overlay, MotionEvent.ACTION_DOWN, 300f, 400f)
            touch(overlay, MotionEvent.ACTION_UP, 300f, 400f)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertEquals(1, taps)
        }
    }

    @Test fun `page transition keeps gesture target visible and cancellation restores drawing`() {
        val binding = FragmentReadingBinding.inflate(LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_MangaTranslator)))
        binding.readingContentContainer.layout(0, 0, 600, 800)
        binding.translationOverlay.visibility = View.VISIBLE
        val transitions = ReadingPageTransitionController(binding,
            { ReadingPageAnimationMode.HORIZONTAL_SLIDE }, {})
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        transitions.start(bitmap, -1)
        assertEquals(View.VISIBLE, binding.translationOverlay.visibility)
        transitions.cancel()
        assertEquals(1f, binding.translationOverlay.alpha)
        assertEquals(View.GONE, binding.readingTransitionImage.visibility)
        transitions.release()
        bitmap.recycle()
    }

    @Test fun `multi pointer cancellation clears a running pan`() {
        val image = ImageView(context).apply { layout(0, 0, 600, 800) }
        val transform = ReadingImageTransformController(context, image, { _, _ -> false }, {})
        transform.resetContent(600, 1600, ReadingDisplayMode.FIT_WIDTH)
        fun send(action: Int, y: Float): Boolean {
            val event = MotionEvent.obtain(1000L, 1100L, action, 300f, y, 0)
            return try { transform.handleTouch(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 400f)
        assertTrue(send(MotionEvent.ACTION_MOVE, 200f))
        val pointers = Array(2) { i -> MotionEvent.PointerProperties().apply {
            id = i
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(2) { i -> MotionEvent.PointerCoords().apply {
            x = 300f + i * 100f
            y = 200f
        } }
        val cancel = MotionEvent.obtain(1000L, 1200L, MotionEvent.ACTION_CANCEL,
            2, pointers, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { transform.handleTouch(cancel) } finally { cancel.recycle() }
        // No movement: a cancelled pan must not continue consuming the stream.
        assertFalse(send(MotionEvent.ACTION_MOVE, 400f))
        assertFalse(send(MotionEvent.ACTION_DOWN, 400f))
        assertFalse(send(MotionEvent.ACTION_UP, 400f))
    }

    @Test fun `slight vertical overflow can scroll then pinch and turn pages`() {
        for (pageHeight in listOf(1452, 4000)) {
            val scroll = com.manga.translate.reader.ReadingScrollView(context)
            val content = FrameLayout(context)
            val image = ImageView(context)
            val overlay = FloatingTranslationView(context)
            content.addView(image, FrameLayout.LayoutParams(1000, pageHeight))
            content.addView(overlay, FrameLayout.LayoutParams(1000, pageHeight))
            scroll.addView(content, FrameLayout.LayoutParams(1000, pageHeight))
            scroll.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1435, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 1000, 1435)
            val transform = ReadingImageTransformController(context, image, { _, _ -> false }, {},
                onUnconsumedVerticalDrag = { dy ->
                    val before = scroll.scrollY
                    scroll.scrollBy(0, -dy.toInt())
                    (scroll.scrollY - before).toFloat()
                }, canScrollVertically = { true })
            transform.resetContent(1000, pageHeight, ReadingDisplayMode.FIT_WIDTH)
            // Long images disable image panning at base scale, but must still scroll.
            transform.setVerticalPanEnabled(false)
            val received = mutableListOf<Int>()
            var swipes = 0
            overlay.onSwipe = { swipes++ }
            overlay.onTransformTouch = { received += it.actionMasked; transform.handleTouch(it) }
            var time = android.os.SystemClock.uptimeMillis()
            fun dispatch(action: Int, x: Float, y: Float, secondX: Float? = null) {
                val count = if (secondX == null) 1 else 2
                val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply {
                    id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coordinates = Array(count) { i -> MotionEvent.PointerCoords().apply {
                    this.x = if (i == 0) x else secondX!!
                    this.y = y; pressure = 1f; size = 1f
                } }
                time += 30
                val event = MotionEvent.obtain(time - 500, time, action, count, pointers,
                    coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
                try { assertTrue(scroll.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN, 400f, 700f)
            dispatch(MotionEvent.ACTION_MOVE, 400f, 600f)
            assertFalse("Scrolling must not cancel the zoom/page gesture target",
                received.contains(MotionEvent.ACTION_CANCEL))
            assertEquals(minOf(100, pageHeight - 1435), scroll.scrollY)
            // Add a second finger without lifting the first after the vertical drag.
            dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                400f, 600f, 600f)
            dispatch(MotionEvent.ACTION_MOVE, 300f, 600f, 700f)
            dispatch(MotionEvent.ACTION_MOVE, 200f, 600f, 800f)
            assertTrue("Pinch must still reach the image transform", transform.isZoomed())
            dispatch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                200f, 600f, 800f)
            dispatch(MotionEvent.ACTION_UP, 200f, 600f)
            transform.resetZoom()
            dispatch(MotionEvent.ACTION_DOWN, 700f, 600f)
            dispatch(MotionEvent.ACTION_MOVE, 300f, 610f)
            dispatch(MotionEvent.ACTION_UP, 300f, 610f)
            assertEquals("Page swipe after vertical scroll", 1, swipes)
        }
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }

    private class TrackingParent(context: Context) : FrameLayout(context) {
        var disallowed = false
        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            disallowed = disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }
}
