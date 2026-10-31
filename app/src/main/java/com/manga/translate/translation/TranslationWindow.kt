package com.manga.translate.translation

import com.manga.translate.model.PageOcrResult
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val TRANSLATION_WINDOW_PAGES = 100

/** Finish and save a window before preparing the next. Only the webtoon lookahead is retained. */
internal suspend fun <T> forEachPreparedPageWindow(
    pages: List<File>,
    windowSize: Int = TRANSLATION_WINDOW_PAGES,
    lookAhead: Boolean = false,
    resolveWindowSize: (() -> Int)? = null,
    prepare: suspend (pages: List<File>, offset: Int) -> List<T?>,
    transform: suspend (List<T?>) -> List<T?> = { it },
    process: suspend (pages: List<T?>, offset: Int) -> Unit
) {
    require(windowSize > 0)
    var carry = emptyList<T?>()
    var start = 0
    while (start < pages.size) {
        currentCoroutineContext().ensureActive()
        val currentSize = resolveWindowSize?.invoke() ?: windowSize
        require(currentSize > 0)
        val end = minOf(start + currentSize, pages.size)
        val prepareEnd = if (lookAhead && end < pages.size) end + 1 else end
        val prepareStart = start + carry.size
        val prepared = prepare(pages.subList(prepareStart, prepareEnd), prepareStart)
        require(prepared.size == prepareEnd - prepareStart)
        val transformed = transform(carry + prepared)
        require(transformed.size == prepareEnd - start)
        currentCoroutineContext().ensureActive()
        process(transformed.take(end - start), start)
        // Keep the already-merged lookahead, not its original OCR result. Otherwise
        // a bubble consumed by the previous page would reappear at the next window.
        carry = transformed.drop(end - start)
        start = end
    }
}

/** Failed, cached or unselected pages break adjacency; never merge across a gap. */
internal fun mergeOcrWindow(
    pages: List<PageOcrResult?>,
    pagePositions: Map<File, Int>
): List<PageOcrResult?> {
    val result = pages.toMutableList()
    var start = 0
    while (start < pages.size) {
        val first = pages[start]
        if (first == null) {
            start++
            continue
        }
        var end = start + 1
        while (end < pages.size && areAdjacentPages(pages[end - 1], pages[end], pagePositions)) end++
        val merged = CrossPageBubbleMerger.merge(pages.subList(start, end).filterNotNull())
        merged.forEachIndexed { index, page -> result[start + index] = page }
        start = end
    }
    return result
}

private fun areAdjacentPages(
    previous: PageOcrResult?,
    next: PageOcrResult?,
    pagePositions: Map<File, Int>
): Boolean {
    if (previous == null || next == null) return false
    // A collection is processed folder by folder, but keep this invariant
    // here as well so a mixed task can never merge across directory edges.
    if (previous.imageFile.parentFile?.absoluteFile != next.imageFile.parentFile?.absoluteFile) return false
    val position = pagePositions[previous.imageFile] ?: return false
    return pagePositions[next.imageFile] == position + 1
}

/**
 * Repair bubbles split between adjacent pages before [mergeOcrWindow]. Only pairs with
 * a region near the seam are re-detected; [repair] returns null to keep a pair as is.
 */
internal suspend fun repairOcrWindowSeams(
    pages: List<PageOcrResult?>,
    pagePositions: Map<File, Int>,
    repair: suspend (upper: PageOcrResult, lower: PageOcrResult) -> Pair<PageOcrResult, PageOcrResult>?
): List<PageOcrResult?> {
    val result = pages.toMutableList()
    for (index in 0 until result.size - 1) {
        val upper = result[index]
        val lower = result[index + 1]
        if (!areAdjacentPages(upper, lower, pagePositions)) continue
        if (!hasRegionNearSeam(upper!!, lower!!)) continue
        currentCoroutineContext().ensureActive()
        val (repairedUpper, repairedLower) = repair(upper, lower) ?: continue
        result[index] = repairedUpper
        result[index + 1] = repairedLower
    }
    return result
}

private const val SEAM_REGION_EDGE_RATIO = 0.25f

private fun hasRegionNearSeam(upper: PageOcrResult, lower: PageOcrResult): Boolean {
    if (upper.width <= 0 || upper.height <= 0 || lower.width <= 0 || lower.height <= 0) return false
    val upperEdge = upper.height - upper.width * SEAM_REGION_EDGE_RATIO
    val lowerEdge = lower.width * SEAM_REGION_EDGE_RATIO
    return upper.bubbles.any { it.rect.bottom >= upperEdge && it.rect.top < upper.height } ||
        lower.bubbles.any { it.rect.top <= lowerEdge }
}

/** Bound even a text-heavy page without discarding text or splitting a surrogate pair. */
/** One complete image per unit; no character slicing, including exceptionally long bubbles. */
internal fun glossaryPageUnits(pages: List<PageOcrResult>): List<TextBudgetUnit> = pages.mapNotNull { page ->
    val text = page.bubbles.sortedWith(compareBy({ it.rect.top }, { it.rect.left }, { it.id }))
        .map { it.text.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n") { "<b>$it</b>" }
    if (text.isEmpty()) null else TextBudgetUnit(page.imageFile.absolutePath, text)
}
