package com.manga.translate.reader

import android.graphics.RectF
import com.manga.translate.R
import com.manga.translate.model.BubbleTranslation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class ManualBubbleShape(val labelRes: Int) {
    RECTANGLE(R.string.reading_bubble_shape_rectangle),
    ELLIPSE(R.string.reading_bubble_shape_ellipse);

    // Use the existing page-normalized contour format so saved results need no migration.
    fun contour(rect: RectF, pageWidth: Int, pageHeight: Int): FloatArray? {
        if (this == RECTANGLE || pageWidth <= 0 || pageHeight <= 0) return null
        return FloatArray(128) { index ->
            val angle = (index / 2) * 2.0 * PI / 64
            if (index % 2 == 0) {
                (rect.centerX() + rect.width() * 0.5f * cos(angle).toFloat()) / pageWidth
            } else {
                (rect.centerY() + rect.height() * 0.5f * sin(angle).toFloat()) / pageHeight
            }
        }
    }
}

internal fun BubbleTranslation.withResizedGeometry(
    newRect: RectF,
    pageWidth: Int,
    pageHeight: Int
): BubbleTranslation {
    val contour = maskContour
    val resizedContour = if (contour != null && pageWidth > 0 && pageHeight > 0 &&
        rect.width() > 0f && rect.height() > 0f
    ) {
        FloatArray(contour.size) { index ->
            if (index % 2 == 0) {
                (newRect.left + (contour[index] * pageWidth - rect.left) *
                    newRect.width() / rect.width()) / pageWidth
            } else {
                (newRect.top + (contour[index] * pageHeight - rect.top) *
                    newRect.height() / rect.height()) / pageHeight
            }
        }
    } else {
        contour?.copyOf()
    }
    return copy(rect = RectF(newRect), maskContour = resizedContour)
}

internal enum class BubbleResizeHandle(val xFraction: Float, val yFraction: Float) {
    TOP_LEFT(0f, 0f), TOP(0.5f, 0f), TOP_RIGHT(1f, 0f), RIGHT(1f, 0.5f),
    BOTTOM_RIGHT(1f, 1f), BOTTOM(0.5f, 1f), BOTTOM_LEFT(0f, 1f), LEFT(0f, 0.5f);

    fun resize(base: RectF, dx: Float, dy: Float, minWidth: Float, minHeight: Float, limits: RectF): RectF {
        return RectF(base).apply {
            if (xFraction == 0f) left = (base.left + dx).coerceIn(
                minOf(limits.left, base.right - minWidth), base.right - minWidth
            )
            if (xFraction == 1f) right = (base.right + dx).coerceIn(
                base.left + minWidth, maxOf(limits.right, base.left + minWidth)
            )
            if (yFraction == 0f) top = (base.top + dy).coerceIn(
                minOf(limits.top, base.bottom - minHeight), base.bottom - minHeight
            )
            if (yFraction == 1f) bottom = (base.bottom + dy).coerceIn(
                base.top + minHeight, maxOf(limits.bottom, base.top + minHeight)
            )
        }
    }
}
