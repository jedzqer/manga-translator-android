package com.manga.translate

import android.text.TextPaint
import com.manga.translate.rendering.BubbleTextScaling
import com.manga.translate.rendering.VerticalTextLayoutCalculator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Integration checks for the size search: they go through real [TextPaint] metrics and
 * [BubbleTextScaling], not just the breaker, because the breaker alone cannot show
 * that the chosen font actually fits the bubble.
 *
 * These are sample regressions with the Robolectric font, not a proof of monotonicity
 * for every text and bubble.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VerticalTextAutoFitTest {

    private fun fits(text: String, maxHeight: Int, textSize: Float): Boolean =
        VerticalTextLayoutCalculator.build(
            textPaint = TextPaint(),
            text = text,
            maxWidth = 600,
            maxHeight = maxHeight,
            textSize = textSize
        ).fits

    @Test
    fun shrinkingTheFontNeverTurnsAFittingLayoutIntoAnOverflowingOne() {
        // A word chunked by maxRows made this loop fail: the layout fitted at a large
        // font and overflowed at a smaller one. The binary size search needs the
        // opposite: once a size fits, every smaller size must fit too.
        val text = "ABCDEF。漢字"
        val maxHeight = 200
        var sawOverflow = false
        var sawFit = false
        var size = 48f
        while (size >= 1f) {
            if (fits(text, maxHeight, size)) {
                sawFit = true
            } else {
                sawOverflow = true
                assertFalse("overflowed again when the font shrank to $size", sawFit)
            }
            size -= 1f
        }
        assertTrue("the loop never saw a fitting size", sawFit)
        assertTrue("the loop never saw an overflowing size, so it proves nothing", sawOverflow)
    }

    @Test
    fun autoSizeReturnsAFontThatFitsCjkTextWithNumbers() {
        val text = "価格は1,200円です。"
        val paint = TextPaint()
        val maxWidth = 200
        val maxHeight = 400
        val size = BubbleTextScaling.findAutoVerticalTextSize(maxWidth, maxHeight, 100) { candidate ->
            VerticalTextLayoutCalculator.build(paint, text, maxWidth, maxHeight, candidate)
        }
        val layout = VerticalTextLayoutCalculator.build(paint, text, maxWidth, maxHeight, size)
        assertTrue("layout must fit at the chosen size $size", layout.fits)
        assertTrue("the chosen size $size must stay above the 0.5px floor", size > 1f)
    }

    @Test
    fun autoSizeStillFitsWithReducedFillPercent() {
        val text = "これはテストです、しかし「重要」と言った。次"
        val paint = TextPaint()
        val maxWidth = 200
        val maxHeight = 400
        val size = BubbleTextScaling.findAutoVerticalTextSize(maxWidth, maxHeight, 80) { candidate ->
            VerticalTextLayoutCalculator.build(paint, text, maxWidth, maxHeight, candidate)
        }
        val layout = VerticalTextLayoutCalculator.build(paint, text, maxWidth, maxHeight, size)
        assertTrue("layout must fit at the chosen size $size", layout.fits)
    }

    @Test
    fun aClusterTallerThanTheBubbleNeverFits() {
        // A term far longer than the bubble hangs past the bottom at every font size, so
        // the real search lands on its 0.5px floor and still reports not-fitting. Callers
        // must clip in that case; a returned size does not mean the text was placed.
        val text = "supercalifragilisticexpialidocious".repeat(4)
        val paint = TextPaint()
        val maxWidth = 40
        val maxHeight = 20
        val size = BubbleTextScaling.findAutoVerticalTextSize(maxWidth, maxHeight, 100) { candidate ->
            VerticalTextLayoutCalculator.build(paint, text, maxWidth, maxHeight, candidate)
        }
        assertTrue("the search must not invent a usable size, got $size", size <= 0.5f)
        val layout = VerticalTextLayoutCalculator.build(paint, text, maxWidth, maxHeight, size)
        assertFalse("an over-long unbreakable term cannot fit", layout.fits)
        assertTrue("the term must overflow the bubble height", layout.totalHeight > 20f)
    }
}
