package com.manga.translate.floating

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.cropBitmap
import com.manga.translate.platform.recycleSafely
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the lifecycle of every [Bitmap] used by [FloatingBallOverlayService]:
 * the retained session frame, the confirm-edit snapshot copy, the transient
 * OCR crops and the auto-close screen-change reference frames.
 *
 * The Service obtains and returns bitmaps exclusively through this class; all
 * creation, size constraints and recycling decisions (timing, single
 * ownership) live here so that every bitmap is recycled exactly once and never
 * while still in use:
 *  - the previous session frame is recycled only when a new frame is adopted;
 *  - a captured frame is recycled on every non-adoption path and survives on
 *    the adoption path (the caller nulls its local reference right after
 *    adopting, so the coroutine finally cannot double-recycle it);
 *  - the confirm snapshot copy is recycled exactly once after the confirm
 *    attempt ends;
 *  - auto-close reference frames are recycled when replaced, cleared or after
 *    a comparison.
 */
internal class FloatingBitmapController {

    // ---------------------------------------------------------------- session frame

    /** The retained source frame of the current session (detection / editing). */
    @Volatile
    var sessionBitmap: Bitmap? = null
        private set

    /**
     * Adopts [frame] as the new session frame, recycling the previous one.
     * Afterwards the controller is the sole owner of [frame].
     */
    fun adoptSessionFrame(frame: Bitmap) {
        sessionBitmap?.recycleSafely()
        sessionBitmap = frame
    }

    /**
     * Recycles [capture] unless it was adopted as the session frame. Intended
     * for the finally block of capture coroutines: the adopted path keeps the
     * frame alive (callers null their local reference after adoption), every
     * early-return / stale-generation path releases it.
     */
    fun discardTransientCapture(capture: Bitmap?) {
        if (capture != null && capture !== sessionBitmap) {
            capture.recycleSafely()
        } else if (capture === sessionBitmap) {
            // This branch should never be reached under the correct calling convention
            // (caller must null the local reference immediately after adoptSessionFrame).
            // If hit, a caller forgot to null the reference, violating the ownership contract.
            AppLogger.error(
                "FloatingBitmapController",
                "discardTransientCapture called with adopted session bitmap; caller violated ownership contract"
            )
        }
    }

    /** Recycles and drops the session frame. */
    fun clearSessionBitmap() {
        sessionBitmap?.recycleSafely()
        sessionBitmap = null
    }

    // ------------------------------------------------------------------ edit snapshot

    /**
     * Creates an independent copy of the session frame for the async confirm
     * processing. The caller must release it with [releaseBitmap] exactly once
     * when the confirm attempt ends.
     */
    fun createEditSnapshot(): Bitmap? {
        val source = sessionBitmap ?: return null
        return runCatching {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }.getOrNull()
    }

    // ----------------------------------------------------------- transient bitmaps

    /** Creates a crop of [source] for bubble OCR / recognition. */
    fun cropRegion(source: Bitmap, rect: RectF): Bitmap? = cropBitmap(source, rect)

    /** Releases a transient bitmap owned by the caller (safe for null / recycled). */
    fun releaseBitmap(bitmap: Bitmap?) {
        bitmap?.recycleSafely()
    }

    // -------------------------------------------------- auto-close reference frames

    /** The scaled reference frame used for auto-close screen-change checks. */
    @Volatile
    var autoCloseReferenceFrame: ScreenChangeReferenceFrame? = null
        private set

    /** Replaces the auto-close reference with a scaled frame of [source]. */
    fun rebuildAutoCloseReference(source: Bitmap?) {
        autoCloseReferenceFrame?.bitmap?.recycleSafely()
        autoCloseReferenceFrame = source?.let { createScreenChangeReferenceFrame(it) }
    }

    /** Releases and drops the auto-close reference frame. */
    fun clearAutoCloseReference() {
        autoCloseReferenceFrame?.bitmap?.recycleSafely()
        autoCloseReferenceFrame = null
    }

