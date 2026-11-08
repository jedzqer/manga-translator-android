package com.manga.translate.reader

import android.content.Context
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent

/** Confirms single taps only after the double-tap window has elapsed. */
internal class ReadingTapDetector(
    context: Context,
    onTap: (Float) -> Unit,
    onDoubleTap: (Float, Float) -> Unit
) {
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap(e.x)
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap(e.x, e.y)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    fun onTouchEvent(event: MotionEvent) { detector.onTouchEvent(event) }

    fun cancel() {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { detector.onTouchEvent(event) } finally { event.recycle() }
    }
}
