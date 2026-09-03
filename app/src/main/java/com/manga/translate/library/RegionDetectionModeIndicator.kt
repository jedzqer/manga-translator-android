package com.manga.translate.library

import android.view.View
import android.widget.RadioGroup
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * Drives the sliding indicator behind the three-state region detection mode
 * selector.
 *
 * The indicator is a plain [View] stacked under the [RadioGroup]; it is sized and
 * translated to match whichever option is checked. Both values are derived from
 * measured child geometry, which is unavailable while the folder detail panel is
 * hidden or while the host fragment's view is detached by the pager.
 *
 * Because of that, the position is re-applied on every layout pass rather than
 * through a one-shot layout callback. A one-shot callback registered while the
 * control has no geometry can be dropped for good, which leaves the indicator
 * stuck under the previously checked option while the radio button highlight
 * still moves -- the state the user sees after returning from the reading tab.
 *
 * Create one instance per host view; the "has been positioned once" state is
 * per-view and decides whether a change slides or snaps.
 */
class RegionDetectionModeIndicator(
    private val group: RadioGroup,
    private val indicator: View,
    private val animationDurationMs: Long = DEFAULT_ANIMATION_MS
) {
    private var checkedId: Int = View.NO_ID
    private var isPositioned: Boolean = false
    private var animatingTo: Float? = null

    init {
        group.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply(animate = false) }
    }

    /**
     * Points the indicator at [checkedId]. Sliding is requested via [animate] but
     * is skipped until the indicator has been placed once for this view, so a
     * restored selection appears in place instead of flying in from the left.
     */
    fun setChecked(checkedId: Int, animate: Boolean) {
        this.checkedId = checkedId
        apply(animate)
    }

    /** Target the indicator is currently placed at, or heading to. Null until placed. */
    val targetTranslationX: Float?
        get() = animatingTo ?: if (isPositioned) indicator.translationX else null

    private fun apply(animate: Boolean) {
        if (checkedId == View.NO_ID) return
        val target = group.findViewById<View>(checkedId) ?: return
        val targetWidth = target.width
        if (targetWidth <= 0) return

        if (indicator.layoutParams.width != targetWidth) {
            indicator.layoutParams = indicator.layoutParams.apply { width = targetWidth }
        }

        val targetX = target.left.toFloat()
        // Assigning layoutParams above schedules another layout pass, which re-enters
        // here through the layout listener. Bail out rather than cancelling an
        // animation that is already heading to the right place.
        if (animatingTo == targetX) return
        indicator.animate().cancel()
        animatingTo = null

        if (animate && isPositioned) {
            animatingTo = targetX
            indicator.animate()
                .translationX(targetX)
                .setDuration(animationDurationMs)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction { animatingTo = null }
                .start()
        } else if (indicator.translationX != targetX) {
            indicator.translationX = targetX
        }
        isPositioned = true
    }

    private companion object {
        const val DEFAULT_ANIMATION_MS = 200L
    }
}
