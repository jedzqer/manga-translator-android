package com.manga.translate.model

import androidx.annotation.StringRes
import com.manga.translate.R

/** How the floating translation overlay window treats touches and its own opacity. */
enum class FloatingOverlayMode(
    val prefValue: String,
    @param:StringRes val labelRes: Int
) {
    /** Window alpha stays within the untrusted-touch limit so touches reach the app underneath. */
    TRANSLUCENT_PASSTHROUGH("translucent_passthrough", R.string.floating_overlay_mode_translucent),

    /** Fully opaque window that captures touches while bubbles are shown. */
    OPAQUE_BLOCKING("opaque_blocking", R.string.floating_overlay_mode_opaque);

    companion object {
        fun fromPref(
            value: String?,
            defaultValue: FloatingOverlayMode
        ): FloatingOverlayMode {
            return entries.firstOrNull { it.prefValue == value } ?: defaultValue
        }

        /**
         * The opaque overlay freezes the screen while bubbles are shown, so it is only usable when
         * one of the on-screen gestures clears them.
         */
        fun isAllowed(
            mode: FloatingOverlayMode,
            gestureActions: Collection<FloatingBallGestureAction>
        ): Boolean {
            return mode != OPAQUE_BLOCKING || FloatingBallGestureAction.CLEAR_SCREEN in gestureActions
        }
    }
}
