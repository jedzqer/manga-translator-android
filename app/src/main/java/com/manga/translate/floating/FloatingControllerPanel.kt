package com.manga.translate.floating

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** A separate, bounded window keeps menu/status measurements out of the ball's layout. */
internal class FloatingControllerPanel(
    private val windows: FloatingWindowController,
    private val anchor: View,
    private val panel: View,
    private val anchorParams: WindowManager.LayoutParams,
    private val preferredWidth: Int
) {
    private val params = WindowManager.LayoutParams(
        preferredWidth, WindowManager.LayoutParams.WRAP_CONTENT,
        anchorParams.type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        anchorParams.format
    ).apply { gravity = Gravity.TOP or Gravity.LEFT }
    private var attached = false

    fun update(visible: Boolean) {
        if (!visible) {
            detach()
            return
        }
        if (anchor.width == 0 || anchor.height == 0) return
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        if (frame.isEmpty) return
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val ball = Rect(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
        val gap = (8 * anchor.resources.displayMetrics.density).toInt()
        val leftSpace = (ball.left - frame.left - gap).coerceAtLeast(0)
        val rightSpace = (frame.right - ball.right - gap).coerceAtLeast(0)
        val onLeft = leftSpace >= rightSpace
        val width = minOf(preferredWidth, maxOf(leftSpace, rightSpace)).coerceAtLeast(1)
        val heightLimit = (frame.height() - gap * 2).coerceAtLeast(1)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightLimit, View.MeasureSpec.AT_MOST)
        )
        val x = if (onLeft) ball.left - gap - width else ball.right + gap
        val y = ball.top.coerceIn(frame.top + gap,
            maxOf(frame.top + gap, frame.bottom - gap - panel.measuredHeight))
        // Both windows use the same inset frame, so convert screen coordinates back to it.
        params.x = x - frame.left
        params.y = y - frame.top
        params.width = width
        params.height = panel.measuredHeight.coerceAtLeast(1)
        if (attached) {
            windows.updateViewLayout(panel, params)
        } else {
            windows.addView(panel, params)
            attached = true
        }
    }

    fun detach() {
        if (attached) windows.removeView(panel)
        attached = false
    }
}
