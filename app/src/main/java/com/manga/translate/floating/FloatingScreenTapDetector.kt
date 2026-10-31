package com.manga.translate.floating

import android.os.Handler
import android.view.MotionEvent
import com.manga.translate.model.FloatingBallGestureAction
import kotlin.math.abs

internal data class FloatingTapActions(
    val single: FloatingBallGestureAction,
    val double: FloatingBallGestureAction,
    val triple: FloatingBallGestureAction,
    val longPress: FloatingBallGestureAction
)

/**
 * Resolves single, double, triple and long press on the touch-capturing overlay with the same
 * timing rules as the floating ball, and reports the action configured for the recognized gesture.
 */
internal class FloatingScreenTapDetector(
    private val handler: Handler,
    private val touchSlopPx: Float,
    private val doubleTapTimeoutMs: Long,
    private val longPressTimeoutMs: Long,
    private val actions: () -> FloatingTapActions,
    private val onAction: (FloatingBallGestureAction) -> Unit
) {
    private var downX = 0f
    private var downY = 0f
    private var pointerDown = false
    private var moved = false
    private var ignoreUntilNextDown = false
    private var longPressTriggered = false
    private var tapCount = 0

    private val longPressRunnable = Runnable {
        if (!pointerDown || moved || tapCount != 0) return@Runnable
        longPressTriggered = true
        onAction(actions().longPress)
    }

    private val commitTapRunnable = Runnable {
        if (pointerDown) return@Runnable
        val count = tapCount
        tapCount = 0
        when (count) {
            1 -> onAction(actions().single)
            2 -> onAction(actions().double)
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pointerDown = true
                moved = false
                ignoreUntilNextDown = false
                longPressTriggered = false
                handler.removeCallbacks(longPressRunnable)
                if (tapCount == 0) handler.postDelayed(longPressRunnable, longPressTimeoutMs)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                ignoreUntilNextDown = true
                resetPending()
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignoreUntilNextDown || moved) return true
                if (abs(event.x - downX) > touchSlopPx || abs(event.y - downY) > touchSlopPx) {
                    moved = true
                    resetPending()
                }
            }

            MotionEvent.ACTION_UP -> {
                pointerDown = false
                handler.removeCallbacks(longPressRunnable)
                if (ignoreUntilNextDown || moved) {
                    ignoreUntilNextDown = false
                    moved = false
                    return true
                }
                if (longPressTriggered) {
                    longPressTriggered = false
                    return true
                }
                tapCount = (tapCount + 1).coerceAtMost(3)
                handler.removeCallbacks(commitTapRunnable)
                if (tapCount >= 3) {
                    tapCount = 0
                    onAction(actions().triple)
                } else {
                    handler.postDelayed(commitTapRunnable, doubleTapTimeoutMs)
                }
            }

            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    fun cancel() {
        pointerDown = false
        moved = false
        ignoreUntilNextDown = false
        longPressTriggered = false
        resetPending()
    }

    private fun resetPending() {
        tapCount = 0
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(commitTapRunnable)
    }
}
