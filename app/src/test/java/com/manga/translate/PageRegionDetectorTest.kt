package com.manga.translate

import android.app.Application
import android.graphics.RectF
import com.manga.translate.detection.BubbleDetection
import com.manga.translate.detection.BubblePriorityCandidate
import com.manga.translate.detection.DetectionTile
import com.manga.translate.detection.RectGeometryDeduplicator
import com.manga.translate.detection.RegionDetectionSelection
import com.manga.translate.detection.TextBlockMerger
import com.manga.translate.detection.TextLineOrientation
import com.manga.translate.detection.adaptiveNextTileTop
import com.manga.translate.detection.buildTileTextSuppressionRects
import com.manga.translate.detection.choosePreferredBubbleCandidateIndex
import com.manga.translate.detection.longImageBubbleDetectionTileHeight
import com.manga.translate.detection.longImageBubbleDetectionInputHeight
import com.manga.translate.detection.longImageMaxRegionHeight
import com.manga.translate.detection.longImageTextDetectionTileHeight
import com.manga.translate.detection.lineBelongsToRegion
import com.manga.translate.detection.mapPageLineRectsToCrop
import com.manga.translate.detection.mergeBubblesSpannedByTextLines
import com.manga.translate.detection.isDetectionAtInternalTileBottom
import com.manga.translate.detection.isDetectionAtReplayTileTop
import com.manga.translate.detection.isTinyTextErrorRegion
import com.manga.translate.detection.mergePageMaskContours
import com.manga.translate.detection.planLongImageBubbleDetectionTiles
import com.manga.translate.detection.planLongImageTextDetectionTiles
import com.manga.translate.detection.planPaddleTextDetectionTiles
import com.manga.translate.detection.remapTileMaskContourToPage
import com.manga.translate.detection.shouldDeduplicateTileCandidates
import com.manga.translate.detection.shouldFilterLongImageRegion
import com.manga.translate.detection.shouldFilterTextRectByBubble
import com.manga.translate.detection.shouldKeepBubblesWhenTextDetectionFails
import com.manga.translate.detection.shouldDiscardReplayTileTopFragments
import com.manga.translate.detection.shouldTreatRectsAsSameBubbleForDedup
import com.manga.translate.detection.shouldUnionTileBubbleCandidates
import com.manga.translate.detection.shouldUseLongImageTiling
import com.manga.translate.detection.unionDetectionRects
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrBubble
import com.manga.translate.translation.buildDetectionStrategyTag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PageRegionDetectorTest {

    @Test
    fun `detected page lines map into scaled OCR crop coordinates`() {
        val mapped = mapPageLineRectsToCrop(
            lineRects = listOf(RectF(120f, 240f, 220f, 280f)),
            cropRect = RectF(100f, 200f, 300f, 400f),
            cropWidth = 100,
            cropHeight = 100
        )

        assertEquals(listOf(RectF(10f, 20f, 60f, 40f)), mapped)
        assertEquals(null, mapPageLineRectsToCrop(null, RectF(0f, 0f, 10f, 10f), 10, 10))
        assertEquals(emptyList<RectF>(), mapPageLineRectsToCrop(emptyList(), RectF(0f, 0f, 10f, 10f), 10, 10))
    }

    @Test
    fun `text line is assigned to region by containment or majority overlap`() {
        val region = RectF(100f, 100f, 300f, 300f)

        assertTrue(lineBelongsToRegion(RectF(120f, 140f, 220f, 180f), region))
        assertTrue(lineBelongsToRegion(RectF(80f, 140f, 180f, 180f), region))
        assertFalse(lineBelongsToRegion(RectF(20f, 140f, 140f, 180f), region))
    }

    @Test
    fun `region detection selections map to expected detectors`() {
        assertTrue(RegionDetectionSelection.BUBBLES_ONLY.detectBubbles)
        assertFalse(RegionDetectionSelection.BUBBLES_ONLY.detectText)
        assertFalse(RegionDetectionSelection.TEXT_ONLY.detectBubbles)
        assertTrue(RegionDetectionSelection.TEXT_ONLY.detectText)
        assertTrue(RegionDetectionSelection.BUBBLES_AND_TEXT.detectBubbles)
        assertTrue(RegionDetectionSelection.BUBBLES_AND_TEXT.detectText)
        assertEquals(
            RegionDetectionSelection.BUBBLES_AND_TEXT,
            RegionDetectionSelection.fromPref(null)
        )
    }

    @Test
    fun `failed text detection keeps completed bubbles only when bubbles are enabled`() {
        assertTrue(
            shouldKeepBubblesWhenTextDetectionFails(
                RegionDetectionSelection.BUBBLES_AND_TEXT,
                bubbleDetectionSucceeded = true
            )
        )
        assertFalse(
            shouldKeepBubblesWhenTextDetectionFails(
                RegionDetectionSelection.BUBBLES_AND_TEXT,
                bubbleDetectionSucceeded = false
            )
        )
        assertFalse(
            shouldKeepBubblesWhenTextDetectionFails(
                RegionDetectionSelection.TEXT_ONLY,
                bubbleDetectionSucceeded = true
            )
        )
    }

    @Test
    fun `Paddle horizontal lines merge into one text block`() {
        val blocks = TextBlockMerger.merge(
            lineRects = listOf(
                RectF(20f, 20f, 220f, 50f),
                RectF(21f, 62f, 190f, 92f),
                RectF(20f, 104f, 210f, 134f)
            ),
            imageWidth = 1000,
            imageHeight = 1000
        )

        assertEquals(1, blocks.size)
        assertEquals(3, blocks.single().lines.size)
        assertEquals(TextLineOrientation.HORIZONTAL, blocks.single().orientation)
        assertEquals(RectF(20f, 20f, 220f, 134f), blocks.single().rect)
        assertArrayEquals(
            floatArrayOf(
                0.020f, 0.020f,
                0.020f, 0.050f,
                0.021f, 0.062f,
                0.021f, 0.092f,
                0.020f, 0.104f,
                0.020f, 0.134f,
                0.210f, 0.134f,
                0.210f, 0.104f,
                0.190f, 0.092f,
                0.190f, 0.062f,
                0.220f, 0.050f,
                0.220f, 0.020f
            ),
            blocks.single().maskContour,
            1e-4f
        )
    }

    @Test
    fun `Paddle vertical lines form polygon from line corners`() {
        val blocks = TextBlockMerger.merge(
            lineRects = listOf(
                RectF(20f, 20f, 50f, 220f),
                RectF(62f, 21f, 92f, 190f),
                RectF(104f, 20f, 134f, 210f)
            ),
            imageWidth = 1000,
            imageHeight = 1000
        )

        assertEquals(1, blocks.size)
        assertEquals(TextLineOrientation.VERTICAL, blocks.single().orientation)
        assertEquals(RectF(20f, 20f, 134f, 220f), blocks.single().rect)
        assertArrayEquals(
            floatArrayOf(
                0.020f, 0.020f,
                0.050f, 0.020f,
                0.062f, 0.021f,
                0.092f, 0.021f,
                0.104f, 0.020f,
                0.134f, 0.020f,
                0.134f, 0.210f,
                0.104f, 0.210f,
                0.092f, 0.190f,
                0.062f, 0.190f,
                0.050f, 0.220f,
                0.020f, 0.220f
            ),
            blocks.single().maskContour,
            1e-4f
        )
    }

    @Test
    fun `Paddle block merge suppresses a substantially overlapping weaker block`() {
        val blocks = TextBlockMerger.merge(
            lineRects = listOf(
                RectF(0f, 0f, 200f, 20f),
                RectF(0f, 40f, 200f, 60f),
                RectF(60f, 10f, 120f, 70f)
            ),
            imageWidth = 400,
            imageHeight = 400
        )

        assertEquals(1, blocks.size)
        assertEquals(2, blocks.single().lines.size)
        assertEquals(RectF(0f, 0f, 200f, 60f), blocks.single().rect)
    }

    @Test
    fun `Paddle block merge attaches centered first line to following paragraph`() {
        val blocks = TextBlockMerger.merge(
            lineRects = listOf(
                RectF(430f, 900f, 850f, 936f),
                RectF(70f, 952f, 870f, 988f),
                RectF(70f, 1002f, 870f, 1038f)
            ),
            imageWidth = 940,
            imageHeight = 1830
        )

        assertEquals(1, blocks.size)
        assertEquals(3, blocks.single().lines.size)
        assertEquals(RectF(70f, 900f, 870f, 1038f), blocks.single().rect)
    }

    @Test
    fun `Paddle overlapping text blocks are combined instead of rendered twice`() {
        val blocks = TextBlockMerger.merge(
            lineRects = listOf(
                RectF(100f, 100f, 700f, 140f),
                RectF(100f, 160f, 700f, 200f),
                RectF(140f, 120f, 660f, 180f)
            ),
            imageWidth = 1000,
            imageHeight = 1000
        )

        assertEquals(1, blocks.size)
        assertEquals(RectF(100f, 100f, 700f, 200f), blocks.single().rect)
    }

    @Test
    fun `Paddle overlapping long-image tiles cover page`() {
        val tiles = planPaddleTextDetectionTiles(pageWidth = 1000, pageHeight = 7000)

        assertTrue(tiles.size > 1)
        assertEquals(0, tiles.first().top)
        assertEquals(7000, tiles.last().bottom)
        assertTrue(tiles.zipWithNext().all { (first, second) -> second.top < first.bottom })
    }

    @Test
    fun `long image tiling only enables for threshold-matching vertical pages`() {
        assertFalse(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 2047))
        assertFalse(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 2700))
        assertFalse(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 2800))
        assertTrue(shouldUseLongImageTiling(pageWidth = 1400, pageHeight = 2801))
        assertTrue(shouldUseLongImageTiling(pageWidth = 1000, pageHeight = 2200))
        assertFalse(shouldUseLongImageTiling(pageWidth = 200, pageHeight = 500))
    }

    @Test
    fun `large square portrait and wide pages stay on full page detection`() {
        assertFalse(shouldUseLongImageTiling(pageWidth = 4096, pageHeight = 4096))
        assertFalse(shouldUseLongImageTiling(pageWidth = 3000, pageHeight = 4000))
        assertFalse(shouldUseLongImageTiling(pageWidth = 8192, pageHeight = 2048))
    }

    @Test
    fun `long image text tiles cover full width and only split vertically`() {
        val tiles = planLongImageTextDetectionTiles(pageWidth = 1000, pageHeight = 7000)
        val tileHeight = longImageTextDetectionTileHeight(pageWidth = 1000, pageHeight = 7000)

        assertEquals(1500, tileHeight)
        assertEquals(5, tiles.size)
        assertEquals(0, tiles.first().top)
        assertEquals(7000, tiles.last().bottom)
        assertTrue(tiles.all { it.left == 0 && it.right == 1000 })
        assertTrue(tiles.all { it.width == 1000 && it.height <= 1500 })
        assertTrue(tiles.zipWithNext().all { (a, b) -> b.top == a.bottom })
    }

    @Test
    fun `long image just over one bubble tile uses one full-height tile`() {
        val tiles = planLongImageBubbleDetectionTiles(pageWidth = 1400, pageHeight = 2801)

        assertEquals(1, tiles.size)
        assertEquals(2801, tiles.single().height)
    }

    @Test
    fun `tile suppression rects are scaled expanded and clipped`() {
        val tile = DetectionTile(left = 440, top = 448, right = 1080, bottom = 1088)
        val suppressionRects = buildTileTextSuppressionRects(
            pageBubbleRects = listOf(
                RectF(400f, 400f, 600f, 600f),
                RectF(100f, 100f, 200f, 200f)
            ),
            tile = tile,
            tileBitmapWidth = 320,
            tileBitmapHeight = 320
        )

        assertEquals(1, suppressionRects.size)
        assertEquals(RectF(0f, 0f, 95f, 91f), suppressionRects.single())
    }

    @Test
    fun `reference webtoon text plan has no horizontal tile multiplication`() {
        val tiles = planLongImageTextDetectionTiles(pageWidth = 1080, pageHeight = 28800)

        assertEquals(18, tiles.size)
        assertTrue(tiles.all { it.left == 0 && it.right == 1080 })
        assertTrue(tiles.all { it.width == 1080 && it.height <= 1620 })
        assertEquals(28800, tiles.maxOf { it.bottom })
    }

    @Test
    fun `long image bubble plan uses contiguous full width 2_5x tiles`() {
        val tiles = planLongImageBubbleDetectionTiles(pageWidth = 1080, pageHeight = 28800)

        assertEquals(2700, longImageBubbleDetectionTileHeight(1080, 28800))
        assertEquals(11, tiles.size)
        assertTrue(tiles.all { it.left == 0 && it.right == 1080 && it.height <= 2700 })
        assertEquals(0, tiles.first().top)
        assertEquals(28800, tiles.last().bottom)
        assertTrue(tiles.zipWithNext().all { (a, b) -> b.top == a.bottom })
    }

    @Test
    fun `internal bottom edge candidates trigger adaptive replay above candidate`() {
        val tile = DetectionTile(left = 0, top = 0, right = 1000, bottom = 1500)
        val edgeRect = RectF(100f, 600f, 500f, 740f)

        assertTrue(
            isDetectionAtInternalTileBottom(
                rect = edgeRect,
                tileBitmapHeight = 750,
                tileBottom = tile.bottom,
                pageHeight = 7000
            )
        )
        assertEquals(
            1155,
            adaptiveNextTileTop(
                tile = tile,
                pageHeight = 7000,
                tileBitmapHeight = 750,
                bottomEdgeRects = listOf(edgeRect)
            )
        )
    }

    @Test
    fun `tiles stay contiguous without edge candidates and retain real page bottom`() {
        val internalTile = DetectionTile(left = 0, top = 0, right = 1000, bottom = 1500)
        val finalTile = DetectionTile(left = 0, top = 6000, right = 1000, bottom = 7000)
        val bottomRect = RectF(100f, 600f, 500f, 750f)

        assertEquals(
            1500,
            adaptiveNextTileTop(
                tile = internalTile,
                pageHeight = 7000,
                tileBitmapHeight = 750,
                bottomEdgeRects = emptyList()
            )
        )
        assertFalse(
            isDetectionAtInternalTileBottom(
                rect = bottomRect,
                tileBitmapHeight = 750,
                tileBottom = finalTile.bottom,
                pageHeight = 7000
            )
        )
        assertEquals(
            7000,
            adaptiveNextTileTop(
                tile = finalTile,
                pageHeight = 7000,
                tileBitmapHeight = 750,
                bottomEdgeRects = listOf(bottomRect)
            )
        )
    }

    @Test
    fun `replay top fragments are not bottom edge replay triggers`() {
        val tile = DetectionTile(left = 0, top = 1155, right = 1000, bottom = 2655)
        val topFragment = RectF(100f, 4f, 500f, 200f)

        assertTrue(isDetectionAtReplayTileTop(topFragment, tileBitmapHeight = 750))
        assertFalse(
            isDetectionAtInternalTileBottom(
                rect = topFragment,
                tileBitmapHeight = 750,
                tileBottom = tile.bottom,
                pageHeight = 7000
            )
        )
        assertEquals(
            tile.bottom,
            adaptiveNextTileTop(
                tile = tile,
                pageHeight = 7000,
                tileBitmapHeight = 750,
                bottomEdgeRects = emptyList()
            )
        )
    }

    @Test
    fun `final replay tile retains top edge candidates`() {
        assertTrue(
            shouldDiscardReplayTileTopFragments(
                overlapsPreviousTile = true,
                tileBottom = 6500,
                pageHeight = 7000
            )
        )
        assertFalse(
            shouldDiscardReplayTileTopFragments(
                overlapsPreviousTile = true,
                tileBottom = 7000,
                pageHeight = 7000
            )
        )
    }

    @Test
    fun `long image bubble input compresses height by twenty percent`() {
        assertEquals(1536, longImageBubbleDetectionInputHeight(bitmapHeight = 1920))
        assertEquals(1, longImageBubbleDetectionInputHeight(bitmapHeight = 1))
        assertEquals(0, longImageBubbleDetectionInputHeight(bitmapHeight = 0))
    }

    @Test
    fun `long image region filter only removes full-strip regions`() {
        // Normal tall balloon (~1.4 page widths) must not be filtered.
        assertFalse(
            shouldFilterLongImageRegion(
                RectF(100f, 100f, 900f, 1500f),
                pageWidth = 1000,
                pageHeight = 7000
            )
        )
        // Abnormal full-strip region (~1.9 page widths) is filtered.
        assertTrue(
            shouldFilterLongImageRegion(
                RectF(100f, 100f, 900f, 2000f),
                pageWidth = 1000,
                pageHeight = 7000
            )
        )
    }

    @Test
    fun `long image region filter is disabled for regular pages`() {
        assertFalse(
            shouldFilterLongImageRegion(
                RectF(100f, 100f, 900f, 2050f),
                pageWidth = 1400,
                pageHeight = 2800
            )
        )
    }

    @Test
    fun `tiny text filter keeps small captions but removes pixel noise`() {
        assertFalse(
            isTinyTextErrorRegion(
                RectF(0f, 0f, 8f, 30f),
                imageWidth = 800,
                imageHeight = 1200
            )
        )
        assertTrue(
            isTinyTextErrorRegion(
                RectF(0f, 0f, 4f, 10f),
                imageWidth = 800,
                imageHeight = 1200
            )
        )
    }

    @Test
    fun `supplement rect merge keeps original rects when union would be screen-sized`() {
        val rects = listOf(
            RectF(100f, 100f, 220f, 1800f),
            RectF(100f, 1700f, 220f, 2020f)
        )

        val merged = RectGeometryDeduplicator.mergeSupplementRects(
            rects = rects,
            imageWidth = 1000,
            imageHeight = 7000,
            maxMergedHeight = longImageMaxRegionHeight(pageWidth = 1000, pageHeight = 7000)
        )

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.top == 100f && it.bottom == 1800f })
        assertTrue(merged.any { it.top == 1700f && it.bottom == 2020f })
    }

    @Test
    fun `short OCR text merge keeps original bubbles when union would be screen-sized`() {
        val bubbles = listOf(
            OcrBubble(
                id = 0,
                rect = RectF(100f, 100f, 220f, 220f),
                text = "あ",
                source = BubbleSource.TEXT_DETECTOR
            ),
            OcrBubble(
                id = 1,
                rect = RectF(105f, 1900f, 225f, 2020f),
                text = "い",
                source = BubbleSource.TEXT_DETECTOR
            )
        )

        val merged = RectGeometryDeduplicator.mergeShortOcrBubbles(
            bubbles = bubbles,
            imageWidth = 1000,
            imageHeight = 7000,
            maxMergedHeight = longImageMaxRegionHeight(pageWidth = 1000, pageHeight = 7000)
        )

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.rect.top == 100f && it.rect.bottom == 220f })
        assertTrue(merged.any { it.rect.top == 1900f && it.rect.bottom == 2020f })
    }

    @Test
    fun `tile mask contour remaps from tile normalized coordinates to page normalized coordinates`() {
        val remapped = remapTileMaskContourToPage(
            contour = floatArrayOf(0f, 0f, 1f, 1f, 0.5f, 0.5f),
            tileTop = 2000,
            tileHeight = 2500,
            pageWidth = 1000,
            pageHeight = 7000,
            tileLeft = 200,
            tileWidth = 600
        )

        assertArrayEquals(
            floatArrayOf(
                0.2f, 2000f / 7000f,
                0.8f, 4500f / 7000f,
                0.5f, 3250f / 7000f
            ),
            remapped,
            1e-4f
        )
    }

    @Test
    fun `bubble priority prefers higher confidence when gap exceeds threshold`() {
        val best = choosePreferredBubbleCandidateIndex(
            listOf(
                BubblePriorityCandidate(confidence = 0.70f, hasMaskContour = false, area = 100f),
                BubblePriorityCandidate(confidence = 0.73f, hasMaskContour = false, area = 80f)
            )
        )

        assertEquals(1, best)
    }

    @Test
    fun `bubble priority prefers contour then area when confidence gap is small`() {
        val contourPreferred = choosePreferredBubbleCandidateIndex(
            listOf(
                BubblePriorityCandidate(confidence = 0.80f, hasMaskContour = false, area = 200f),
                BubblePriorityCandidate(confidence = 0.81f, hasMaskContour = true, area = 150f)
            )
        )
        val areaPreferred = choosePreferredBubbleCandidateIndex(
            listOf(
                BubblePriorityCandidate(confidence = 0.80f, hasMaskContour = true, area = 120f),
                BubblePriorityCandidate(confidence = 0.80f, hasMaskContour = true, area = 180f)
            )
        )

        assertEquals(1, contourPreferred)
        assertEquals(1, areaPreferred)
    }

    @Test
    fun `bubble priority rejects tile boundary contour even when confidence is higher`() {
        val best = choosePreferredBubbleCandidateIndex(
            listOf(
                BubblePriorityCandidate(
                    confidence = 0.78f,
                    hasMaskContour = true,
                    area = 180f,
                    touchesInternalTileBoundary = false
                ),
                BubblePriorityCandidate(
                    confidence = 0.91f,
                    hasMaskContour = true,
                    area = 220f,
                    touchesInternalTileBoundary = true
                )
            )
        )

        assertEquals(0, best)
    }

    @Test
    fun `tile bubble union only applies when every duplicate is boundary-truncated`() {
        assertFalse(
            shouldUnionTileBubbleCandidates(
                listOf(
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = false),
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = false)
                )
            )
        )
        assertFalse(
            shouldUnionTileBubbleCandidates(
                listOf(
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = false),
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = true)
                )
            )
        )
        assertTrue(
            shouldUnionTileBubbleCandidates(
                listOf(
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = true),
                    BubblePriorityCandidate(0.8f, true, 100f, touchesInternalTileBoundary = true)
                )
            )
        )
    }

    @Test
    fun `tile contour merge covers upper and lower partial masks`() {
        val upper = floatArrayOf(
            0.20f, 0.20f,
            0.20f, 0.52f,
            0.60f, 0.52f,
            0.60f, 0.20f
        )
        val lower = floatArrayOf(
            0.22f, 0.46f,
            0.22f, 0.80f,
            0.62f, 0.80f,
            0.62f, 0.46f
        )

        val merged = mergePageMaskContours(listOf(upper, lower), pageHeight = 7000)

        requireNotNull(merged)
        val xs = merged.indices.filter { it % 2 == 0 }.map { merged[it] }
        val ys = merged.indices.filter { it % 2 == 1 }.map { merged[it] }
        assertEquals(0.20f, xs.min(), 1e-4f)
        assertEquals(0.62f, xs.max(), 1e-4f)
        assertEquals(0.20f, ys.min(), 1e-4f)
        assertEquals(0.80f, ys.max(), 1e-4f)
    }

    @Test
    fun `bubble dedup matches highly overlapping or contained rectangles`() {
        val overlappingA = RectF(0f, 0f, 100f, 100f)
        val overlappingB = RectF(5f, 5f, 95f, 95f)
        val container = RectF(0f, 0f, 100f, 100f)
        val inside = RectF(5f, 5f, 95f, 95f)
        val shiftedTileDuplicateA = RectF(100f, 1800f, 420f, 2120f)
        val shiftedTileDuplicateB = RectF(155f, 1740f, 455f, 2050f)
        val separate = RectF(150f, 0f, 250f, 100f)
        val stackedNeighborA = RectF(100f, 1000f, 420f, 1320f)
        val stackedNeighborB = RectF(120f, 1225f, 440f, 1545f)
        // Adjacent tiles often split one balloon into upper/lower halves with modest overlap.
        val tileSplitUpper = RectF(200f, 2000f, 520f, 2280f)
        val tileSplitLower = RectF(210f, 2200f, 530f, 2550f)
        // Two distinct stacked bubbles with a clear gap should stay separate.
        val distinctStackedA = RectF(100f, 1000f, 420f, 1280f)
        val distinctStackedB = RectF(110f, 1320f, 430f, 1600f)

        assertTrue(shouldTreatRectsAsSameBubbleForDedup(overlappingA, overlappingB))
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(container, inside))
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(shiftedTileDuplicateA, shiftedTileDuplicateB))
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(tileSplitUpper, tileSplitLower))
        assertFalse(shouldTreatRectsAsSameBubbleForDedup(overlappingA, separate))
        // Partial-overlap path still merges tightly stacked neighbors with large Y overlap;
        // only clearly gapped pairs stay separate.
        assertFalse(shouldTreatRectsAsSameBubbleForDedup(distinctStackedA, distinctStackedB))
        // Keep previous tight-neighbor fixture for regression visibility of partial-overlap path.
        assertTrue(shouldTreatRectsAsSameBubbleForDedup(stackedNeighborA, stackedNeighborB))
    }

    @Test
    fun `tile dedup only merges duplicate candidates from different tiles`() {
        val first = RectF(100f, 100f, 400f, 400f)
        val intersecting = RectF(160f, 120f, 460f, 420f)

        assertFalse(
            shouldDeduplicateTileCandidates(
                firstTileIndex = 2,
                secondTileIndex = 2,
                firstRect = first,
                secondRect = intersecting
            )
        )
        assertTrue(
            shouldDeduplicateTileCandidates(
                firstTileIndex = 2,
                secondTileIndex = 3,
                firstRect = first,
                secondRect = intersecting
            )
        )
        assertEquals(
            RectF(100f, 100f, 460f, 420f),
            unionDetectionRects(listOf(first, intersecting))
        )
    }

    @Test
    fun `text suppression only removes actual bubble overlap`() {
        val bubble = RectF(100f, 100f, 400f, 400f)
        val adjacentText = RectF(110f, 400f, 410f, 700f)
        val containedText = RectF(150f, 150f, 250f, 250f)

        assertFalse(shouldFilterTextRectByBubble(adjacentText, bubble, 0.2f))
        assertTrue(shouldFilterTextRectByBubble(containedText, bubble, 0.2f))
    }

    @Test
    fun `detection strategy tag switches between full and tiled modes`() {
        assertEquals(
            "det_full_yolo26nseg1472_paddle_blocks_v3",
            buildDetectionStrategyTag(pageWidth = 640, pageHeight = 640)
        )
        assertEquals(
            "det_full_yolo26nseg1472_paddle_blocks_v3",
            buildDetectionStrategyTag(pageWidth = 1080, pageHeight = 1600)
        )
        assertEquals(
            "det_full_yolo26nseg1472_paddle_blocks_v3",
            buildDetectionStrategyTag(pageWidth = 4096, pageHeight = 4096)
        )
        assertEquals(
            "det_full_yolo26nseg1472_paddle_blocks_v3",
            buildDetectionStrategyTag(pageWidth = 8192, pageHeight = 2048)
        )
        assertEquals(
            "det_vertical_tiled_yolo26nseg1472_paddle_blocks_v3",
            buildDetectionStrategyTag(pageWidth = 1000, pageHeight = 2200)
        )
    }

    @Test
    fun `text line spanning two overlapping bubbles rejoins them`() {
        // Geometry from real_images/bubble_overlap_case.jpg, where the detector split one
        // balloon into overlapping halves and the middle text line crossed both.
        val leftHalf = BubbleDetection(
            rect = RectF(0f, 857.30f, 710.61f, 1139.42f),
            confidence = 0.81f,
            classId = 0
        )
        val rightHalf = BubbleDetection(
            rect = RectF(262.59f, 848.07f, 939f, 1078.38f),
            confidence = 0.86f,
            classId = 0
        )
        val unrelatedBubble = BubbleDetection(
            rect = RectF(7.70f, 0f, 581.31f, 355.60f),
            confidence = 0.9f,
            classId = 0
        )
        val spanningLine = RectF(59.12f, 952.22f, 877.07f, 988.46f)

        val merged = mergeBubblesSpannedByTextLines(
            balloons = listOf(leftHalf, rightHalf, unrelatedBubble),
            textLines = listOf(spanningLine),
            pageWidth = 940,
            pageHeight = 1830
        )

        assertEquals(2, merged.size)
        val rejoined = merged.first { it.rect.top > 500f }
        assertEquals(0f, rejoined.rect.left, 0.01f)
        assertEquals(848.07f, rejoined.rect.top, 0.01f)
        assertEquals(939f, rejoined.rect.right, 0.01f)
        assertEquals(1139.42f, rejoined.rect.bottom, 0.01f)
        // The union keeps the stronger fragment's confidence.
        assertEquals(0.86f, rejoined.confidence, 0.001f)
        assertTrue(merged.any { it.rect == unrelatedBubble.rect })
    }

    @Test
    fun `text lines do not rejoin bubbles that merely sit side by side`() {
        val left = BubbleDetection(RectF(0f, 100f, 400f, 400f), 0.9f, 0)
        val right = BubbleDetection(RectF(420f, 100f, 820f, 400f), 0.9f, 0)
        // A line crossing the gap must not join balloons that share no area.
        val crossingLine = RectF(300f, 240f, 520f, 280f)

        assertEquals(
            2,
            mergeBubblesSpannedByTextLines(
                balloons = listOf(left, right),
                textLines = listOf(crossingLine),
                pageWidth = 940,
                pageHeight = 1830
            ).size
        )
    }

    @Test
    fun `line contained in one bubble does not trigger rejoin`() {
        val big = BubbleDetection(RectF(0f, 100f, 700f, 500f), 0.9f, 0)
        val overlapping = BubbleDetection(RectF(300f, 120f, 900f, 460f), 0.8f, 0)
        // Fully inside `big`, so it is no evidence about the pair.
        val innerLine = RectF(40f, 300f, 380f, 330f)

        assertEquals(
            2,
            mergeBubblesSpannedByTextLines(
                balloons = listOf(big, overlapping),
                textLines = listOf(innerLine),
                pageWidth = 940,
                pageHeight = 1830
            ).size
        )
    }

    @Test
    fun `vertically stacked bubbles are not rejoined by an unrelated line`() {
        val upper = BubbleDetection(RectF(100f, 100f, 600f, 400f), 0.9f, 0)
        val lower = BubbleDetection(RectF(120f, 360f, 620f, 700f), 0.9f, 0)
        // Overlaps both in x and lands in the shared band, but only 40px of vertical overlap
        // exists; the line center must fall inside both boxes.
        val lineBelow = RectF(60f, 500f, 700f, 540f)

        assertEquals(
            2,
            mergeBubblesSpannedByTextLines(
                balloons = listOf(upper, lower),
                textLines = listOf(lineBelow),
                pageWidth = 940,
                pageHeight = 1830
            ).size
        )
    }
}
