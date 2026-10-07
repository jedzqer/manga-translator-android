package com.manga.translate.translation

import com.manga.translate.model.PageOcrResult
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val TRANSLATION_WINDOW_PAGES = 100
internal const val GLOSSARY_WINDOW_CHARACTERS = 12_000

internal fun translationWindowSize(batchSize: Int): Int = TRANSLATION_WINDOW_PAGES

/** Finish and save a window before preparing the next. Only the webtoon lookahead is retained. */
internal suspend fun <T> forEachPreparedPageWindow(
    pages: List<File>,
    windowSize: Int = TRANSLATION_WINDOW_PAGES,
    lookAhead: Boolean = false,
    prepare: suspend (pages: List<File>, offset: Int) -> List<T?>,
    transform: (List<T?>) -> List<T?> = { it },
    process: suspend (pages: List<T?>, offset: Int) -> Unit
) {
    require(windowSize > 0)
    var carry = emptyList<T?>()
    for (start in pages.indices step windowSize) {
        currentCoroutineContext().ensureActive()
        val end = minOf(start + windowSize, pages.size)
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
        while (end < pages.size) {
            val previous = pages[end - 1] ?: break
            val next = pages[end] ?: break
            // A collection is processed folder by folder, but keep this invariant
            // here as well so a mixed task can never merge across directory edges.
            if (previous.imageFile.parentFile?.absoluteFile != next.imageFile.parentFile?.absoluteFile) break
            val position = pagePositions[previous.imageFile] ?: break
            if (pagePositions[next.imageFile] != position + 1) break
            end++
        }
        val merged = CrossPageBubbleMerger.merge(pages.subList(start, end).filterNotNull())
        merged.forEachIndexed { index, page -> result[start + index] = page }
        start = end
    }
    return result
}

/** Bound even a text-heavy page without discarding text or splitting a surrogate pair. */
internal fun glossaryTextBatches(
    pages: List<PageOcrResult>,
    maxCharacters: Int = GLOSSARY_WINDOW_CHARACTERS
): Sequence<String> = sequence {
    val markupLength = "<b></b>\n".length
    require(maxCharacters >= markupLength + 2)
    val builder = StringBuilder()
    for (page in pages) {
        val bubbles = page.bubbles.sortedWith(compareBy({ it.rect.top }, { it.rect.left }, { it.id }))
        for (bubble in bubbles) {
            val text = bubble.text.trim()
            var start = 0
            while (start < text.length) {
                val remaining = maxCharacters - builder.length - markupLength
                if (remaining < minOf(2, text.length - start) ||
                    (builder.isNotEmpty() && text.length - start > remaining)
                ) {
                    yield(builder.toString())
                    builder.setLength(0)
                    continue
                }
                var end = minOf(text.length, start + remaining)
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
                    end--
                }
                builder.append("<b>").append(text, start, end).append("</b>\n")
                start = end
            }
        }
    }
    if (builder.isNotEmpty()) yield(builder.toString())
}
