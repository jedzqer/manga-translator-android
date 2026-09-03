package com.manga.translate.detection

import android.graphics.RectF
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.OcrBubble
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object RectGeometryDeduplicator {
    fun mergeSupplementRects(
        rects: List<RectF>,
        imageWidth: Int,
        imageHeight: Int,
        maxMergedHeight: Float? = null
    ): List<RectF> {
        if (rects.size <= 1) return rects
        val imageArea = (imageWidth.toFloat() * imageHeight.toFloat()).coerceAtLeast(1f)
        val mergedRects = rects.map { RectF(it) }.toMutableList()
        // 迭代到不动点：一次合并产生的更大框可能又触及第三个框，必须反复扫描直到某轮没有合并发生。
        var merged = true
        while (merged) {
            merged = false
            for (i in 0 until mergedRects.size) {
                var j = i + 1
                while (j < mergedRects.size) {
                    val union = unionRects(mergedRects[i], mergedRects[j])
                    if (shouldMergeRects(mergedRects[i], mergedRects[j], union, imageArea, maxMergedHeight)) {
                        mergedRects[i] = union
                        mergedRects.removeAt(j)
                        merged = true
                    } else {
                        // 仅在不合并时前进 j；合并后第 j 位已换成新元素，需原地复检。
                        j++
                    }
                }
            }
        }
        return mergeDenseClusters(mergedRects, imageArea, maxMergedHeight)
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    fun mergeShortOcrBubbles(
        bubbles: List<OcrBubble>,
        imageWidth: Int,
        imageHeight: Int,
        maxMergedHeight: Float? = null
    ): List<OcrBubble> {
        if (bubbles.size <= 1) return bubbles
        val imageArea = (imageWidth.toFloat() * imageHeight.toFloat()).coerceAtLeast(1f)
        val merged = bubbles.map { MutableBubbleGroup.from(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until merged.size) {
                var j = i + 1
                while (j < merged.size) {
                    if (shouldMergeShortTextGroups(merged[i], merged[j], imageArea, maxMergedHeight)) {
                        merged[i] = merged[i].mergeWith(merged[j])
                        merged.removeAt(j)
                        changed = true
                    } else {
                        j++
                    }
                }
            }
        }
        return merged
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            .mapIndexed { index, group -> group.toOcrBubble(index) }
    }

    private fun mergeDenseClusters(
        rects: List<RectF>,
        imageArea: Float,
        maxMergedHeight: Float?
    ): List<RectF> {
        if (rects.size <= DENSE_CLUSTER_MIN_COUNT) return rects

        // 第二阶段：对前面成对合并后仍残留的密集小框做连通分量(BFS)聚类。
        // 场景是一堆挨得很近、单独看都达不到成对合并阈值的碎块（如手写拟声词），整体却应视为一个区域。
        val visited = BooleanArray(rects.size)
        val result = ArrayList<RectF>(rects.size)

        for (start in rects.indices) {
            if (visited[start]) continue
            val component = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component.add(current)
                for (next in rects.indices) {
                    if (visited[next]) continue
                    if (!isDenseNeighbor(rects[current], rects[next])) continue
                    visited[next] = true
                    queue.add(next)
                }
            }

            // 只有“成员够多”且“合并后不会占据整页过大比例”时才整体合并；否则原样保留各成员，
            // 避免把稀疏分布或跨大半页的框误聚成一个巨框。
            if (component.size >= DENSE_CLUSTER_MIN_COUNT) {
                val union = unionOfComponent(rects, component)
                val unionArea = max(0f, union.width()) * max(0f, union.height())
                if (isMergedHeightAllowed(union, maxMergedHeight) &&
                    unionArea / imageArea <= DENSE_CLUSTER_MAX_UNION_FRACTION
                ) {
                    result.add(union)
                    continue
                }
            }

            for (index in component) {
                result.add(RectF(rects[index]))
            }
        }
        return result
    }

    private fun isDenseNeighbor(a: RectF, b: RectF): Boolean {
        if (RectF.intersects(a, b)) return true

        // Reuse first-stage adaptive padding and yGap limit to prevent倒挂:
        // second stage was more permissive (fixed 56px pad, no yGap, 28% union)
        // than first stage (8-56px adaptive, yGap 36-140, 20% union), causing
        // link chains to merge independent bubbles in dense layouts.
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        if (areaA <= 0f || areaB <= 0f) return false
        val minArea = min(areaA, areaB)

        // Use the same sizeT formula as shouldMergeRects (line 278):
        // small fragments get generous pad, large rects get strict pad.
        val imageArea = (areaA + areaB) * 5f // Rough estimate for sizeT denominator
        val sizeT = sqrt((minArea / imageArea) / MERGE_SIZE_REF_AREA).coerceIn(0f, 1f)
        val pad = lerp(MERGE_PAD_MAX, MERGE_PAD_MIN, sizeT)

        val centerAY = (a.top + a.bottom) * 0.5f
        val centerBY = (b.top + b.bottom) * 0.5f
        val yGap = abs(centerAY - centerBY)
        val yGapLimit = lerp(MERGE_Y_GAP_MAX, MERGE_Y_GAP_MIN, sizeT)
        if (yGap > yGapLimit) return false

        val expandedA = RectF(a.left - pad, a.top - pad, a.right + pad, a.bottom + pad)
        val expandedB = RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
        return RectF.intersects(expandedA, b) || RectF.intersects(expandedB, a)
    }

    private fun shouldMergeShortTextGroups(
        a: MutableBubbleGroup,
        b: MutableBubbleGroup,
        imageArea: Float,
        maxMergedHeight: Float?
    ): Boolean {
        if (a.source != BubbleSource.TEXT_DETECTOR || b.source != BubbleSource.TEXT_DETECTOR) return false
        if (a.maskContour != null || b.maskContour != null) return false
        if (!isShortText(a.text) || !isShortText(b.text)) return false

        val union = unionRects(a.rect, b.rect)
        if (!isMergedHeightAllowed(union, maxMergedHeight)) return false
        val unionArea = max(0f, union.width()) * max(0f, union.height())
        if (unionArea / imageArea > SHORT_TEXT_MAX_UNION_FRACTION) return false

        val centerAX = (a.rect.left + a.rect.right) * 0.5f
        val centerAY = (a.rect.top + a.rect.bottom) * 0.5f
        val centerBX = (b.rect.left + b.rect.right) * 0.5f
        val centerBY = (b.rect.top + b.rect.bottom) * 0.5f
        val dx = abs(centerAX - centerBX)
        val dy = abs(centerAY - centerBY)
        val maxWidth = max(a.rect.width(), b.rect.width()).coerceAtLeast(1f)
        val maxHeight = max(a.rect.height(), b.rect.height()).coerceAtLeast(1f)

        val edgeXGap = edgeGap(a.rect.left, a.rect.right, b.rect.left, b.rect.right)
        val edgeYGap = edgeGap(a.rect.top, a.rect.bottom, b.rect.top, b.rect.bottom)
        val overlapX = overlapLength(a.rect.left, a.rect.right, b.rect.left, b.rect.right)
        val overlapY = overlapLength(a.rect.top, a.rect.bottom, b.rect.top, b.rect.bottom)
        val minWidth = min(a.rect.width(), b.rect.width()).coerceAtLeast(1f)
        val minHeight = min(a.rect.height(), b.rect.height()).coerceAtLeast(1f)
        val centerXAligned = dx <= minWidth * VERTICAL_STACK_CENTER_X_MIN_WIDTH_RATIO + VERTICAL_STACK_CENTER_X_MIN_WIDTH_PAD

        val verticalStacked =
            (dx <= maxWidth * VERTICAL_STACK_CENTER_X_RATIO + VERTICAL_STACK_CENTER_X_PAD || centerXAligned) &&
                edgeYGap <= maxHeight * VERTICAL_STACK_EDGE_Y_RATIO + VERTICAL_STACK_EDGE_Y_PAD &&
                (overlapX >= minWidth * VERTICAL_STACK_MIN_X_OVERLAP_RATIO ||
                    edgeXGap <= minWidth * VERTICAL_STACK_EDGE_X_RATIO + VERTICAL_STACK_EDGE_X_PAD)
        if (verticalStacked) return true

        val horizontalAligned =
            dy <= maxHeight * HORIZONTAL_JOIN_CENTER_Y_RATIO + HORIZONTAL_JOIN_CENTER_Y_PAD &&
                edgeXGap <= maxWidth * HORIZONTAL_JOIN_EDGE_X_RATIO + HORIZONTAL_JOIN_EDGE_X_PAD &&
                overlapY >= minHeight * HORIZONTAL_JOIN_MIN_Y_OVERLAP_RATIO
        return horizontalAligned &&
            edgeYGap <= maxHeight * HORIZONTAL_JOIN_EDGE_Y_RATIO + HORIZONTAL_JOIN_EDGE_Y_PAD
    }

    private fun isShortText(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val compactLength = normalized.count { !it.isWhitespace() && !isShortTextIgnoredChar(it) }
        if (compactLength <= 0) return false
        if (looksLikeLatinOrHangulText(normalized)) {
            if (words.size > 1) {
                return words.size <= LATIN_SHORT_TEXT_MAX_WORDS &&
                    compactLength < LATIN_SHORT_TEXT_MAX_CHARS
            }
            return compactLength < LATIN_SHORT_TEXT_MAX_CHARS
        }
        if (words.size > 1) {
            return words.size <= SHORT_TEXT_MAX_WORDS
        }
        return compactLength < SHORT_TEXT_MAX_CHARS
    }

    private fun isShortTextIgnoredChar(char: Char): Boolean {
        return char in SHORT_TEXT_IGNORED_CHARS
    }

    private fun looksLikeLatinOrHangulText(text: String): Boolean {
        var letterCount = 0
        var latinOrHangulCount = 0
        for (char in text) {
            if (!char.isLetter()) continue
            letterCount++
            if (char in 'A'..'Z' || char in 'a'..'z' || char.code in HANGUL_SYLLABLE_START..HANGUL_SYLLABLE_END) {
                latinOrHangulCount++
            }
        }
        return letterCount > 0 && latinOrHangulCount * 2 >= letterCount
    }

    private fun edgeGap(startA: Float, endA: Float, startB: Float, endB: Float): Float {
        return when {
            endA < startB -> startB - endA
            endB < startA -> startA - endB
            else -> 0f
        }
    }

    private fun overlapLength(startA: Float, endA: Float, startB: Float, endB: Float): Float {
        return max(0f, min(endA, endB) - max(startA, startB))
    }

    private fun mergeTextsByReadingOrder(a: MutableBubbleGroup, b: MutableBubbleGroup): String {
        val items = listOf(a, b).sortedWith(
            compareBy<MutableBubbleGroup> { it.rect.top }.thenBy { it.rect.left }
        )
        return items.joinToString("\n") { it.text.trim() }.trim()
    }

    private fun unionOfComponent(rects: List<RectF>, indices: List<Int>): RectF {
        val first = rects[indices.first()]
        var left = first.left
        var top = first.top
        var right = first.right
        var bottom = first.bottom
        for (i in 1 until indices.size) {
            val rect = rects[indices[i]]
            left = min(left, rect.left)
            top = min(top, rect.top)
            right = max(right, rect.right)
            bottom = max(bottom, rect.bottom)
        }
        return RectF(left, top, right, bottom)
    }

    private fun shouldMergeRects(
        a: RectF,
        b: RectF,
        union: RectF,
        imageArea: Float,
        maxMergedHeight: Float?
    ): Boolean {
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        if (areaA <= 0f || areaB <= 0f) return false
        if (!isMergedHeightAllowed(union, maxMergedHeight)) return false
        val minArea = min(areaA, areaB)
        // 一框几乎被另一框包住时直接合并，不再走下面的阈值判断。
        val overlapOverMin = overlapOverMinArea(a, b, minArea)
        if (overlapOverMin >= MERGE_OVERLAP_MIN_RATIO) return true

        // 阈值随框大小自适应：sizeT 接近 0 表示小框（噪点/拆碎的文字），用更宽松的间距和更低的 IoU 把它们粘回去；
        // 接近 1 表示大框，收紧标准以免把相邻的独立气泡误并。
        val sizeT = sqrt((minArea / imageArea) / MERGE_SIZE_REF_AREA).coerceIn(0f, 1f)
        val pad = lerp(MERGE_PAD_MAX, MERGE_PAD_MIN, sizeT)
        val iouThreshold = lerp(MERGE_IOU_SMALL, MERGE_IOU_LARGE, sizeT)

        // 合并后会占据整页过大比例时放弃，避免把跨页的多个气泡塌缩成一个巨框。
        val unionArea = max(0f, union.width()) * max(0f, union.height())
        if (unionArea / imageArea >= MERGE_MAX_UNION_FRACTION) return false

        // 垂直中心相距过远的不合并：同一气泡被拆出的碎块通常上下贴近，限制 y 间距可挡掉竖排相邻气泡。
        val centerAY = (a.top + a.bottom) * 0.5f
        val centerBY = (b.top + b.bottom) * 0.5f
        val yGap = abs(centerAY - centerBY)
        val yGapLimit = lerp(MERGE_Y_GAP_MAX, MERGE_Y_GAP_MIN, sizeT)
        if (yGap > yGapLimit) return false

        if (iou(a, b) >= iouThreshold) return true
        // IoU 不够时退一步：两框各按 pad 外扩后若相交也算邻接，用于粘合“挨着但不重叠”的碎块。
        val expandedA = RectF(a.left - pad, a.top - pad, a.right + pad, a.bottom + pad)
        val expandedB = RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
        return RectF.intersects(expandedA, b) || RectF.intersects(expandedB, a)
    }

    private fun isMergedHeightAllowed(rect: RectF, maxMergedHeight: Float?): Boolean {
        return maxMergedHeight == null || rect.height() < maxMergedHeight
    }

    private fun overlapOverMinArea(a: RectF, b: RectF, minArea: Float): Float {
        if (minArea <= 0f) return 0f
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        return inter / minArea
    }

    private fun unionRects(a: RectF, b: RectF): RectF {
        return RectF(
            min(a.left, b.left),
            min(a.top, b.top),
            max(a.right, b.right),
            max(a.bottom, b.bottom)
        )
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t.coerceIn(0f, 1f)
    }

    private const val MERGE_PAD_MAX = 56f
    private const val MERGE_PAD_MIN = 8f
    private const val MERGE_SIZE_REF_AREA = 0.02f
    private const val MERGE_IOU_SMALL = 0.07f
    private const val MERGE_IOU_LARGE = 0.28f
    private const val MERGE_OVERLAP_MIN_RATIO = 0.2f
    private const val MERGE_MAX_UNION_FRACTION = 0.2f
    private const val MERGE_Y_GAP_MAX = 140f
    private const val MERGE_Y_GAP_MIN = 36f
    private const val DENSE_CLUSTER_MIN_COUNT = 3
    private const val DENSE_CLUSTER_PAD = 56f
    private const val DENSE_CLUSTER_MAX_UNION_FRACTION = 0.28f
    private const val SHORT_TEXT_MAX_CHARS = 6
    private const val SHORT_TEXT_MAX_WORDS = 2
    private const val LATIN_SHORT_TEXT_MAX_CHARS = 28
    private const val LATIN_SHORT_TEXT_MAX_WORDS = 6
    private const val SHORT_TEXT_MAX_UNION_FRACTION = 0.14f
    private const val VERTICAL_STACK_CENTER_X_RATIO = 4.8f
    private const val VERTICAL_STACK_CENTER_X_PAD = 138f
    private const val VERTICAL_STACK_CENTER_X_MIN_WIDTH_RATIO = 2.0f
    private const val VERTICAL_STACK_CENTER_X_MIN_WIDTH_PAD = 36f
    private const val VERTICAL_STACK_EDGE_Y_RATIO = 11.5f
    private const val VERTICAL_STACK_EDGE_Y_PAD = 330f
    private const val VERTICAL_STACK_MIN_X_OVERLAP_RATIO = 0.02f
    private const val VERTICAL_STACK_EDGE_X_RATIO = 2.2f
    private const val VERTICAL_STACK_EDGE_X_PAD = 58f
    private const val HORIZONTAL_JOIN_CENTER_Y_RATIO = 2.0f
    private const val HORIZONTAL_JOIN_CENTER_Y_PAD = 44f
    private const val HORIZONTAL_JOIN_EDGE_X_RATIO = 2.5f
    private const val HORIZONTAL_JOIN_EDGE_X_PAD = 74f
    private const val HORIZONTAL_JOIN_MIN_Y_OVERLAP_RATIO = 0.14f
    private const val HORIZONTAL_JOIN_EDGE_Y_RATIO = 1.8f
    private const val HORIZONTAL_JOIN_EDGE_Y_PAD = 40f
    private const val HANGUL_SYLLABLE_START = 0xAC00
    private const val HANGUL_SYLLABLE_END = 0xD7A3
    private val SHORT_TEXT_IGNORED_CHARS = setOf(
        '.', ',', '!', '?', ':', ';', '-', '_', '~', '·', '…',
        '，', '。', '！', '？', '：', '；', '、', '·', '・',
        '「', '」', '『', '』', '（', '）', '(', ')', '[', ']', '{', '}',
        '"', '\'', '“', '”', '‘', '’'
    )

    private data class MutableBubbleGroup(
        val rect: RectF,
        val text: String,
        val source: BubbleSource,
        val maskContour: FloatArray?
    ) {
        fun mergeWith(other: MutableBubbleGroup): MutableBubbleGroup {
            return MutableBubbleGroup(
                rect = unionRects(rect, other.rect),
                text = mergeTextsByReadingOrder(this, other),
                source = source,
                maskContour = null
            )
        }

        fun toOcrBubble(id: Int): OcrBubble {
            return OcrBubble(
                id = id,
                rect = RectF(rect),
                text = text,
                source = source,
                maskContour = maskContour
            )
        }

        fun toBubbleTranslation(id: Int): BubbleTranslation {
            return BubbleTranslation.pending(
                id = id,
                rect = RectF(rect),
                originalText = text,
                source = source,
                maskContour = maskContour
            )
        }

        companion object {
            fun from(bubble: OcrBubble): MutableBubbleGroup {
                return MutableBubbleGroup(
                    rect = RectF(bubble.rect),
                    text = bubble.text,
                    source = bubble.source,
                    maskContour = bubble.maskContour
                )
            }

        }
    }
}
