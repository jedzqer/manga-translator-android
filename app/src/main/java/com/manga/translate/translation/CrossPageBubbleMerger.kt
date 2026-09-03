package com.manga.translate.translation


import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import android.graphics.RectF
import com.manga.translate.detection.shouldTreatRectsAsSameBubbleForDedup
import com.manga.translate.model.BubbleSource
import com.manga.translate.platform.AppLogger
import kotlin.math.max
import kotlin.math.min

/**
 * 长图漫画跨页气泡合并工具。
 *
 * 条漫/长图模式下，一个气泡可能被切割在相邻两页之间。该模块在 OCR 完成后、翻译前
 * 扫描相邻页面，将跨越页面边界的气泡合并到上一页末尾，使下一页不再重复显示同一
 * 气泡的下半部分。
 *
 * 合并后的气泡坐标位于上一页的坐标系中，bottom 会超出上一页高度，从而在阅读页
 * 渲染时自然横跨两页。
 */
internal object CrossPageBubbleMerger {

    private const val CROSS_PAGE_EDGE_RATIO = 0.25f
    private const val CROSS_PAGE_EDGE_MIN_PX = 200f
    private const val CROSS_PAGE_EDGE_MAX_PX = 600f

    /**
     * 对相邻页面执行跨页气泡合并。
     *
     * @param pages 按阅读顺序排列的 OCR 结果。
     * @return 合并后的 OCR 结果列表，长度与输入一致。
     */
    fun merge(pages: List<PageOcrResult>): List<PageOcrResult> {
        if (pages.size < 2) {
            return pages.map { it.copy(bubbles = it.bubbles.toList()) }
        }
        val working = pages.map { it.copy(bubbles = it.bubbles.toList()) }.toMutableList()
        var mergeCount = 0
        for (index in 0 until working.size - 1) {
            val current = working[index]
            val next = working[index + 1]
            val result = mergeAdjacentPages(current, next)
            if (result.merged) {
                working[index] = result.currentPage
                working[index + 1] = result.nextPage
                mergeCount += result.mergedCount
            }
        }
        if (mergeCount > 0) {
            AppLogger.log(
                "CrossPageBubbleMerger",
                "Merged $mergeCount cross-page bubble(s) across ${pages.size} pages"
            )
        }
        return working
    }

    private data class MergeResult(
        val currentPage: PageOcrResult,
        val nextPage: PageOcrResult,
        val merged: Boolean,
        val mergedCount: Int
    )

    private fun mergeAdjacentPages(
        current: PageOcrResult,
        next: PageOcrResult
    ): MergeResult {
        if (current.width <= 0 || current.height <= 0 || next.width <= 0 || next.height <= 0) {
            return MergeResult(current, next, merged = false, mergedCount = 0)
        }
        val edgeHeight = computeEdgeHeight(current.width, current.height, next.height)
        val currentCandidates = current.bubbles.filter {
            isNearPageBottom(it.rect, current.height, edgeHeight)
        }
        val nextCandidates = next.bubbles.filter {
            isNearPageTop(it.rect, edgeHeight)
        }
        if (currentCandidates.isEmpty() || nextCandidates.isEmpty()) {
            return MergeResult(current, next, merged = false, mergedCount = 0)
        }

        val mergedNextIds = mutableSetOf<Int>()
        val currentIdToMergedBubble = mutableMapOf<Int, OcrBubble>()

        for (currentBubble in currentCandidates) {
            val match = findBestCrossPageMatch(
                currentBubble = currentBubble,
                currentHeight = current.height,
                currentWidth = current.width,
                nextWidth = next.width,
                nextCandidates = nextCandidates,
                alreadyMergedNextIds = mergedNextIds
            )
            if (match != null) {
                currentIdToMergedBubble[currentBubble.id] = buildMergedBubble(
                    currentBubble = currentBubble,
                    nextBubble = match.nextBubble,
                    currentHeight = current.height,
                    currentWidth = current.width,
                    nextWidth = next.width,
                    nextId = -1 // 后续统一重新编号
                )
                mergedNextIds.add(match.nextBubble.id)
            }
        }

        if (currentIdToMergedBubble.isEmpty()) {
            return MergeResult(current, next, merged = false, mergedCount = 0)
        }

        val newCurrentBubbles = current.bubbles.mapNotNull { bubble ->
            currentIdToMergedBubble[bubble.id] ?: bubble
        }

        val newNextBubbles = next.bubbles.filterNot { it.id in mergedNextIds }

        val renumberedCurrent = renumberBubbles(newCurrentBubbles)
        val renumberedNext = renumberBubbles(newNextBubbles)

        return MergeResult(
            currentPage = current.copy(bubbles = renumberedCurrent),
            nextPage = next.copy(bubbles = renumberedNext),
            merged = true,
            mergedCount = mergedNextIds.size
        )
    }

