package com.manga.translate.platform

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LockedWebtoonLinearLayoutManager(
    context: Context
) : LinearLayoutManager(context) {
    private var lockedRange: IntRange? = null
    var onLayoutFinished: (() -> Unit)? = null

    override fun onLayoutCompleted(state: RecyclerView.State) {
        super.onLayoutCompleted(state)
        onLayoutFinished?.invoke()
    }

    fun setLockedPosition(position: Int?) {
        setLockedPositionRange(position?.let { it..it })
    }

    fun setLockedPositionRange(range: IntRange?) {
        if (lockedRange == range) return
        lockedRange = range
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        val range = lockedRange ?: return super.scrollVerticallyBy(dy, recycler, state)
        if (dy == 0 || childCount == 0) return 0
        if (range.first == range.last) {
            return scrollSingleLockedPosition(dy, recycler, state, range.first)
        }
        val consumed = super.scrollVerticallyBy(dy, recycler, state)
        keepLockedRangeInViewport(dy, range, recycler, state)
        return consumed
    }

    private fun scrollSingleLockedPosition(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        target: Int
    ): Int {
        val lockedView = findViewByPosition(target) ?: return 0
        val viewportTop = paddingTop
        val viewportBottom = height - paddingBottom
        val viewportHeight = viewportBottom - viewportTop
        val itemHeight = lockedView.height
        if (itemHeight <= viewportHeight) return 0

        val maxScrollDown = (lockedView.bottom - viewportBottom).coerceAtLeast(0)
        val maxScrollUp = (lockedView.top - viewportTop).coerceAtMost(0)
        val constrainedDy = when {
            dy > 0 -> dy.coerceAtMost(maxScrollDown)
            dy < 0 -> dy.coerceAtLeast(maxScrollUp)
            else -> 0
        }
        if (constrainedDy == 0) return 0

        val consumed = super.scrollVerticallyBy(constrainedDy, recycler, state)
        val updatedTop = findViewByPosition(target)?.top ?: return consumed
        val minTop = viewportBottom - itemHeight
        val correction = when {
            updatedTop > viewportTop -> updatedTop - viewportTop
            updatedTop < minTop -> updatedTop - minTop
            else -> 0
        }
        if (correction != 0) {
            super.scrollVerticallyBy(correction, recycler, state)
        }
        return consumed
    }

    private fun keepLockedRangeInViewport(
        dy: Int,
        range: IntRange,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ) {
        val viewportTop = paddingTop
        val viewportBottom = height - paddingBottom
        val correction = if (dy < 0) {
            val firstLockedView = findViewByPosition(range.first)
            when {
                findFirstVisibleItemPosition() < range.first -> firstLockedView?.top?.minus(viewportTop) ?: 0
                firstLockedView != null && firstLockedView.top > viewportTop -> firstLockedView.top - viewportTop
                else -> 0
            }
        } else if (dy > 0) {
            val lastLockedView = findViewByPosition(range.last)
            when {
                findLastVisibleItemPosition() > range.last -> lastLockedView?.bottom?.minus(viewportBottom) ?: 0
                lastLockedView != null && lastLockedView.bottom < viewportBottom -> lastLockedView.bottom - viewportBottom
                else -> 0
            }
        } else {
            0
        }
        if (correction != 0) {
            super.scrollVerticallyBy(correction, recycler, state)
        }
    }
}
