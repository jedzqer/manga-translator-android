package com.manga.translate.floating

import android.os.Build
import com.manga.translate.model.FloatingOverlayMode

/**
 * Window-level touch and opacity policy of the full-screen detection overlay.
 *
 * Since Android 12 an untrusted overlay that obscures the app below with window alpha above
 * [PASSTHROUGH_ALPHA] drops the touches meant for that app, even when the overlay is not touchable.
 * The input dispatcher reads the window alpha, not the drawn pixels.
 */
internal data class DetectionOverlayWindowPolicy(
    val touchable: Boolean,
    val alpha: Float
) {
    companion object {
        const val PASSTHROUGH_ALPHA = 0.8f

        fun resolve(
            mode: FloatingOverlayMode,
            isEditing: Boolean,
            hasBubbles: Boolean,
            sdkInt: Int = Build.VERSION.SDK_INT
        ): DetectionOverlayWindowPolicy {
            if (isEditing) return DetectionOverlayWindowPolicy(touchable = true, alpha = 1f)
            // Below Android 12 the overlay never blocks touches, so an opaque mode carried over
            // from a profile or backup must not capture them while the setting is hidden.
            if (sdkInt < Build.VERSION_CODES.S) {
                return DetectionOverlayWindowPolicy(touchable = false, alpha = 1f)
            }
            if (mode == FloatingOverlayMode.OPAQUE_BLOCKING && hasBubbles) {
                return DetectionOverlayWindowPolicy(touchable = true, alpha = 1f)
            }
            return DetectionOverlayWindowPolicy(touchable = false, alpha = PASSTHROUGH_ALPHA)
        }
    }
}