    private data class CrossPageMatch(
        val nextBubble: OcrBubble,
        val score: Float
    )

    private fun findBestCrossPageMatch(
        currentBubble: OcrBubble,
        currentHeight: Int,
        currentWidth: Int,
        nextWidth: Int,
        nextCandidates: List<OcrBubble>,
        alreadyMergedNextIds: Set<Int>
    ): CrossPageMatch? {
        var bestMatch: CrossPageMatch? = null
        var bestScore = 0f
        val currentRect = currentBubble.rect
        for (nextBubble in nextCandidates) {
            if (nextBubble.id in alreadyMergedNextIds) continue
            val nextRectInCurrentCoords = nextBubble.rect
                .scaleFromPage(nextWidth, currentWidth)
                .offsetBy(0f, currentHeight.toFloat())
            if (!shouldTreatRectsAsSameBubbleForDedup(currentRect, nextRectInCurrentCoords)) {
                continue
            }
            val score = rectIou(currentRect, nextRectInCurrentCoords)
            if (score > bestScore) {
                bestScore = score
                bestMatch = CrossPageMatch(nextBubble, score)
            }
        }
        return bestMatch
    }

    private fun buildMergedBubble(
        currentBubble: OcrBubble,
        nextBubble: OcrBubble,
        currentHeight: Int,
        currentWidth: Int,
        nextWidth: Int,
        nextId: Int
    ): OcrBubble {
        val nextRect = nextBubble.rect.scaleFromPage(nextWidth, currentWidth)
        val top = min(currentBubble.rect.top, currentHeight + nextRect.top)
        val bottom = currentHeight + nextRect.bottom
        val left = min(currentBubble.rect.left, nextRect.left)
        val right = max(currentBubble.rect.right, nextRect.right)
        val mergedRect = RectF(
            left.coerceAtLeast(0f),
            top.coerceAtLeast(0f),
            right,
            bottom
        )
        val mergedText = mergeCrossPageText(currentBubble.text, nextBubble.text)
        val source = if (
            currentBubble.source == BubbleSource.BUBBLE_DETECTOR ||
            nextBubble.source == BubbleSource.BUBBLE_DETECTOR
        ) {
            BubbleSource.BUBBLE_DETECTOR
        } else {
            BubbleSource.TEXT_DETECTOR
        }
        return OcrBubble(
            id = nextId,
            rect = mergedRect,
            text = mergedText,
            source = source,
            maskContour = null // 跨页轮廓拼接复杂，统一用矩形渲染
        )
    }

    private fun mergeCrossPageText(a: String, b: String): String {
        val left = a.trim()
        val right = b.trim()
        if (left.isBlank()) return right
        if (right.isBlank()) return left
        if (left == right) return left
        if (left.contains(right)) return left
        if (right.contains(left)) return right
        return "$left\n$right"
    }

    private fun renumberBubbles(bubbles: List<OcrBubble>): List<OcrBubble> {
        return bubbles
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }, { it.id }))
            .mapIndexed { index, bubble ->
                if (bubble.id == index) bubble else bubble.copy(id = index)
            }
    }

    private fun computeEdgeHeight(currentWidth: Int, currentHeight: Int, nextHeight: Int): Float {
        val minPageHeight = min(currentHeight, nextHeight).coerceAtLeast(1)
        return (currentWidth * CROSS_PAGE_EDGE_RATIO)
            .coerceIn(CROSS_PAGE_EDGE_MIN_PX, CROSS_PAGE_EDGE_MAX_PX)
            .coerceAtMost(minPageHeight / 2f)
    }

    private fun isNearPageBottom(rect: RectF, pageHeight: Int, edgeHeight: Float): Boolean {
        if (rect.bottom <= 0f || rect.top >= pageHeight) return false
        return rect.bottom >= pageHeight - edgeHeight
    }

    private fun isNearPageTop(rect: RectF, edgeHeight: Float): Boolean {
        if (rect.top < 0f) return true
        return rect.top <= edgeHeight
    }

    private fun RectF.offsetBy(offsetX: Float, offsetY: Float): RectF {
        return RectF(
            left + offsetX,
            top + offsetY,
            right + offsetX,
            bottom + offsetY
        )
    }

    private fun RectF.scaleFromPage(sourceWidth: Int, targetWidth: Int): RectF {
        val scale = targetWidth.toFloat() / sourceWidth.coerceAtLeast(1)
        return RectF(left * scale, top * scale, right * scale, bottom * scale)
    }

    private fun rectIou(a: RectF, b: RectF): Float {
        val inter = rectIntersectionArea(a, b)
        val union = rectAreaValue(a) + rectAreaValue(b) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun rectIntersectionArea(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        return max(0f, right - left) * max(0f, bottom - top)
    }

    private fun rectAreaValue(rect: RectF): Float {
        return max(0f, rect.width()) * max(0f, rect.height())
    }
}