    /**
     * Builds a scaled comparison frame from [source]. The returned frame owns
     * its (newly created) bitmap; release it with [releaseBitmap].
     */
    fun createScreenChangeReferenceFrame(source: Bitmap): ScreenChangeReferenceFrame? {
        if (source.width <= 0 || source.height <= 0) return null
        val targetWidth = AUTO_CLOSE_REFERENCE_WIDTH
        val targetHeight = max(1, (targetWidth * source.height.toFloat() / source.width.toFloat()).toInt())
        val scaled = source.scale(targetWidth, targetHeight)
        val ignoreTop = (targetHeight * AUTO_CLOSE_IGNORE_TOP_RATIO).toInt()
        val ignoreBottom = (targetHeight * AUTO_CLOSE_IGNORE_BOTTOM_RATIO).toInt()
        val sideInset = (targetWidth * AUTO_CLOSE_IGNORE_SIDE_RATIO).toInt()
        val cropLeft = sideInset.coerceIn(0, targetWidth - 1)
        val cropTop = ignoreTop.coerceIn(0, targetHeight - 1)
        val cropRight = (targetWidth - sideInset).coerceIn(cropLeft + 1, targetWidth)
        val cropBottom = (targetHeight - ignoreBottom).coerceIn(cropTop + 1, targetHeight)
        return ScreenChangeReferenceFrame(
            bitmap = scaled,
            sampleRect = Rect(cropLeft, cropTop, cropRight, cropBottom)
        )
    }

    /**
     * Compares two scaled frames and decides whether the screen changed enough
     * to auto-close the floating session. Pure sampling over the reference
     * bitmaps; the frames are not modified.
     */
    fun hasMeaningfulScreenChange(
        reference: ScreenChangeReferenceFrame,
        current: ScreenChangeReferenceFrame
    ): Boolean {
        val left = max(reference.sampleRect.left, current.sampleRect.left)
        val top = max(reference.sampleRect.top, current.sampleRect.top)
        val right = min(reference.sampleRect.right, current.sampleRect.right)
        val bottom = min(reference.sampleRect.bottom, current.sampleRect.bottom)
        if (right <= left || bottom <= top) return false
        var sampled = 0
        var changed = 0
        var totalDelta = 0
        var rowsWithChange = 0
        val sampledRows = max(1, ((bottom - top) + AUTO_CLOSE_SAMPLE_STEP - 1) / AUTO_CLOSE_SAMPLE_STEP)
        val rowChangeThreshold = max(1, ((right - left) / AUTO_CLOSE_SAMPLE_STEP) / 4)
        for (y in top until bottom step AUTO_CLOSE_SAMPLE_STEP) {
            var rowChangedPixels = 0
            for (x in left until right step AUTO_CLOSE_SAMPLE_STEP) {
                val delta = pixelDelta(reference.bitmap[x, y], current.bitmap[x, y])
                sampled++
                totalDelta += delta
                if (delta >= AUTO_CLOSE_SIGNIFICANT_PIXEL_DELTA) {
                    changed++
                    rowChangedPixels++
                }
            }
            if (rowChangedPixels >= rowChangeThreshold) {
                rowsWithChange++
            }
        }
        if (sampled == 0) return false
        val changedRatio = changed.toFloat() / sampled.toFloat()
        val averageDelta = totalDelta.toFloat() / sampled.toFloat()
        val rowChangedRatio = rowsWithChange.toFloat() / sampledRows.toFloat()
        AppLogger.log(
            "FloatingOCR",
            "Auto close sampled=$sampled changedRatio=$changedRatio averageDelta=$averageDelta rowChangedRatio=$rowChangedRatio"
        )
        return changedRatio >= AUTO_CLOSE_CHANGED_PIXEL_RATIO_THRESHOLD &&
            averageDelta >= AUTO_CLOSE_AVERAGE_DELTA_THRESHOLD &&
            rowChangedRatio >= AUTO_CLOSE_CHANGED_ROW_RATIO_THRESHOLD
    }

    private fun pixelDelta(first: Int, second: Int): Int {
        val dr = abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF))
        val dg = abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF))
        val db = abs((first and 0xFF) - (second and 0xFF))
        return (dr + dg + db) / 3
    }

    private companion object {
        const val AUTO_CLOSE_REFERENCE_WIDTH = 180
        const val AUTO_CLOSE_IGNORE_TOP_RATIO = 0.12f
        const val AUTO_CLOSE_IGNORE_BOTTOM_RATIO = 0.14f
        const val AUTO_CLOSE_IGNORE_SIDE_RATIO = 0.04f
        const val AUTO_CLOSE_SAMPLE_STEP = 3
        const val AUTO_CLOSE_SIGNIFICANT_PIXEL_DELTA = 32
        const val AUTO_CLOSE_CHANGED_PIXEL_RATIO_THRESHOLD = 0.12f
        const val AUTO_CLOSE_AVERAGE_DELTA_THRESHOLD = 14f
        const val AUTO_CLOSE_CHANGED_ROW_RATIO_THRESHOLD = 0.18f
    }
}

internal data class ScreenChangeReferenceFrame(
    val bitmap: Bitmap,
    val sampleRect: Rect
)
