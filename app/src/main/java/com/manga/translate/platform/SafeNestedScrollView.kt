package com.manga.translate.platform

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

open class SafeNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {
    init {
        // Disable framework scrollbars by default to avoid OEM/framework crashes
        // in ScrollBarDrawable code paths.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isScrollbarFadingEnabled = false
    }

    private var scrollBarCrashOccurred = false

    override fun draw(canvas: Canvas) {
        try {
            super.draw(canvas)
        } catch (e: NullPointerException) {
            if (!isFrameworkScrollBarCrash(e)) {
                throw e
            }
            if (!scrollBarCrashOccurred) {
                // First crash: disable scroll bars and request a fresh draw.
                // Do NOT retry within the same frame — the scrollbar state hasn't
                // been cleared yet, so a retry would crash again.
                disableScrollBarsAfterCrash(e)
                postInvalidate()
            }
            // Subsequent crashes (or the re-entrant retry): silently skip this frame.
        }
    }

    private fun isFrameworkScrollBarCrash(error: NullPointerException): Boolean {
        // The known OEM/framework crash originates inside ScrollBarDrawable drawing.
        // Some ROM variants only name the class in the message (field access NPEs),
        // others only in the stack trace (method invocation NPEs), so check both.
        if (error.message?.contains("ScrollBarDrawable", ignoreCase = true) == true) {
            return true
        }
        return error.stackTrace.any { frame ->
            frame.className.contains("ScrollBarDrawable", ignoreCase = true)
        }
    }

    private fun disableScrollBarsAfterCrash(error: NullPointerException) {
        scrollBarCrashOccurred = true
        AppLogger.error(
            "SafeNestedScrollView",
            "Intercepted framework scrollbar crash; scrollbars disabled",
            error
        )
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
}
