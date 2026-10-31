package com.manga.translate

import android.graphics.RectF
import com.manga.translate.detection.PageRegion
import com.manga.translate.detection.PageRegionDetectionMode
import com.manga.translate.detection.PageRegionDetectionResult
import com.manga.translate.model.BubbleSource
import com.manga.translate.translation.ocrRegionCacheMode
import com.manga.translate.translation.selectPageRegionsForOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BubbleOnlyOcrSelectionTest {
    @Test
    fun `bubble-only OCR drops text regions when bubbles were detected`() {
        val bubble = PageRegion(1, RectF(0f, 0f, 40f, 40f), BubbleSource.BUBBLE_DETECTOR)
        val text = PageRegion(2, RectF(50f, 50f, 90f, 70f), BubbleSource.TEXT_DETECTOR)
        val detection = detection(listOf(bubble, text))

        val filtered = selectPageRegionsForOcr(detection, bubbleOnlyEnabled = true)

        assertEquals(1, filtered.regions.size)
        assertSame(bubble, filtered.regions.single())
        assertEquals(emptyList<RectF>(), filtered.textRects)
        assertEquals(2, detection.regions.size)
        assertSame(text, detection.regions.last())
    }

    @Test
    fun `bubble-only OCR excludes text-only pages`() {
        val text = PageRegion(1, RectF(10f, 10f, 90f, 30f), BubbleSource.TEXT_DETECTOR)
        val detection = detection(listOf(text))

        val filtered = selectPageRegionsForOcr(detection, bubbleOnlyEnabled = true)

        assertEquals(emptyList<PageRegion>(), filtered.regions)
        assertEquals(emptyList<RectF>(), filtered.textRects)
        assertEquals(listOf(text), detection.regions)
    }

    @Test
    fun `only-bubbles cache mode is local-only and separated from existing OCR cache`() {
        assertEquals("bubbles_only_strict", ocrRegionCacheMode(onlyTranslateBubbles = true, useLocalOcr = true))
        assertEquals("bubbles_and_text", ocrRegionCacheMode(onlyTranslateBubbles = true, useLocalOcr = false))
        assertEquals("bubbles_and_text", ocrRegionCacheMode(onlyTranslateBubbles = false, useLocalOcr = true))
    }

    @Test
    fun `disabled bubble-only OCR preserves text regions`() {
        val text = PageRegion(1, RectF(10f, 10f, 90f, 30f), BubbleSource.TEXT_DETECTOR)
        val detection = detection(listOf(text))

        assertSame(detection, selectPageRegionsForOcr(detection, bubbleOnlyEnabled = false))
    }

    private fun detection(regions: List<PageRegion>) = PageRegionDetectionResult(
        width = 100,
        height = 100,
        bubbleDetections = emptyList(),
        textRects = regions.filter { it.source == BubbleSource.TEXT_DETECTOR }.map { it.rect },
        regions = regions,
        detectionMode = PageRegionDetectionMode.FULL
    )
}
