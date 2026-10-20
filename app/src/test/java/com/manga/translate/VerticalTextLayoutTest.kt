package com.manga.translate

import android.text.TextPaint
import com.manga.translate.rendering.BubbleTextScaling
import com.manga.translate.rendering.VerticalTextLayoutCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerticalTextLayoutTest {
    @Test
    fun explicitBreaksAreIncludedInMeasuredColumns() {
        val layout = VerticalTextLayoutCalculator.build(TextPaint(), "甲\n乙\n丙", 200, 500, 24f)
        assertEquals(3, layout.columns)
        assertEquals(layout.lineHeight, layout.totalHeight, 0.01f)
        assertEquals(layout.columnWidth * 3, layout.totalWidth, 0.01f)
    }

    @Test
    fun shortTextUsesOnlyRowsActuallyOccupied() {
        val paint = TextPaint().apply { textSize = 24f }
        val layout = VerticalTextLayoutCalculator.build(
            textPaint = paint,
            text = "短文",
            maxWidth = 500,
            maxHeight = 2_000,
            textSize = 24f
        )

        assertEquals(layout.lineHeight * 2f, layout.totalHeight, 0.01f)
        assertTrue(layout.totalHeight < layout.maxRows * layout.lineHeight)
    }
    @Test
    fun prefersSingleColumnWhenOnlyModestShrinkingIsNeeded() {
        val paint = TextPaint()
        val text = "春夏秋冬天地"
        // Derive dimensions from actual font metrics to avoid device font assumptions.
        val reference = VerticalTextLayoutCalculator.build(paint, text, 1000, 1000, 30f)
        val width = (reference.columnWidth * 2.1f).toInt()
        val height = (reference.lineHeight * 5.2f).toInt()
        val build = { size: Float -> VerticalTextLayoutCalculator.build(paint, text, width, height, size) }
        val largest = BubbleTextScaling.findLargestFittingTextSize(width, height) { build(it).fits }
        assertEquals(2, build(largest).columns)
        val preferred = BubbleTextScaling.findAutoVerticalTextSize(width, height, build)
        assertEquals(1, build(preferred).columns)
        assertTrue(build(preferred).fits)
        assertTrue(preferred >= largest * 0.8f)
    }

    @Test
    fun longTextKeepsMultipleColumnsAtReadableSize() {
        val paint = TextPaint()
        val text = "春夏秋冬天地".repeat(10)
        val build = { size: Float -> VerticalTextLayoutCalculator.build(paint, text, 160, 240, size) }
        val largest = BubbleTextScaling.findLargestFittingTextSize(160, 240) { build(it).fits }
        val preferred = BubbleTextScaling.findAutoVerticalTextSize(160, 240, build)
        assertTrue(preferred >= largest * 0.8f)
        assertTrue(build(preferred).columns > 1)
        assertTrue(build(preferred).fits)
    }

    @Test
    fun singleColumnPreferencePreservesParagraphBreaks() {
        val paint = TextPaint()
        val text = BubbleTextScaling.prepareTextForLayout("春夏\n\n秋冬")
        val build = { size: Float -> VerticalTextLayoutCalculator.build(paint, text, 160, 240, size) }
        val size = BubbleTextScaling.findAutoVerticalTextSize(160, 240, build)
        assertEquals(3, build(size).columns)
        assertTrue(build(size).fits)
    }

    @Test
    fun prefersTwoColumnsOverThreeWithoutMakingTextTooSmall() {
        val paint = TextPaint()
        val text = "春夏秋冬天地玄黄"
        val reference = VerticalTextLayoutCalculator.build(paint, text, 1000, 1000, 30f)
        val width = (reference.columnWidth * 3.1f).toInt()
        val height = (reference.lineHeight * 3.5f).toInt()
        val build = { size: Float -> VerticalTextLayoutCalculator.build(paint, text, width, height, size) }
        val largest = BubbleTextScaling.findLargestFittingTextSize(width, height) { build(it).fits }
        assertEquals(3, build(largest).columns)
        val preferred = BubbleTextScaling.findAutoVerticalTextSize(width, height, build)
        assertEquals(2, build(preferred).columns)
        assertTrue(preferred >= largest * 0.8f)
        assertTrue(build(preferred).fits)
    }

}
