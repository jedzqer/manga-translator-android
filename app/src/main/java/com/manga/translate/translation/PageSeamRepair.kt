package com.manga.translate.translation

import android.graphics.RectF
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import kotlin.math.max
import kotlin.math.min

private const val SEAM_FRAGMENT_COVERAGE = 0.5f

/**
 * Place bubbles recovered across the seam on [upper] and drop the truncated fragments
 * they replace from both pages. [recovered] uses upper page coordinates with the lower
 * page scaled to the upper width and placed below `upper.height`, like
 * [CrossPageBubbleMerger]. Bubbles are renumbered in reading order.
 */
internal fun applySeamBubbles(
    upper: PageOcrResult,
    lower: PageOcrResult,
    recovered: List<OcrBubble>
): Pair<PageOcrResult, PageOcrResult> {
    if (recovered.isEmpty()) return upper to lower
    val lowerScale = upper.width / lower.width.coerceAtLeast(1).toFloat()
    val seam = upper.height.toFloat()
    val upperKept = upper.bubbles.filterNot { bubble ->
        recovered.any { isSeamFragment(bubble.rect, it.rect) }
    }
    val lowerKept = lower.bubbles.filterNot { bubble ->
        val inUpper = RectF(
            bubble.rect.left * lowerScale,
            bubble.rect.top * lowerScale + seam,
            bubble.rect.right * lowerScale,
            bubble.rect.bottom * lowerScale + seam
        )
        recovered.any { isSeamFragment(inUpper, it.rect) }
    }
    return upper.copy(bubbles = renumberSeamBubbles(upperKept + recovered)) to
        lower.copy(bubbles = renumberSeamBubbles(lowerKept))
}

/** A fragment lies mostly inside the recovered bubble; neighbours that merely touch it stay. */
private fun isSeamFragment(fragment: RectF, recovered: RectF): Boolean {
    val area = max(0f, fragment.width()) * max(0f, fragment.height())
    if (area <= 0f) return false
    val overlap = max(0f, min(fragment.right, recovered.right) - max(fragment.left, recovered.left)) *
        max(0f, min(fragment.bottom, recovered.bottom) - max(fragment.top, recovered.top))
    return overlap / area >= SEAM_FRAGMENT_COVERAGE
}

private fun renumberSeamBubbles(bubbles: List<OcrBubble>): List<OcrBubble> =
    bubbles.sortedWith(compareBy({ it.rect.top }, { it.rect.left }, { it.id }))
        .mapIndexed { index, bubble -> if (bubble.id == index) bubble else bubble.copy(id = index) }
