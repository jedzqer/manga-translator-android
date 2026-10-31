package com.manga.translate.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.scale
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.TranslationCoreDefaults
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.PerformanceTrace
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.platform.DETECTION_MAX_EDGE
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.StitchedBitmapCropSource
import com.manga.translate.platform.recycleSafely
import com.manga.translate.settings.SettingsStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class PageRegionDetectionMode {
    FULL,
    TILED_LONG
}

internal data class DetectionTile(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    fun toRectF(): RectF {
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
}

internal data class BubblePriorityCandidate(
    val confidence: Float,
    val hasMaskContour: Boolean,
    val area: Float,
    val touchesInternalTileBoundary: Boolean = false
)

private data class DeduplicatedBubbleGroup(
    val detection: BubbleDetection,
    val suppressionRect: RectF
)

private data class TiledBubbleDetection(
    val detection: BubbleDetection,
    val touchesInternalTileBoundary: Boolean,
    val tileIndex: Int
)

private data class TiledDetectionRunResult(
    val succeeded: Boolean,
    val failedTileCount: Int,
    val tileCount: Int,
    val tiles: List<DetectionTile> = emptyList()
) {
    val complete: Boolean
        get() = failedTileCount == 0
}

internal fun shouldUseLongImageTiling(pageWidth: Int, pageHeight: Int): Boolean {
    if (pageWidth <= 0 || pageHeight <= 0) return false
    if (pageHeight < LONG_IMAGE_MIN_HEIGHT_PX) return false
    return pageHeight / pageWidth.toFloat() > LONG_IMAGE_ASPECT_THRESHOLD
}

/**
 * Unified tiny region filter with configurable thresholds.
 * Text detector uses 6x16px absolute bounds; bubble detector uses 12x28px scaled bounds.
 */
private fun isTinyErrorRegion(
    rect: RectF,
    imageWidth: Int,
    imageHeight: Int,
    minShortSidePx: Float,
    minLongSidePx: Float,
    shortSideRatio: Float,
    longSideRatio: Float,
    maxAreaRatio: Float
): Boolean {
    val width = rect.width().coerceAtLeast(0f)
    val height = rect.height().coerceAtLeast(0f)
    if (width <= 0f || height <= 0f) return true

    val shortSide = min(width, height)
    val longSide = max(width, height)
    val imageArea = (imageWidth.toLong() * imageHeight.toLong())
        .toFloat()
        .coerceAtLeast(1f)
    val areaRatio = (width * height) / imageArea

    val imageMinSide = min(imageWidth, imageHeight).toFloat().coerceAtLeast(1f)
    val maxShortSide = max(minShortSidePx, imageMinSide * shortSideRatio)
    val maxLongSide = max(minLongSidePx, imageMinSide * longSideRatio)

    return shortSide <= maxShortSide &&
        longSide <= maxLongSide &&
        areaRatio <= maxAreaRatio
}

internal fun isTinyTextErrorRegion(rect: RectF, imageWidth: Int, imageHeight: Int): Boolean {
    return isTinyErrorRegion(
        rect = rect,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        minShortSidePx = TINY_TEXT_SHORT_SIDE_MAX_PX,
        minLongSidePx = TINY_TEXT_LONG_SIDE_MAX_PX,
        shortSideRatio = 0f,  // Text uses absolute bounds, no image-relative scaling
        longSideRatio = 0f,
        maxAreaRatio = TINY_TEXT_MAX_AREA_RATIO
    )
}

private const val TINY_TEXT_SHORT_SIDE_MAX_PX = 6f
private const val TINY_TEXT_LONG_SIDE_MAX_PX = 16f
private const val TINY_TEXT_MAX_AREA_RATIO = 0.0002f

internal fun planLongImageBubbleDetectionTiles(
    pageWidth: Int,
    pageHeight: Int
): List<DetectionTile> {
    if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
    val tileHeight = longImageBubbleDetectionTileHeight(pageWidth, pageHeight)
    if (tileHeight <= 0) return emptyList()
    return planDetectionAxisStarts(pageHeight, tileHeight).map { tileTop ->
        DetectionTile(
            left = 0,
            top = tileTop,
            right = pageWidth,
            bottom = min(pageHeight, tileTop + tileHeight)
        )
    }
}

internal fun longImageBubbleDetectionTileHeight(pageWidth: Int, pageHeight: Int): Int {
    if (pageWidth <= 0 || pageHeight <= 0) return 0
    val baseTileHeight = (pageWidth * LONG_IMAGE_TILE_HEIGHT_WIDTH_RATIO)
        .roundToInt()
        .coerceAtMost(pageHeight)
    val remainingHeight = pageHeight - baseTileHeight
    return if (
        remainingHeight > 0 &&
        remainingHeight <= (baseTileHeight * LONG_IMAGE_SMALL_REMAINDER_RATIO).roundToInt()
    ) {
        // A page only slightly taller than one tile is better handled as one
        // full-height crop than as two almost-identical model invocations.
        pageHeight
    } else {
        baseTileHeight
    }
}

internal fun longImageBubbleDetectionInputHeight(bitmapHeight: Int): Int {
    if (bitmapHeight <= 0) return 0
    return max(1, (bitmapHeight * LONG_IMAGE_BUBBLE_VERTICAL_SCALE).roundToInt())
}

internal fun planLongImageTextDetectionTiles(
    pageWidth: Int,
    pageHeight: Int
): List<DetectionTile> {
    if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
    val tileHeight = longImageTextDetectionTileHeight(pageWidth, pageHeight)
    if (tileHeight <= 0) return emptyList()
    val tops = planDetectionAxisStarts(pageHeight, tileHeight)
    return tops.map { tileTop ->
        DetectionTile(
            left = 0,
            top = tileTop,
            right = pageWidth,
            bottom = min(pageHeight, tileTop + tileHeight)
        )
    }
}

internal fun planPaddleTextDetectionTiles(
    pageWidth: Int,
    pageHeight: Int
): List<DetectionTile> {
    if (pageWidth <= 0 || pageHeight <= 0) return emptyList()
    if (!shouldUseLongImageTiling(pageWidth, pageHeight)) {
        return listOf(DetectionTile(0, 0, pageWidth, pageHeight))
    }
    val tileHeight = min(
        pageHeight,
        max(PADDLE_MIN_TILE_HEIGHT_PX, (pageWidth * PADDLE_TILE_HEIGHT_WIDTH_RATIO).roundToInt())
    )
    if (tileHeight >= pageHeight) {
        return listOf(DetectionTile(0, 0, pageWidth, pageHeight))
    }
    val stride = max(1, (tileHeight * (1f - PADDLE_TILE_OVERLAP_RATIO)).roundToInt())
    val starts = ArrayList<Int>()
    var top = 0
    while (top + tileHeight < pageHeight) {
        starts.add(top)
        top += stride
    }
    val finalTop = pageHeight - tileHeight
    if (starts.lastOrNull() != finalTop) starts.add(finalTop)
    return starts.map { tileTop ->
        DetectionTile(0, tileTop, pageWidth, tileTop + tileHeight)
    }
}

private fun planDetectionAxisStarts(
    length: Int,
    tileSize: Int
): List<Int> {
    if (length <= 0 || tileSize <= 0) return emptyList()
    if (tileSize >= length) return listOf(0)
    return (0 until length step tileSize).toList()
}

internal fun isDetectionAtInternalTileBottom(
    rect: RectF,
    tileBitmapHeight: Int,
    tileBottom: Int,
    pageHeight: Int
): Boolean {
    if (tileBitmapHeight <= 0 || tileBottom >= pageHeight) return false
    val margin = tileBoundaryMargin(tileBitmapHeight)
    return rect.bottom >= tileBitmapHeight - margin
}

/** Band around the seam between two stitched pages, or null when either side is too thin. */
internal fun planSeamDetectionBand(width: Int, seamY: Int, height: Int): RectF? {
    if (width <= 0 || seamY <= 0 || seamY >= height) return null
    val halfExtent = width * SEAM_BAND_HALF_HEIGHT_RATIO
    val top = max(0f, seamY - halfExtent)
    val bottom = min(height.toFloat(), seamY + halfExtent)
    if (seamY - top < SEAM_BAND_MIN_SIDE_PX || bottom - seamY < SEAM_BAND_MIN_SIDE_PX) return null
    return RectF(0f, top, width.toFloat(), bottom)
}

/**
 * A seam bubble must extend past the seam on both sides and must not be cut by a band
 * edge, otherwise the band would only reproduce the truncation it is meant to repair.
 */
internal fun isSeamCrossingDetection(rect: RectF, band: RectF, seamY: Int, stitchedHeight: Int): Boolean {
    val margin = tileBoundaryMargin(band.height().roundToInt())
    if (rect.top > seamY - margin || rect.bottom < seamY + margin) return false
    // A band clamped to the stitched page edge is not a cut.
    if (band.top > 0f && rect.top <= band.top + margin) return false
    if (band.bottom < stitchedHeight && rect.bottom >= band.bottom - margin) return false
    return true
}

internal fun isDetectionAtReplayTileTop(
    rect: RectF,
    tileBitmapHeight: Int
): Boolean {
    if (tileBitmapHeight <= 0) return false
    return rect.top <= tileBoundaryMargin(tileBitmapHeight)
}

internal fun shouldDiscardReplayTileTopFragments(
    overlapsPreviousTile: Boolean,
    tileBottom: Int,
    pageHeight: Int
): Boolean {
    return overlapsPreviousTile && tileBottom < pageHeight
}

/** Both candidate and retained detections must use page coordinates. */
internal fun isReplayDetectionCovered(
    candidate: BubbleDetection,
    retainedDetections: List<BubbleDetection>
): Boolean = retainedDetections.any { retained ->
    // Mere overlap is insufficient: the replay may have recovered missing geometry.
    retained.classId == candidate.classId && rectContains(retained.rect, candidate.rect)
}

internal fun adaptiveNextTileTop(
    tile: DetectionTile,
    pageHeight: Int,
    tileBitmapHeight: Int,
    bottomEdgeRects: List<RectF>
): Int {
    if (tile.bottom >= pageHeight) return pageHeight
    if (bottomEdgeRects.isEmpty() || tileBitmapHeight <= 0) return tile.bottom
    if (tile.height <= 1) return tile.bottom

    val replayPadding = tileBoundaryMargin(tileBitmapHeight) *
        ADAPTIVE_TILE_REPLAY_PADDING_MULTIPLIER
    val earliestLocalTop = bottomEdgeRects.minOf { it.top }
    val desiredTop = tile.top +
        ((earliestLocalTop - replayPadding).coerceAtLeast(0f) * tile.height / tileBitmapHeight)
            .roundToInt()
    val minimumAdvance = max(
        1,
        (tile.height * ADAPTIVE_TILE_MIN_ADVANCE_RATIO).roundToInt()
    ).coerceAtMost(tile.height - 1)
    return desiredTop.coerceIn(
        tile.top + minimumAdvance,
        tile.bottom - 1
    )
}

private fun buildDetectionTile(
    pageWidth: Int,
    pageHeight: Int,
    tileTop: Int,
    tileHeight: Int
): DetectionTile {
    return DetectionTile(
        left = 0,
        top = tileTop,
        right = pageWidth,
        bottom = min(pageHeight, tileTop + tileHeight)
    )
}

internal fun longImageTextDetectionTileHeight(pageWidth: Int, pageHeight: Int): Int {
    if (pageWidth <= 0 || pageHeight <= 0) return 0
    return min(
        (pageWidth * LONG_IMAGE_TEXT_TILE_HEIGHT_WIDTH_RATIO).roundToInt(),
        pageHeight
    )
}

internal fun buildTileTextSuppressionRects(
    pageBubbleRects: List<RectF>,
    tile: DetectionTile,
    tileBitmapWidth: Int,
    tileBitmapHeight: Int
): List<RectF> {
    if (
        pageBubbleRects.isEmpty() ||
        tile.width <= 0 || tile.height <= 0 ||
        tileBitmapWidth <= 0 || tileBitmapHeight <= 0
    ) {
        return emptyList()
    }
    val scaleX = tileBitmapWidth / tile.width.toFloat()
    val scaleY = tileBitmapHeight / tile.height.toFloat()
    return pageBubbleRects.mapNotNull { pageRect ->
        val intersectsTile =
            pageRect.right > tile.left && pageRect.left < tile.right &&
                pageRect.bottom > tile.top && pageRect.top < tile.bottom
        if (!intersectsTile) return@mapNotNull null

        val localRect = RectF(
            (pageRect.left - tile.left) * scaleX,
            (pageRect.top - tile.top) * scaleY,
            (pageRect.right - tile.left) * scaleX,
            (pageRect.bottom - tile.top) * scaleY
        )
        val pad = max(
            TranslationCoreDefaults.PageRegionMaskExpandMin,
            max(1f, localRect.height()) * TranslationCoreDefaults.PageRegionMaskExpandRatio
        )
        RectF(
            (localRect.left - pad).coerceIn(0f, tileBitmapWidth.toFloat()),
            (localRect.top - pad).coerceIn(0f, tileBitmapHeight.toFloat()),
            (localRect.right + pad).coerceIn(0f, tileBitmapWidth.toFloat()),
            (localRect.bottom + pad).coerceIn(0f, tileBitmapHeight.toFloat())
        )
    }
}

internal fun shouldDeduplicateTileCandidates(
    firstTileIndex: Int,
    secondTileIndex: Int,
    firstRect: RectF,
    secondRect: RectF
): Boolean {
    return firstTileIndex != secondTileIndex &&
        shouldTreatRectsAsSameBubbleForDedup(firstRect, secondRect)
}

internal fun shouldUnionTileBubbleCandidates(
    candidates: List<BubblePriorityCandidate>
): Boolean {
    return candidates.size > 1 && candidates.all { it.touchesInternalTileBoundary }
}

internal fun unionDetectionRects(rects: List<RectF>): RectF? {
    val first = rects.firstOrNull() ?: return null
    var left = first.left
    var top = first.top
    var right = first.right
    var bottom = first.bottom
    for (index in 1 until rects.size) {
        val rect = rects[index]
        left = min(left, rect.left)
        top = min(top, rect.top)
        right = max(right, rect.right)
        bottom = max(bottom, rect.bottom)
    }
    return RectF(left, top, right, bottom)
}

internal fun remapTileMaskContourToPage(
    contour: FloatArray,
    tileTop: Int,
    tileHeight: Int,
    pageWidth: Int,
    pageHeight: Int,
    tileLeft: Int = 0,
    tileWidth: Int = pageWidth
): FloatArray {
    if (contour.isEmpty()) return contour
    val result = FloatArray(contour.size)
    val safePageWidth = pageWidth.coerceAtLeast(1)
    val safePageHeight = pageHeight.coerceAtLeast(1)
    val safeTileWidth = tileWidth.coerceAtLeast(1)
    val safeTileHeight = tileHeight.coerceAtLeast(1)
    var index = 0
    while (index + 1 < contour.size) {
        val x = contour[index].coerceIn(0f, 1f)
        val y = contour[index + 1].coerceIn(0f, 1f)
        result[index] = ((tileLeft + x * safeTileWidth) / safePageWidth.toFloat()).coerceIn(0f, 1f)
        result[index + 1] = ((tileTop + y * safeTileHeight) / safePageHeight.toFloat()).coerceIn(0f, 1f)
        index += 2
    }
    return result
}

/**
 * Merges page-normalized tile contours into the single polygon representation used by renderers.
 * The horizontal union envelope preserves the full extent of masks split by adjacent tile edges.
 */
internal fun mergePageMaskContours(
    contours: List<FloatArray>,
    pageHeight: Int
): FloatArray? {
    val validContours = contours.filter { it.size >= 6 && it.size % 2 == 0 }
    if (validContours.isEmpty()) return null
    if (validContours.size == 1) return validContours.first().copyOf()

    var minY = 1f
    var maxY = 0f
    for (contour in validContours) {
        var index = 1
        while (index < contour.size) {
            val y = contour[index].coerceIn(0f, 1f)
            minY = min(minY, y)
            maxY = max(maxY, y)
            index += 2
        }
    }
    if (maxY - minY <= CONTOUR_COORD_EPSILON) return null

    val estimatedPixelRows = ((maxY - minY) * pageHeight.coerceAtLeast(1)).roundToInt()
    val sampleCount = estimatedPixelRows.coerceIn(
        MERGED_CONTOUR_MIN_SAMPLE_ROWS,
        MERGED_CONTOUR_MAX_SAMPLE_ROWS
    )
    val leftEdge = ArrayList<Float>((sampleCount + 1) * 2)
    val rightEdge = ArrayList<Float>((sampleCount + 1) * 2)
    for (sample in 0..sampleCount) {
        val y = minY + (maxY - minY) * sample / sampleCount.toFloat()
        var rowLeft = Float.POSITIVE_INFINITY
        var rowRight = Float.NEGATIVE_INFINITY
        for (contour in validContours) {
            val bounds = contourHorizontalBounds(contour, y) ?: continue
            rowLeft = min(rowLeft, bounds.first)
            rowRight = max(rowRight, bounds.second)
        }
        if (!rowLeft.isFinite() || !rowRight.isFinite() || rowRight <= rowLeft) continue
        leftEdge.add(rowLeft.coerceIn(0f, 1f))
        leftEdge.add(y.coerceIn(0f, 1f))
        rightEdge.add(rowRight.coerceIn(0f, 1f))
        rightEdge.add(y.coerceIn(0f, 1f))
    }
    if (leftEdge.size < 4) return null

    val polygon = FloatArray(leftEdge.size + rightEdge.size)
    leftEdge.toFloatArray().copyInto(polygon)
    var outputIndex = leftEdge.size
    for (index in rightEdge.size - 2 downTo 0 step 2) {
        polygon[outputIndex] = rightEdge[index]
        polygon[outputIndex + 1] = rightEdge[index + 1]
        outputIndex += 2
    }
    return polygon
}

private fun contourHorizontalBounds(contour: FloatArray, y: Float): Pair<Float, Float>? {
    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    val pointCount = contour.size / 2
    for (pointIndex in 0 until pointCount) {
        val nextPointIndex = (pointIndex + 1) % pointCount
        val x1 = contour[pointIndex * 2]
        val y1 = contour[pointIndex * 2 + 1]
        val x2 = contour[nextPointIndex * 2]
        val y2 = contour[nextPointIndex * 2 + 1]
        val deltaY = y2 - y1
        if (abs(deltaY) <= CONTOUR_COORD_EPSILON) {
            if (abs(y - y1) <= CONTOUR_COORD_EPSILON) {
                minX = min(minX, min(x1, x2))
                maxX = max(maxX, max(x1, x2))
            }
            continue
        }
        val edgeMinY = min(y1, y2)
        val edgeMaxY = max(y1, y2)
        if (y < edgeMinY - CONTOUR_COORD_EPSILON || y > edgeMaxY + CONTOUR_COORD_EPSILON) {
            continue
        }
        val ratio = ((y - y1) / deltaY).coerceIn(0f, 1f)
        val x = x1 + (x2 - x1) * ratio
        minX = min(minX, x)
        maxX = max(maxX, x)
    }
    return if (minX.isFinite() && maxX.isFinite()) minX to maxX else null
}

internal fun choosePreferredBubbleCandidateIndex(
    candidates: List<BubblePriorityCandidate>
): Int {
    if (candidates.isEmpty()) return -1
    var bestIndex = 0
    for (index in 1 until candidates.size) {
        if (compareBubblePriority(candidates[index], candidates[bestIndex]) > 0) {
            bestIndex = index
        }
    }
    return bestIndex
}

internal fun shouldTreatRectsAsSameBubbleForDedup(a: RectF, b: RectF): Boolean {
    val areaA = rectAreaValue(a)
    val areaB = rectAreaValue(b)
    if (areaA <= 0f || areaB <= 0f) return false
    if (rectIou(a, b) >= BUBBLE_DEDUP_IOU_THRESHOLD) return true

    val minArea = min(areaA, areaB).coerceAtLeast(1f)
    val overlapOverMin = rectIntersectionArea(a, b) / minArea
    if (overlapOverMin >= BUBBLE_DEDUP_CONTAINMENT_THRESHOLD &&
        (rectContains(a, b) || rectContains(b, a))
    ) {
        return true
    }

    if (shouldTreatPartiallyShiftedRectsAsSameBubble(a, b, overlapOverMin)) {
        return true
    }
    // Tile seams often produce vertically stacked partial balloons with modest overlap.
    return shouldTreatVerticallySplitTileRectsAsSameBubble(a, b)
}

internal fun shouldFilterLongImageRegion(
    rect: RectF,
    pageWidth: Int,
    pageHeight: Int
): Boolean {
    if (!shouldUseLongImageTiling(pageWidth, pageHeight)) return false
    val width = rect.width().coerceAtLeast(0f)
    val height = rect.height().coerceAtLeast(0f)
    if (width <= 0f || height <= 0f) return true

    return height >= longImageMaxRegionHeight(pageWidth, pageHeight)
}

internal fun longImageMaxRegionHeight(pageWidth: Int, pageHeight: Int): Float {
    // Independent of tile height: tiles are sized for letterbox density on the fixed
    // 832 TFLite input, while this cap only rejects full-strip false positives.
    if (pageWidth <= 0) return 0f
    return pageWidth * LONG_IMAGE_MAX_REGION_HEIGHT_WIDTH_RATIO
}

/**
 * Rejoins balloon boxes that the detector split into overlapping halves.
 *
 * A single detected text line cannot belong to two different balloons, so a line that
 * straddles two mutually overlapping boxes without fitting inside either one is direct
 * evidence they are two fragments of one balloon. This catches splits that the
 * confidence-based NMS in [deduplicateBubbleDetections] leaves behind: the fragments'
 * centers drift too far apart to look like duplicate predictions, and unioning them is
 * the correct repair anyway, since suppressing one fragment would drop half the text.
 */
internal fun mergeBubblesSpannedByTextLines(
    balloons: List<BubbleDetection>,
    textLines: List<RectF>?,
    pageWidth: Int,
    pageHeight: Int
): List<BubbleDetection> {
    if (balloons.size <= 1 || textLines.isNullOrEmpty()) return balloons
    val imageArea = (pageWidth.toFloat() * pageHeight.toFloat()).coerceAtLeast(1f)
    val working = balloons.toMutableList()
    // Iterate to a fixed point: a union can reach a third fragment.
    var merged = true
    while (merged) {
        merged = false
        outer@ for (i in working.indices) {
            var j = i + 1
            while (j < working.size) {
                if (isBubblePairSpannedByAnyTextLine(working[i], working[j], textLines, imageArea)) {
                    working[i] = unionBubbleDetections(working[i], working[j], pageHeight)
                    working.removeAt(j)
                    merged = true
                    // Indices shifted; restart the scan rather than reasoning about them.
                    break@outer
                }
                j++
            }
        }
    }
    return working
}

private fun isBubblePairSpannedByAnyTextLine(
    a: BubbleDetection,
    b: BubbleDetection,
    textLines: List<RectF>,
    imageArea: Float
): Boolean {
    if (a.classId != b.classId) return false
    val rectA = a.rect
    val rectB = b.rect
    // Genuinely adjacent balloons barely overlap; fragments of one balloon share real area.
    if (rectIntersectionArea(rectA, rectB) /
        min(rectAreaValue(rectA), rectAreaValue(rectB)).coerceAtLeast(1f) <
        BUBBLE_SPAN_MIN_PAIR_OVERLAP
    ) {
        return false
    }
    val union = RectF(
        min(rectA.left, rectB.left),
        min(rectA.top, rectB.top),
        max(rectA.right, rectB.right),
        max(rectA.bottom, rectB.bottom)
    )
    if (rectAreaValue(union) / imageArea > BUBBLE_SPAN_MAX_UNION_FRACTION) return false
    return textLines.any { line -> isTextLineSpanningBubbles(line, rectA, rectB) }
}

private fun isTextLineSpanningBubbles(line: RectF, a: RectF, b: RectF): Boolean {
    val lineWidth = line.width()
    if (lineWidth <= 0f || line.height() <= 0f) return false
    // A line already inside one box says nothing about the pair.
    if (rectContainsHorizontally(a, line) || rectContainsHorizontally(b, line)) return false
    val overlapA = max(0f, min(line.right, a.right) - max(line.left, a.left)) / lineWidth
    val overlapB = max(0f, min(line.right, b.right) - max(line.left, b.left)) / lineWidth
    if (overlapA < BUBBLE_SPAN_MIN_LINE_OVERLAP || overlapB < BUBBLE_SPAN_MIN_LINE_OVERLAP) {
        return false
    }
    // The line must sit at a height both boxes actually cover, so stacked balloons that
    // merely share a column are not joined by an unrelated line.
    val centerY = (line.top + line.bottom) * 0.5f
    return centerY in a.top..a.bottom && centerY in b.top..b.bottom
}

private fun rectContainsHorizontally(container: RectF, line: RectF): Boolean {
    return container.left <= line.left && line.right <= container.right
}

private fun unionBubbleDetections(
    a: BubbleDetection,
    b: BubbleDetection,
    pageHeight: Int
): BubbleDetection {
    val base = if (a.confidence >= b.confidence) a else b
    return base.copy(
        rect = RectF(
            min(a.rect.left, b.rect.left),
            min(a.rect.top, b.rect.top),
            max(a.rect.right, b.rect.right),
            max(a.rect.bottom, b.rect.bottom)
        ),
        maskContour = mergePageMaskContours(
            listOfNotNull(a.maskContour, b.maskContour),
            pageHeight
        )
    )
}

internal class PageRegionDetector(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private var bubbleDetector: BubbleDetector? = null

    suspend fun detect(
        bitmap: Bitmap,
        logTag: String = "PageRegionDetector"
    ): PageRegionDetectionResult? {
        return PipelineBitmapDecoder.openCropSource(bitmap).use { cropSource ->
            detect(cropSource, bitmap.width, bitmap.height, logTag)
        }
    }

    suspend fun detect(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String = "PageRegionDetector"
    ): PageRegionDetectionResult? {
        val trace = PerformanceTrace(
            tag = logTag,
            operation = "detect:${pageWidth}x$pageHeight",
            enabled = settingsStore.loadModelIoLogging()
        )
        trace.attribute("detection", "bubbles_and_text")
        try {
            return trace.measure("model") {
                if (!shouldUseLongImageTiling(pageWidth, pageHeight)) {
                    trace.attribute("mode", "full")
                    return@measure detectFullPage(
                        cropSource,
                        pageWidth,
                        pageHeight,
                        logTag
                    )
                }
                trace.attribute("mode", "tiled")
                try {
                    detectLongImageTiledPage(
                        cropSource,
                        pageWidth,
                        pageHeight,
                        logTag,
                        trace
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.log(logTag, "Tiled page detection failed; trying full-page fallback", e)
                    trace.attribute("mode", "tiled_fallback_full")
                    try {
                        detectFullPage(
                            cropSource,
                            pageWidth,
                            pageHeight,
                            "$logTag[fallback]"
                        )
                    } catch (fallbackCancellation: CancellationException) {
                        throw fallbackCancellation
                    } catch (fallbackError: Exception) {
                        AppLogger.log(logTag, "Full-page fallback detection failed", fallbackError)
                        null
                    }
                }
            }
        } finally {
            trace.logSummary()
        }
    }

    private suspend fun detectFullPage(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): PageRegionDetectionResult? {
        val fullBitmap = cropSource.decodeRegion(
            RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()),
            maxEdge = DETECTION_MAX_EDGE
        ) ?: return null
        return try {
            detectSingleBitmap(fullBitmap, logTag)
                ?.remapToSource(pageWidth, pageHeight)
                ?.copy(detectionMode = PageRegionDetectionMode.FULL)
        } finally {
            fullBitmap.recycleSafely()
        }
    }

    /**
     * Detect bubbles cut by the seam between two adjacent pages. Page detection only
     * sees one file, so a bubble split across files is never closed there. A band
     * around the seam is re-detected on the stitched view; only balloons that clearly
     * cross the seam and are not cut by the band itself are returned, in [stitched]
     * coordinates (upper page space, lower page below `seamY`).
     */
    suspend fun detectSeamBubbles(
        stitched: StitchedBitmapCropSource,
        logTag: String = "PageRegionDetector"
    ): List<BubbleDetection> {
        val band = planSeamDetectionBand(stitched.width, stitched.seamY, stitched.height)
            ?: return emptyList()
        val bitmap = stitched.decodeRegion(band, maxEdge = DETECTION_MAX_EDGE) ?: return emptyList()
        val detected = try {
            detectSingleBitmap(bitmap, "$logTag[seam]")
                ?.remapToSource(band.width().roundToInt(), band.height().roundToInt())
        } finally {
            bitmap.recycleSafely()
        } ?: return emptyList()
        val bubbles = detected.bubbleDetections
            .map { it.copy(rect = it.rect.offsetBy(band.left, band.top), maskContour = null) }
            .filter { isSeamCrossingDetection(it.rect, band, stitched.seamY, stitched.height) }
        if (bubbles.isNotEmpty()) {
            AppLogger.log(logTag, "Seam detection found ${bubbles.size} bubble(s) across y=${stitched.seamY}")
        }
        return bubbles
    }

    private suspend fun detectLongImageTiledPage(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String,
        trace: PerformanceTrace? = null
    ): PageRegionDetectionResult? {
        val candidates = ArrayList<TiledBubbleDetection>()
        val run = appendLongImageBubbleCandidates(
            cropSource, pageWidth, pageHeight, candidates, logTag
        )
        trace?.attribute("dualTiles", run.tileCount)
        trace?.attribute("dualTileFailures", run.failedTileCount)
        // A partial tile set must never become a complete OCR cache.
        if (!run.succeeded || !run.complete) return null
        val groups = deduplicateBubbleDetections(candidates, pageHeight)
        val balloons = filterLongImageBubbleGroups(
            groups.filter { it.detection.classId == BubbleDetector.CLASS_BALLOON },
            pageWidth, pageHeight, logTag
        ).map { it.detection }
        val allText = groups.filter { it.detection.classId == BubbleDetector.CLASS_TEXT }.map { it.detection }
        val textRects = groups
            .filter { it.detection.classId == BubbleDetector.CLASS_TEXT }
            .map { it.detection.rect }
        return buildDetectionResult(
            width = pageWidth,
            height = pageHeight,
            detections = balloons,
            textBlocks = dualTextBlocks(filterOverlapping(
                textRects, balloons.map { it.rect }, TEXT_IOU_THRESHOLD
            ), pageWidth, pageHeight),
            detectionMode = PageRegionDetectionMode.TILED_LONG,
            allTextDetections = allText,
            tiles = run.tiles
        )
    }

    private suspend fun appendLongImageBubbleCandidates(
        cropSource: BitmapCropSource,
        pageWidth: Int,
        pageHeight: Int,
        bubbleDetections: MutableList<TiledBubbleDetection>,
        logTag: String
    ): TiledDetectionRunResult {
        val detector = getBubbleDetector(logTag) ?: run {
            AppLogger.log(logTag, "Dual detector unavailable")
            return TiledDetectionRunResult(false, failedTileCount = 0, tileCount = 0)
        }
        val tileHeight = longImageBubbleDetectionTileHeight(pageWidth, pageHeight)
        if (tileHeight <= 0) return TiledDetectionRunResult(false, failedTileCount = 0, tileCount = 0)
        AppLogger.log(
            logTag,
            "Long-image dual detection: contiguous adaptive tiles, " +
                "vertical scale=${(LONG_IMAGE_BUBBLE_VERTICAL_SCALE * 100).roundToInt()}%"
        )
        val tiles = ArrayList<DetectionTile>()
        var tileTop = 0
        var tileIndex = 0
        var failedTileCount = 0
        var successfulTileCount = 0
        var previousTileBottom = 0
        while (tileTop < pageHeight) {
            currentCoroutineContext().ensureActive()
            val tile = buildDetectionTile(
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                tileTop = tileTop,
                tileHeight = tileHeight
            )
            tiles.add(tile)
            val tileTag = "$logTag[dual tile ${tileIndex + 1} y=${tile.top}..${tile.bottom}]"
            var nextTileTop = tile.bottom
            var decodeThrew = false
            val tileBitmap = try {
                cropSource.decodeRegion(tile.toRectF(), maxEdge = DETECTION_MAX_EDGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                decodeThrew = true
                failedTileCount++
                AppLogger.log(tileTag, "bubble tile decode failed; skipping tile", e)
                null
            }
            if (tileBitmap == null) {
                if (!decodeThrew) {
                    failedTileCount++
                    AppLogger.log(tileTag, "bubble tile decode returned null; skipping tile")
                }
            } else {
                try {
                    val detectionBitmap = compressLongImageBubbleTile(tileBitmap)
                    if (detectionBitmap !== tileBitmap) {
                        tileBitmap.recycleSafely()
                    }
                    try {
                        val unified = detector.detectRegions(detectionBitmap)
                        val detections = filterTinyBubbleDetections(
                            unified.balloons, detectionBitmap, tileTag
                        ) + unified.textDetections
                        successfulTileCount++
                        val discardTopEdgeFragments = shouldDiscardReplayTileTopFragments(
                            overlapsPreviousTile = tile.top < previousTileBottom,
                            tileBottom = tile.bottom,
                            pageHeight = pageHeight
                        )
                        val (topEdgeFragments, replayCandidates) = if (discardTopEdgeFragments) {
                            detections.partition { detection ->
                                isBubbleDetectionAtReplayTileTop(
                                    detection = detection,
                                    tileBitmapHeight = detectionBitmap.height
                                )
                            }
                        } else {
                            emptyList<BubbleDetection>() to detections
                        }
                        // A top-edge detection may be the very target discarded at the
                        // preceding bottom edge. Only remove fragments actually covered
                        // by a retained detection; leave uncertain matches to page dedup.
                        // Keep top candidates out of replay triggers to preserve progress.
                        val retainedDetections = bubbleDetections.map { it.detection }
                        val recoveredTopCandidates = remapTileBubbleDetectionsToPage(
                            detections = topEdgeFragments,
                            tileBitmapWidth = detectionBitmap.width,
                            tileBitmapHeight = detectionBitmap.height,
                            tile = tile,
                            tileIndex = tileIndex,
                            pageWidth = pageWidth,
                            pageHeight = pageHeight
                        ).filterNot { isReplayDetectionCovered(it.detection, retainedDetections) }
                        val (bottomEdgeDetections, completeDetections) =
                            replayCandidates.partition { detection ->
                                isBubbleDetectionAtInternalTileBottom(
                                    detection = detection,
                                    tileBitmapHeight = detectionBitmap.height,
                                    tileBottom = tile.bottom,
                                    pageHeight = pageHeight
                                )
                            }
                        nextTileTop = adaptiveNextTileTop(
                            tile = tile,
                            pageHeight = pageHeight,
                            tileBitmapHeight = detectionBitmap.height,
                            bottomEdgeRects = bottomEdgeDetections.map { it.rect }
                        )
                        if (bottomEdgeDetections.isNotEmpty()) {
                            AppLogger.log(
                                tileTag,
                                "Replaying ${bottomEdgeDetections.size} bottom-edge bubble(s) " +
                                    "from y=$nextTileTop"
                            )
                        }
                        val discardedTopCount = topEdgeFragments.size - recoveredTopCandidates.size
                        if (discardedTopCount > 0) {
                            AppLogger.log(
                                tileTag,
                                "Dropped $discardedTopCount top-edge bubble fragment(s) " +
                                    "already covered by retained detections"
                            )
                        }
                        bubbleDetections.addAll(recoveredTopCandidates)
                        bubbleDetections.addAll(
                            remapTileBubbleDetectionsToPage(
                                detections = completeDetections,
                                tileBitmapWidth = detectionBitmap.width,
                                tileBitmapHeight = detectionBitmap.height,
                                tile = tile,
                                tileIndex = tileIndex,
                                pageWidth = pageWidth,
                                pageHeight = pageHeight
                            )
                        )
                    } finally {
                        if (detectionBitmap !== tileBitmap) {
                            detectionBitmap.recycleSafely()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedTileCount++
                    AppLogger.log(tileTag, "bubble tile detection failed; skipping tile", e)
                } finally {
                    tileBitmap.recycleSafely()
                }
            }
            previousTileBottom = tile.bottom
            tileTop = nextTileTop
            tileIndex++
        }
        if (failedTileCount > 0) {
            AppLogger.log(logTag, "Skipped $failedTileCount/$tileIndex bubble tile(s)")
        }
        return TiledDetectionRunResult(
            succeeded = successfulTileCount > 0,
            failedTileCount = failedTileCount,
            tileCount = tileIndex,
            tiles = tiles
        )
    }

    private fun compressLongImageBubbleTile(bitmap: Bitmap): Bitmap {
        val targetHeight = longImageBubbleDetectionInputHeight(bitmap.height)
        if (targetHeight <= 0 || targetHeight == bitmap.height) return bitmap
        return bitmap.scale(bitmap.width, targetHeight)
    }

    private fun detectSingleBitmap(
        bitmap: Bitmap,
        logTag: String
    ): PageRegionDetectionResult? {
        val raw = try {
            getBubbleDetector(logTag)?.detectRegions(bitmap) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "Dual page detection failed", e)
            return null
        }
        val balloons = filterTinyBubbleDetections(raw.balloons, bitmap, logTag)
        return buildDetectionResult(
            width = bitmap.width,
            height = bitmap.height,
            detections = balloons,
            textBlocks = dualTextBlocks(filterOverlapping(
                raw.freeTextRects, buildTextSuppressionRects(balloons, bitmap), TEXT_IOU_THRESHOLD
            ), bitmap.width, bitmap.height),
            allTextDetections = raw.textDetections,
            detectionComplete = raw.detectionComplete,
            detectionMode = PageRegionDetectionMode.FULL
        )
    }

    private fun buildTextSuppressionRects(
        detections: List<BubbleDetection>,
        bitmap: Bitmap
    ): List<RectF> {
        return detections.map { detection ->
            val rect = detection.rect
            val pad = max(
                TranslationCoreDefaults.PageRegionMaskExpandMin,
                max(1f, rect.height()) * TranslationCoreDefaults.PageRegionMaskExpandRatio
            )
            RectF(
                (rect.left - pad).coerceIn(0f, bitmap.width.toFloat()),
                (rect.top - pad).coerceIn(0f, bitmap.height.toFloat()),
                (rect.right + pad).coerceIn(0f, bitmap.width.toFloat()),
                (rect.bottom + pad).coerceIn(0f, bitmap.height.toFloat())
            )
        }
    }

    private fun buildDetectionResult(
        width: Int,
        height: Int,
        detections: List<BubbleDetection>,
        textBlocks: List<TextBlock>,
        detectedTextLines: List<RectF>? = null,
        detectionComplete: Boolean = true,
        detectionMode: PageRegionDetectionMode,
        allTextDetections: List<BubbleDetection> = emptyList(),
        tiles: List<DetectionTile> = emptyList()
    ): PageRegionDetectionResult {
        val bubbleRects = detections.map { it.rect }
        val regions = buildRegions(detections, bubbleRects, textBlocks, detectedTextLines)
        return PageRegionDetectionResult(
            width = width,
            height = height,
            bubbleDetections = detections,
            allTextDetections = allTextDetections,
            tiles = tiles,
            textRects = textBlocks.map { it.rect },
            regions = regions,
            detectionComplete = detectionComplete,
            detectionMode = detectionMode
        )
    }

    private fun remapTileBubbleDetectionsToPage(
        detections: List<BubbleDetection>,
        tileBitmapWidth: Int,
        tileBitmapHeight: Int,
        tile: DetectionTile,
        tileIndex: Int,
        pageWidth: Int,
        pageHeight: Int
    ): List<TiledBubbleDetection> {
        val scaleX = tile.width / tileBitmapWidth.toFloat().coerceAtLeast(1f)
        val scaleY = tile.height / tileBitmapHeight.toFloat().coerceAtLeast(1f)
        return detections.map { detection ->
            TiledBubbleDetection(
                detection = detection.copy(
                    rect = detection.rect.scaleBy(scaleX, scaleY)
                        .offsetBy(tile.left.toFloat(), tile.top.toFloat()),
                    maskContour = detection.maskContour?.let {
                        remapTileMaskContourToPage(
                            contour = it,
                            tileTop = tile.top,
                            tileHeight = tile.height,
                            pageWidth = pageWidth,
                            pageHeight = pageHeight,
                            tileLeft = tile.left,
                            tileWidth = tile.width
                        )
                    }
                ),
                touchesInternalTileBoundary = touchesInternalTileBoundary(
                    detection = detection,
                    tileBitmapWidth = tileBitmapWidth,
                    tileBitmapHeight = tileBitmapHeight,
                    tile = tile,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight
                ),
                tileIndex = tileIndex
            )
        }
    }

    private fun touchesInternalTileBoundary(
        detection: BubbleDetection,
        tileBitmapWidth: Int,
        tileBitmapHeight: Int,
        tile: DetectionTile,
        pageWidth: Int,
        pageHeight: Int
    ): Boolean {
        if (tileBitmapWidth <= 0 || tileBitmapHeight <= 0) return false
        val marginX = tileBoundaryMargin(tileBitmapWidth)
        val marginY = tileBoundaryMargin(tileBitmapHeight)
        val contour = detection.maskContour
        val contourMinX = contour?.let { values ->
            values.indices.asSequence().filter { it % 2 == 0 }.minOfOrNull { values[it] }
        }?.times(tileBitmapWidth)
        val contourMaxX = contour?.let { values ->
            values.indices.asSequence().filter { it % 2 == 0 }.maxOfOrNull { values[it] }
        }?.times(tileBitmapWidth)
        val contourMinY = contour?.let { values ->
            values.indices.asSequence().filter { it % 2 == 1 }.minOfOrNull { values[it] }
        }?.times(tileBitmapHeight)
        val contourMaxY = contour?.let { values ->
            values.indices.asSequence().filter { it % 2 == 1 }.maxOfOrNull { values[it] }
        }?.times(tileBitmapHeight)
        val touchesLeft = tile.left > 0 &&
            (detection.rect.left <= marginX || (contourMinX != null && contourMinX <= marginX))
        val touchesTop = tile.top > 0 &&
            (detection.rect.top <= marginY || (contourMinY != null && contourMinY <= marginY))
        val touchesRight = tile.right < pageWidth &&
            (detection.rect.right >= tileBitmapWidth - marginX ||
                (contourMaxX != null && contourMaxX >= tileBitmapWidth - marginX))
        val touchesBottom = tile.bottom < pageHeight &&
            (detection.rect.bottom >= tileBitmapHeight - marginY ||
                (contourMaxY != null && contourMaxY >= tileBitmapHeight - marginY))
        return touchesLeft || touchesTop || touchesRight || touchesBottom
    }

    private fun isBubbleDetectionAtInternalTileBottom(
        detection: BubbleDetection,
        tileBitmapHeight: Int,
        tileBottom: Int,
        pageHeight: Int
    ): Boolean {
        if (
            isDetectionAtInternalTileBottom(
                rect = detection.rect,
                tileBitmapHeight = tileBitmapHeight,
                tileBottom = tileBottom,
                pageHeight = pageHeight
            )
        ) {
            return true
        }
        if (tileBitmapHeight <= 0 || tileBottom >= pageHeight) return false
        val contourMaxY = detection.maskContour?.let { contour ->
            contour.indices.asSequence()
                .filter { it % 2 == 1 }
                .maxOfOrNull { contour[it] }
        } ?: return false
        return contourMaxY * tileBitmapHeight >=
            tileBitmapHeight - tileBoundaryMargin(tileBitmapHeight)
    }

    private fun isBubbleDetectionAtReplayTileTop(
        detection: BubbleDetection,
        tileBitmapHeight: Int
    ): Boolean {
        if (isDetectionAtReplayTileTop(detection.rect, tileBitmapHeight)) return true
        if (tileBitmapHeight <= 0) return false
        val contourMinY = detection.maskContour?.let { contour ->
            contour.indices.asSequence()
                .filter { it % 2 == 1 }
                .minOfOrNull { contour[it] }
        } ?: return false
        return contourMinY * tileBitmapHeight <= tileBoundaryMargin(tileBitmapHeight)
    }

    private fun remapTileRectsToPage(
        rects: List<RectF>,
        tileBitmapWidth: Int,
        tileBitmapHeight: Int,
        tile: DetectionTile
    ): List<RectF> {
        val scaleX = tile.width / tileBitmapWidth.toFloat().coerceAtLeast(1f)
        val scaleY = tile.height / tileBitmapHeight.toFloat().coerceAtLeast(1f)
        return rects.map { rect ->
            rect.scaleBy(scaleX, scaleY).offsetBy(tile.left.toFloat(), tile.top.toFloat())
        }
    }

    private fun deduplicateBubbleDetections(
        detections: List<TiledBubbleDetection>,
        pageHeight: Int
    ): List<DeduplicatedBubbleGroup> {
        if (detections.size <= 1) {
            return detections.map { tiled ->
                val detection = if (tiled.touchesInternalTileBoundary) {
                    tiled.detection.copy(maskContour = null)
                } else {
                    tiled.detection
                }
                DeduplicatedBubbleGroup(detection, RectF(detection.rect))
            }
        }
        val visited = BooleanArray(detections.size)
        val result = ArrayList<DeduplicatedBubbleGroup>(detections.size)
        for (start in detections.indices) {
            if (visited[start]) continue
            val queue = ArrayDeque<Int>()
            val component = ArrayList<Int>()
            val componentTileIndices = hashSetOf(detections[start].tileIndex)
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component.add(current)
                for (next in detections.indices) {
                    if (visited[next]) continue
                    if (detections[current].detection.classId != detections[next].detection.classId) continue
                    if (detections[next].tileIndex in componentTileIndices) continue
                    if (!shouldDeduplicateTileCandidates(
                            firstTileIndex = detections[current].tileIndex,
                            secondTileIndex = detections[next].tileIndex,
                            firstRect = detections[current].detection.rect,
                            secondRect = detections[next].detection.rect
                        )
                    ) continue
                    visited[next] = true
                    componentTileIndices.add(detections[next].tileIndex)
                    queue.add(next)
                }
            }
            val candidates = component.map { index ->
                BubblePriorityCandidate(
                    confidence = detections[index].detection.confidence,
                    hasMaskContour = detections[index].detection.maskContour != null,
                    area = rectAreaValue(detections[index].detection.rect),
                    touchesInternalTileBoundary = detections[index].touchesInternalTileBoundary
                )
            }
            val bestOffset = choosePreferredBubbleCandidateIndex(candidates).coerceAtLeast(0)
            val best = detections[component[bestOffset]].detection
            val useUnion = shouldUnionTileBubbleCandidates(candidates)
            val outputRect = if (useUnion) {
                requireNotNull(
                    unionDetectionRects(component.map { index -> detections[index].detection.rect })
                )
            } else {
                RectF(best.rect)
            }
            val outputContour = if (useUnion) {
                mergePageMaskContours(
                    component.mapNotNull { index -> detections[index].detection.maskContour },
                    pageHeight
                )
            } else {
                best.maskContour
            }
            result.add(
                DeduplicatedBubbleGroup(
                    detection = best.copy(rect = outputRect, maskContour = outputContour),
                    suppressionRect = RectF(outputRect)
                )
            )
        }
        return result
    }

    @Synchronized
    private fun getBubbleDetector(logTag: String): BubbleDetector? {
        if (bubbleDetector != null) return bubbleDetector
        return try {
            AppLogger.log(logTag, "Loading BubbleDetector (${BubbleDetector.DEFAULT_MODEL_ASSET})")
            bubbleDetector = BubbleDetector(appContext, settingsStore = settingsStore)
            AppLogger.log(logTag, "BubbleDetector ready")
            bubbleDetector
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init bubble detector", e)
            null
        } catch (e: Error) {
            // OutOfMemoryError / UnsatisfiedLinkError etc. — do not kill the process unlogged.
            AppLogger.logFatal(logTag, "Fatal error init bubble detector", e)
            null
        }
    }

    @Synchronized
    fun releaseLoadedDetectors() {
        val loaded = bubbleDetector
        bubbleDetector = null
        loaded?.close()
        if (loaded != null) {
            AppLogger.log("PageRegionDetector", "Released TFLite detector")
        }
    }

    private fun buildRegions(
        detections: List<BubbleDetection>,
        bubbleRects: List<RectF>,
        textBlocks: List<TextBlock>,
        detectedTextLines: List<RectF>?
    ): List<PageRegion> {
        data class RegionSeed(
            val rect: RectF,
            val source: BubbleSource,
            val maskContour: FloatArray?,
            val textLineRects: List<RectF>?
        )
        val seeds = ArrayList<RegionSeed>(bubbleRects.size + textBlocks.size)
        for (i in bubbleRects.indices) {
            seeds.add(
                RegionSeed(
                    rect = bubbleRects[i],
                    source = BubbleSource.BUBBLE_DETECTOR,
                    maskContour = detections.getOrNull(i)?.maskContour,
                    textLineRects = detectedTextLines?.filter { line ->
                        lineBelongsToRegion(line, bubbleRects[i])
                    }?.takeIf { it.isNotEmpty() }
                )
            )
        }
        for (block in textBlocks) {
            seeds.add(
                RegionSeed(
                    rect = block.rect,
                    source = BubbleSource.TEXT_DETECTOR,
                    maskContour = block.maskContour,
                    textLineRects = block.lines.takeIf { it.isNotEmpty() }
                )
            )
        }
        seeds.sortWith(compareBy({ it.rect.top }, { it.rect.left }))
        return seeds.mapIndexed { index, seed ->
            PageRegion(
                id = index,
                rect = seed.rect,
                source = seed.source,
                maskContour = seed.maskContour,
                textLineRects = seed.textLineRects
            )
        }
    }

    private fun filterOverlapping(
        textRects: List<RectF>,
        bubbleRects: List<RectF>,
        threshold: Float
    ): List<RectF> {
        if (bubbleRects.isEmpty()) return textRects
        val filtered = ArrayList<RectF>(textRects.size)
        for (rect in textRects) {
            var overlapped = false
            for (bubble in bubbleRects) {
                if (shouldFilterTextRectByBubble(rect, bubble, threshold)) {
                    overlapped = true
                    break
                }
            }
            if (!overlapped) {
                filtered.add(rect)
            }
        }
        return filtered
    }

    private fun filterTinyBubbleDetections(
        detections: List<BubbleDetection>,
        bitmap: Bitmap,
        logTag: String
    ): List<BubbleDetection> {
        if (detections.isEmpty()) return detections
        val filtered = detections.filterNot {
            isTinyBubbleErrorRegion(it.rect, bitmap.width, bitmap.height)
        }
        val removedCount = detections.size - filtered.size
        if (removedCount > 0) {
            AppLogger.log(
                logTag,
                "Filtered $removedCount tiny bubble false positives, kept ${filtered.size}"
            )
        }
        return filtered
    }

    private fun filterTinyTextRects(
        rects: List<RectF>,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): List<RectF> {
        if (rects.isEmpty()) return rects
        // Text lines use a much smaller threshold than bubble candidates. The bubble
        // detector's 12x28px noise filter can otherwise remove legitimate small captions,
        // annotations, and sound effects on regular pages.
        val filtered = rects.filterNot { isTinyTextErrorRegion(it, pageWidth, pageHeight) }
        val removedCount = rects.size - filtered.size
        if (removedCount > 0) {
            AppLogger.log(
                logTag,
                "Filtered $removedCount tiny supplement text regions, kept ${filtered.size}"
            )
        }
        return filtered
    }

    private fun filterLongImageBubbleGroups(
        groups: List<DeduplicatedBubbleGroup>,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): List<DeduplicatedBubbleGroup> {
        if (groups.isEmpty()) return groups
        val filtered = groups.filterNot {
            shouldFilterLongImageRegion(it.detection.rect, pageWidth, pageHeight)
        }
        logLongImageRegionFilter(
            removedCount = groups.size - filtered.size,
            keptCount = filtered.size,
            label = "bubble",
            logTag = logTag
        )
        return filtered
    }

    private fun filterLongImageRects(
        rects: List<RectF>,
        pageWidth: Int,
        pageHeight: Int,
        logTag: String
    ): List<RectF> {
        if (rects.isEmpty()) return rects
        val filtered = rects.filterNot { shouldFilterLongImageRegion(it, pageWidth, pageHeight) }
        logLongImageRegionFilter(
            removedCount = rects.size - filtered.size,
            keptCount = filtered.size,
            label = "supplement text",
            logTag = logTag
        )
        return filtered
    }

    private fun logLongImageRegionFilter(
        removedCount: Int,
        keptCount: Int,
        label: String,
        logTag: String
    ) {
        if (removedCount <= 0) return
        AppLogger.log(
            logTag,
            "Filtered $removedCount long-image $label regions, kept $keptCount"
        )
    }

    private fun isTinyBubbleErrorRegion(rect: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        return isTinyErrorRegion(
            rect = rect,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            minShortSidePx = TINY_BUBBLE_SHORT_SIDE_MIN_PX,
            minLongSidePx = TINY_BUBBLE_LONG_SIDE_MIN_PX,
            shortSideRatio = TINY_BUBBLE_SHORT_SIDE_RATIO,
            longSideRatio = TINY_BUBBLE_LONG_SIDE_RATIO,
            maxAreaRatio = TINY_BUBBLE_MAX_AREA_RATIO
        )
    }

    companion object {
        private const val TEXT_IOU_THRESHOLD = TranslationCoreDefaults.PageRegionTextIouThreshold
        private const val TINY_BUBBLE_SHORT_SIDE_MIN_PX = TranslationCoreDefaults.TinyBubbleShortSideMinPx
        private const val TINY_BUBBLE_LONG_SIDE_MIN_PX = TranslationCoreDefaults.TinyBubbleLongSideMinPx
        private const val TINY_BUBBLE_SHORT_SIDE_RATIO = TranslationCoreDefaults.TinyBubbleShortSideRatio
        private const val TINY_BUBBLE_LONG_SIDE_RATIO = TranslationCoreDefaults.TinyBubbleLongSideRatio
        private const val TINY_BUBBLE_MAX_AREA_RATIO = TranslationCoreDefaults.TinyBubbleMaxAreaRatio
    }
}

private fun compareBubblePriority(
    candidate: BubblePriorityCandidate,
    currentBest: BubblePriorityCandidate
): Int {
    if (candidate.touchesInternalTileBoundary != currentBest.touchesInternalTileBoundary) {
        return if (candidate.touchesInternalTileBoundary) -1 else 1
    }
    val confidenceDiff = candidate.confidence - currentBest.confidence
    if (abs(confidenceDiff) >= 0.02f) {
        return if (confidenceDiff > 0f) 1 else -1
    }
    if (candidate.hasMaskContour != currentBest.hasMaskContour) {
        return if (candidate.hasMaskContour) 1 else -1
    }
    if (confidenceDiff != 0f) {
        return if (confidenceDiff > 0f) 1 else -1
    }
    if (candidate.area != currentBest.area) {
        return if (candidate.area > currentBest.area) 1 else -1
    }
    return 0
}

internal data class PageRegion(
    val id: Int,
    val rect: RectF,
    val source: BubbleSource,
    val maskContour: FloatArray? = null,
    val textLineRects: List<RectF>? = null
)

internal data class PageRegionDetectionResult(
    val width: Int,
    val height: Int,
    val bubbleDetections: List<BubbleDetection>,
    val textRects: List<RectF>,
    val regions: List<PageRegion>,
    val detectionComplete: Boolean = true,
    val detectionMode: PageRegionDetectionMode = PageRegionDetectionMode.FULL,
    val allTextDetections: List<BubbleDetection> = emptyList(),
    val tiles: List<DetectionTile> = emptyList()
)

private fun normalizedRectContour(rect: RectF, width: Int, height: Int): FloatArray {
    val safeWidth = width.toFloat().coerceAtLeast(1f)
    val safeHeight = height.toFloat().coerceAtLeast(1f)
    return floatArrayOf(
        (rect.left / safeWidth).coerceIn(0f, 1f),
        (rect.top / safeHeight).coerceIn(0f, 1f),
        (rect.left / safeWidth).coerceIn(0f, 1f),
        (rect.bottom / safeHeight).coerceIn(0f, 1f),
        (rect.right / safeWidth).coerceIn(0f, 1f),
        (rect.bottom / safeHeight).coerceIn(0f, 1f),
        (rect.right / safeWidth).coerceIn(0f, 1f),
        (rect.top / safeHeight).coerceIn(0f, 1f)
    )
}

private fun RectF.offsetBy(offsetX: Float, offsetY: Float): RectF {
    return RectF(
        left + offsetX,
        top + offsetY,
        right + offsetX,
        bottom + offsetY
    )
}

private const val BUBBLE_SPAN_MIN_PAIR_OVERLAP = 0.15f
private const val BUBBLE_SPAN_MIN_LINE_OVERLAP = 0.25f
private const val BUBBLE_SPAN_MAX_UNION_FRACTION = 0.35f
private const val LONG_IMAGE_ASPECT_THRESHOLD = 2.0f
private const val LONG_IMAGE_MIN_HEIGHT_PX = 2048
// Cover 2.5 page widths per bubble tile, then compress Y by 20%. The model sees
// the same effective 2:1 aspect and pixel density as the previous 2x tiles.
private const val LONG_IMAGE_TILE_HEIGHT_WIDTH_RATIO = 2.5f
private const val LONG_IMAGE_BUBBLE_VERTICAL_SCALE = 0.8f
private const val LONG_IMAGE_SMALL_REMAINDER_RATIO = 0.20f
private const val LONG_IMAGE_TEXT_TILE_HEIGHT_WIDTH_RATIO = 1.5f
private const val PADDLE_TILE_HEIGHT_WIDTH_RATIO = 1.5f
private const val PADDLE_TILE_OVERLAP_RATIO = 0.25f
private const val PADDLE_MIN_TILE_HEIGHT_PX = 960
// Normal tiles are contiguous. Overlap is introduced only when a detector finds
// a candidate clipped by an internal bottom edge.
private const val ADAPTIVE_TILE_MIN_ADVANCE_RATIO = 0.25f
private const val ADAPTIVE_TILE_REPLAY_PADDING_MULTIPLIER = 2f
// Reject only abnormal full-strip boxes (~1.8 page-widths tall), not normal tall balloons.
private const val LONG_IMAGE_MAX_REGION_HEIGHT_WIDTH_RATIO = 1.8f
private const val BUBBLE_DEDUP_IOU_THRESHOLD = TranslationCoreDefaults.BubbleDedupIouThreshold
private const val BUBBLE_DEDUP_CONTAINMENT_THRESHOLD = 0.9f
private const val BUBBLE_DEDUP_PARTIAL_OVERLAP_MIN_RATIO = 0.40f
private const val BUBBLE_DEDUP_AXIS_OVERLAP_MIN_RATIO = 0.45f
private const val BUBBLE_DEDUP_CENTER_DRIFT_RATIO = 0.42f
private const val BUBBLE_DEDUP_CENTER_DRIFT_PAD = 24f
private const val BUBBLE_DEDUP_VERTICAL_SPLIT_WIDTH_RATIO = 0.72f
private const val BUBBLE_DEDUP_VERTICAL_SPLIT_CENTER_X_RATIO = 0.28f
private const val BUBBLE_DEDUP_VERTICAL_SPLIT_AXIS_X_RATIO = 0.60f
private const val BUBBLE_DEDUP_VERTICAL_SPLIT_MAX_GAP_PX = 12f
private const val TILE_BOUNDARY_MARGIN_RATIO = 0.015f
private const val TILE_BOUNDARY_MARGIN_MIN_PX = 4f
private const val TILE_BOUNDARY_MARGIN_MAX_PX = 20f
private const val SEAM_BAND_HALF_HEIGHT_RATIO = 0.25f
private const val SEAM_BAND_MIN_SIDE_PX = 16f
private const val MERGED_CONTOUR_MIN_SAMPLE_ROWS = 8
private const val MERGED_CONTOUR_MAX_SAMPLE_ROWS = 160
private const val CONTOUR_COORD_EPSILON = 1e-5f

private fun tileBoundaryMargin(tileBitmapExtent: Int): Float {
    return (tileBitmapExtent * TILE_BOUNDARY_MARGIN_RATIO)
        .coerceIn(TILE_BOUNDARY_MARGIN_MIN_PX, TILE_BOUNDARY_MARGIN_MAX_PX)
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

private fun rectContains(outer: RectF, inner: RectF): Boolean {
    return outer.left <= inner.left &&
        outer.top <= inner.top &&
        outer.right >= inner.right &&
        outer.bottom >= inner.bottom
}

internal fun lineBelongsToRegion(lineRect: RectF, regionRect: RectF): Boolean {
    if (rectContains(regionRect, lineRect)) return true
    val lineArea = rectAreaValue(lineRect)
    if (lineArea <= 0f) return false
    return rectIntersectionArea(lineRect, regionRect) / lineArea >= 0.5f
}

internal fun shouldFilterTextRectByBubble(
    textRect: RectF,
    bubbleRect: RectF,
    iouThreshold: Float
): Boolean {
    return rectIou(textRect, bubbleRect) >= iouThreshold ||
        rectContains(bubbleRect, textRect)
}

private fun shouldTreatPartiallyShiftedRectsAsSameBubble(
    a: RectF,
    b: RectF,
    overlapOverMin: Float
): Boolean {
    if (overlapOverMin < BUBBLE_DEDUP_PARTIAL_OVERLAP_MIN_RATIO) return false

    val overlapX = max(0f, min(a.right, b.right) - max(a.left, b.left))
    val overlapY = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val minWidth = min(a.width(), b.width()).coerceAtLeast(1f)
    val minHeight = min(a.height(), b.height()).coerceAtLeast(1f)
    if (overlapX / minWidth < BUBBLE_DEDUP_AXIS_OVERLAP_MIN_RATIO) return false
    if (overlapY / minHeight < BUBBLE_DEDUP_AXIS_OVERLAP_MIN_RATIO) return false

    val maxWidth = max(a.width(), b.width()).coerceAtLeast(1f)
    val maxHeight = max(a.height(), b.height()).coerceAtLeast(1f)
    val centerAX = (a.left + a.right) * 0.5f
    val centerAY = (a.top + a.bottom) * 0.5f
    val centerBX = (b.left + b.right) * 0.5f
    val centerBY = (b.top + b.bottom) * 0.5f
    val maxCenterDx = maxWidth * BUBBLE_DEDUP_CENTER_DRIFT_RATIO + BUBBLE_DEDUP_CENTER_DRIFT_PAD
    val maxCenterDy = maxHeight * BUBBLE_DEDUP_CENTER_DRIFT_RATIO + BUBBLE_DEDUP_CENTER_DRIFT_PAD

    return abs(centerAX - centerBX) <= maxCenterDx &&
        abs(centerAY - centerBY) <= maxCenterDy
}

/**
 * Detects two partial balloons from adjacent long-image tiles: similar width, strong X overlap,
 * vertically stacked with little gap, and the union is taller than either half alone.
 */
private fun shouldTreatVerticallySplitTileRectsAsSameBubble(a: RectF, b: RectF): Boolean {
    val widthA = a.width().coerceAtLeast(1f)
    val widthB = b.width().coerceAtLeast(1f)
    val heightA = a.height().coerceAtLeast(1f)
    val heightB = b.height().coerceAtLeast(1f)
    val widthRatio = min(widthA, widthB) / max(widthA, widthB)
    if (widthRatio < BUBBLE_DEDUP_VERTICAL_SPLIT_WIDTH_RATIO) return false

    val overlapX = max(0f, min(a.right, b.right) - max(a.left, b.left))
    if (overlapX / min(widthA, widthB) < BUBBLE_DEDUP_VERTICAL_SPLIT_AXIS_X_RATIO) return false

    val centerAX = (a.left + a.right) * 0.5f
    val centerBX = (b.left + b.right) * 0.5f
    if (abs(centerAX - centerBX) > max(widthA, widthB) * BUBBLE_DEDUP_VERTICAL_SPLIT_CENTER_X_RATIO) {
        return false
    }

    val verticalGap = when {
        a.bottom <= b.top -> b.top - a.bottom
        b.bottom <= a.top -> a.top - b.bottom
        else -> 0f
    }
    if (verticalGap > BUBBLE_DEDUP_VERTICAL_SPLIT_MAX_GAP_PX) return false

    val unionTop = min(a.top, b.top)
    val unionBottom = max(a.bottom, b.bottom)
    val unionHeight = (unionBottom - unionTop).coerceAtLeast(1f)
    // Require a real vertical extension, not two nearly-identical duplicates.
    if (unionHeight <= max(heightA, heightB) * 1.08f) return false
    // Avoid gluing two full stacked bubbles that barely touch: each half should cover a
    // substantial share of the union (typical for tile-truncated pairs).
    if (heightA / unionHeight < 0.28f || heightB / unionHeight < 0.28f) return false
    return true
}

/** Model text outputs are blocks, never reusable OCR line boxes. */
internal fun dualTextBlocks(rects: List<RectF>, width: Int, height: Int): List<TextBlock> =
    mergeRelatedTextRects(rects, min(width, height).coerceAtLeast(0).toFloat()).map { rect ->
        TextBlock(RectF(rect), emptyList(), TextLineOrientation.AMBIGUOUS,
            normalizedRectContour(rect, width, height))
    }

/** Merge connected original boxes, without using empty space in a union to join other text. */
private fun mergeRelatedTextRects(rects: List<RectF>, shortEdge: Float): List<RectF> {
    // Measure neighbors on original boxes, before any union can change distances.
    // A wide row must not skip a nearer continuation and also join another column.
    val nearestAbove = FloatArray(rects.size) { Float.POSITIVE_INFINITY }
    val nearestBelow = FloatArray(rects.size) { Float.POSITIVE_INFINITY }
    for (first in rects.indices) {
        for (second in first + 1 until rects.size) {
            val gap = horizontalTextRowGap(rects[first], rects[second], shortEdge) ?: continue
            val (upper, lower) = if (rects[first].top < rects[second].top) {
                first to second
            } else {
                second to first
            }
            nearestBelow[upper] = min(nearestBelow[upper], gap)
            nearestAbove[lower] = min(nearestAbove[lower], gap)
        }
    }
    val visited = BooleanArray(rects.size)
    val merged = ArrayList<RectF>(rects.size)
    for (start in rects.indices) {
        if (visited[start]) continue
        val union = RectF(rects[start])
        val queue = ArrayDeque<Int>()
        queue.add(start)
        visited[start] = true
        while (queue.isNotEmpty()) {
            val currentIndex = queue.removeFirst()
            val current = rects[currentIndex]
            for (next in rects.indices) {
                if (visited[next]) continue
                val candidate = rects[next]
                val minArea = min(rectAreaValue(current), rectAreaValue(candidate))
                if (minArea <= 0f) continue
                val overlaps = rectIntersectionArea(current, candidate) / minArea >=
                    TEXT_BLOCK_MERGE_OVERLAP_RATIO
                if (!overlaps) {
                    val gap = horizontalTextRowGap(current, candidate, shortEdge) ?: continue
                    val (upper, lower) = if (current.top < candidate.top) {
                        currentIndex to next
                    } else {
                        next to currentIndex
                    }
                    if (gap > nearestBelow[upper] || gap > nearestAbove[lower]) continue
                }
                visited[next] = true
                queue.add(next)
                union.union(candidate)
            }
        }
        merged.add(union)
    }
    return merged
}

private const val TEXT_BLOCK_MERGE_OVERLAP_RATIO = 0.3f

/**
 * Return the eligible edge gap in short-edge units. Both spacing and alignment use
 * the smaller block height capped in those same units: a tall block may contain
 * several lines, so its full height is not a reliable font-size estimate.
 * The long canvas edge and the relative lengths of the two rows are irrelevant.
 */
private fun horizontalTextRowGap(a: RectF, b: RectF, shortEdge: Float): Float? {
    if (shortEdge <= 0f) return null
    val minHeight = min(a.height(), b.height())
    val minWidth = min(a.width(), b.width())
    if (minHeight <= 0f || minWidth <= 0f) return null
    if (a.width() < a.height() * TEXT_ROW_MIN_ASPECT ||
        b.width() < b.height() * TEXT_ROW_MIN_ASPECT
    ) return null
    if (minHeight / max(a.height(), b.height()) < TEXT_ROW_MIN_HEIGHT_RATIO) return null
    val localScale = min(minHeight / shortEdge, TEXT_ROW_MAX_SHORT_EDGE_HEIGHT_RATIO)
    val gap = (max(a.top, b.top) - min(a.bottom, b.bottom)) / shortEdge
    // Overlapping blocks use the existing overlap rule, not paragraph heuristics.
    if (gap < 0f || gap > localScale * TEXT_ROW_MAX_GAP_RATIO) return null
    val overlapX = min(a.right, b.right) - max(a.left, b.left)
    if (overlapX < minWidth * TEXT_ROW_MIN_OVERLAP_RATIO) return null
    val alignment = minOf(abs(a.centerX() - b.centerX()), abs(a.left - b.left), abs(a.right - b.right)) / shortEdge
    if (alignment > localScale * TEXT_ROW_ALIGNMENT_RATIO) return null
    return gap
}

private const val TEXT_ROW_MIN_ASPECT = 1.8f
private const val TEXT_ROW_MIN_HEIGHT_RATIO = 0.65f
private const val TEXT_ROW_MAX_GAP_RATIO = 0.8f
// Shared local-scale cap: gap <= 8% and alignment drift <= 5% of the short edge.
private const val TEXT_ROW_MAX_SHORT_EDGE_HEIGHT_RATIO = 0.1f
private const val TEXT_ROW_MIN_OVERLAP_RATIO = 0.8f
private const val TEXT_ROW_ALIGNMENT_RATIO = 0.5f
