package com.manga.translate.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Two adjacent pages viewed as one vertical strip in [upper] page coordinates.
 * [lower] is scaled to the upper width and starts at [seamY]. The sources stay
 * owned by the caller; closing this view does not close them.
 */
internal class StitchedBitmapCropSource(
    private val upper: BitmapCropSource,
    private val lower: BitmapCropSource
) : BitmapCropSource {
    private val lowerScale = upper.width / lower.width.coerceAtLeast(1).toFloat()

    val seamY: Int = upper.height
    override val width: Int = upper.width
    override val height: Int = upper.height + (lower.height * lowerScale).roundToInt()

    override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap? {
        val bounds = PipelineBitmapDecoder.clampRect(rect, width, height) ?: return null
        val scale = min(1f, maxEdge / max(bounds.width(), bounds.height()))
        val output = createBitmap(
            max(1, (bounds.width() * scale).roundToInt()),
            max(1, (bounds.height() * scale).roundToInt())
        )
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val seam = seamY.toFloat()
            if (bounds.top < seam) {
                val part = RectF(bounds.left, bounds.top, bounds.right, min(bounds.bottom, seam))
                if (!drawPart(canvas, upper, part, part, bounds, scale, maxEdge)) {
                    output.recycleSafely()
                    return null
                }
            }
            if (bounds.bottom > seam) {
                val part = RectF(bounds.left, max(bounds.top, seam), bounds.right, bounds.bottom)
                val sourceRect = RectF(
                    part.left / lowerScale,
                    (part.top - seam) / lowerScale,
                    part.right / lowerScale,
                    (part.bottom - seam) / lowerScale
                )
                if (!drawPart(canvas, lower, sourceRect, part, bounds, scale, maxEdge)) {
                    output.recycleSafely()
                    return null
                }
            }
            return output
        } catch (e: Throwable) {
            output.recycleSafely()
            throw e
        }
    }

    /** Slivers thinner than a decodable region are left blank instead of failing the crop. */
    private suspend fun drawPart(
        canvas: Canvas,
        source: BitmapCropSource,
        sourceRect: RectF,
        stitchedRect: RectF,
        bounds: RectF,
        scale: Float,
        maxEdge: Int
    ): Boolean {
        val clamped = PipelineBitmapDecoder.clampRect(sourceRect, source.width, source.height)
            ?: return true
        val bitmap = source.decodeRegion(clamped, maxEdge) ?: return false
        try {
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    (stitchedRect.left - bounds.left) * scale,
                    (stitchedRect.top - bounds.top) * scale,
                    (stitchedRect.right - bounds.left) * scale,
                    (stitchedRect.bottom - bounds.top) * scale
                ),
                null
            )
        } finally {
            bitmap.recycleSafely()
        }
        return true
    }

    override fun close() = Unit
}
