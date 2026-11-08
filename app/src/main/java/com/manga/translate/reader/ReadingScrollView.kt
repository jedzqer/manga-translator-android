package com.manga.translate.reader

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.platform.SafeNestedScrollView
import kotlin.math.roundToInt

internal fun shouldEnableReadingContainerScroll(
    readingMode: FolderReadingMode,
    isEditMode: Boolean,
    hasVerticalOverflow: Boolean
): Boolean {
    return readingMode != FolderReadingMode.WEBTOON_SCROLL && !isEditMode && hasVerticalOverflow
}

internal fun resolveEffectiveReadingDisplayMode(
    readingMode: FolderReadingMode,
    configuredMode: ReadingDisplayMode,
    isLongImage: Boolean
): ReadingDisplayMode {
    return if (readingMode == FolderReadingMode.WEBTOON_SCROLL || isLongImage) {
        ReadingDisplayMode.FIT_WIDTH
    } else {
        configuredMode
    }
}

internal fun resolveFitWidthScrollableContentHeight(
    readingMode: FolderReadingMode,
    displayMode: ReadingDisplayMode,
    contentWidth: Int,
    contentHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int
): Int? {
    if (
        readingMode == FolderReadingMode.WEBTOON_SCROLL ||
        displayMode != ReadingDisplayMode.FIT_WIDTH ||
        contentWidth <= 0 ||
        contentHeight <= 0 ||
        viewportWidth <= 0 ||
        viewportHeight <= 0
    ) {
        return null
    }
    val fitWidthHeight = (viewportWidth.toDouble() * contentHeight / contentWidth)
        .roundToInt()
        .coerceAtLeast(1)
    return fitWidthHeight.takeIf { it > viewportHeight }
}

class ReadingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SafeNestedScrollView(context, attrs, defStyleAttr) {
    var scrollEnabled: Boolean = true

    /**
     * Returns the part of this view that can actually contain the reading page.
     * System-window handling on some devices is represented as view padding, so
     * using the raw view height can make a page extend below the visible area.
     */
    fun contentViewportHeight(): Int {
        return (height - paddingTop - paddingBottom).coerceAtLeast(0)
    }

    fun contentViewportWidth(): Int {
        return (width - paddingLeft - paddingRight).coerceAtLeast(0)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // The overlay owns the entire gesture, including vertical drags. Intercepting
        // here cancels its scale/tap detectors and prevents a drag becoming a pinch.
        // It delegates unconsumed vertical movement back through scrollBy instead.
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!scrollEnabled) return false
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP && !handled) {
            performClick()
        }
        return handled
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
