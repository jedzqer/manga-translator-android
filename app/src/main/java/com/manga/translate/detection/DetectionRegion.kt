package com.manga.translate.detection

import android.graphics.Bitmap
import android.graphics.RectF

/** Detect only the selected rectangle, returning full-screen coordinates. */
internal suspend fun detectWithinInsets(
    bitmap: Bitmap,
    topInsetPercent: Int,
    bottomInsetPercent: Int,
    leftInsetPercent: Int = 0,
    rightInsetPercent: Int = 0,
    detect: suspend (Bitmap) -> List<PageRegion>?
): List<PageRegion>? {
    val topPercent = topInsetPercent.coerceIn(0, 90)
    val bottomPercent = bottomInsetPercent.coerceIn(0, 90 - topPercent)
    val top = (bitmap.height * topPercent / 100f).toInt()
    val bottom = (bitmap.height * (100 - bottomPercent) / 100f).toInt()
        .coerceAtLeast(top + 1).coerceAtMost(bitmap.height)
    val leftPercent = leftInsetPercent.coerceIn(0, 90)
    val rightPercent = rightInsetPercent.coerceIn(0, 90 - leftPercent)
    val left = (bitmap.width * leftPercent / 100f).toInt()
    val right = (bitmap.width * (100 - rightPercent) / 100f).toInt()
        .coerceAtLeast(left + 1).coerceAtMost(bitmap.width)
    if (left == 0 && right == bitmap.width && top == 0 && bottom == bitmap.height) return detect(bitmap)
    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    try {
        return detect(crop)?.map { region ->
            region.copy(
                rect = RectF(region.rect).apply { offset(left.toFloat(), top.toFloat()) },
                textLineRects = region.textLineRects?.map { rect ->
                    RectF(rect).apply { offset(left.toFloat(), top.toFloat()) }
                },
                // Mask vertices are normalized against the detector input image.
                maskContour = region.maskContour?.mapIndexed { index, value ->
                    if (index % 2 == 0) (value * crop.width + left) / bitmap.width else (value * crop.height + top) / bitmap.height
                }?.toFloatArray()
            )
        }
    } finally {
        if (crop !== bitmap) crop.recycle()
    }
}
