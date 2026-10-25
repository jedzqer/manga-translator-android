package com.manga.translate

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.manga.translate.rendering.BubbleTextScaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleTextScalingTest {
    @Test
    fun fillPercentScalesHorizontalAndVerticalFontsWithoutOverflow() {
        val paint = TextPaint()
        val text = "春夏秋冬 Hello world\n\n第二段"
        val horizontal = { content: String, width: Int, size: Float ->
            paint.textSize = size
            StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
                .setIncludePad(false).build()
        }
        val vertical = { size: Float ->
            com.manga.translate.rendering.VerticalTextLayoutCalculator.build(paint, text, 160, 240, size)
        }
        val defaultHorizontal = BubbleTextScaling.findAutoHorizontalTextSize(
            text, 160, 240, horizontal, BubbleTextScaling::layoutFits)
        val defaultVertical = BubbleTextScaling.findAutoVerticalTextSize(160, 240, buildLayout = vertical)
        for (percent in listOf(-10, 20, 50, 80, 100, 150)) {
            val ratio = percent.coerceIn(20, 100) / 100f
            val h = BubbleTextScaling.findAutoHorizontalTextSize(
                text, 160, 240, horizontal, BubbleTextScaling::layoutFits, percent)
            val v = BubbleTextScaling.findAutoVerticalTextSize(160, 240, percent, vertical)
            assertEquals(defaultHorizontal * ratio, h, 0.01f)
            assertEquals(defaultVertical * ratio, v, 0.01f)
            val layout = horizontal(text, 160, h)
            assertTrue(layout.height <= 240)
            // Reflow can leave a trailing space beyond the line; visible glyphs must fit.
            for (line in 0 until layout.lineCount) assertTrue(layout.getLineMax(line) <= 160.5f)
            assertTrue(vertical(v).fits)
        }
    }

    @Test
    fun sourceLineWrappingReflowsButParagraphsRemain() {
        assertEquals("你好世界\n\n第二段", BubbleTextScaling.prepareTextForLayout("你好\n世界\n\n第二段"))
        assertEquals("Hello world", BubbleTextScaling.prepareTextForLayout("Hello\r\nworld"))
    }

    @Test
    fun reflowedShortLinesUseLargerFontWithoutOverflow() {
        val paint = TextPaint()
        val build = { text: String, width: Int, size: Float ->
            paint.textSize = size
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setIncludePad(false).build()
        }
        val original = "你好\n世界\n今天\n晴天"
        val reflowed = BubbleTextScaling.prepareTextForLayout(original)
        fun size(text: String) = BubbleTextScaling.findAutoHorizontalTextSize(
            text, 160, 80, build, BubbleTextScaling::layoutFits
        )
        val before = size(original)
        val after = size(reflowed)
        assertTrue("before=$before after=$after", after > before * 1.2f)
        assertTrue(BubbleTextScaling.layoutFits(build(reflowed, 160, after), 160, 80))
    }

    @Test
    fun horizontalAutoSizePrefersFewerLinesWithoutShrinkingBelowEightyPercent() {
        val paint = TextPaint()
        val text = "MMMMMMMM"
        val referenceSize = 30f
        paint.textSize = referenceSize
        val width = (paint.measureText(text) * 0.9f).toInt()
        val height = (paint.fontMetrics.run { descent - ascent } * 2.1f).toInt()
        val build = { content: String, availableWidth: Int, size: Float ->
            paint.textSize = size
            StaticLayout.Builder.obtain(content, 0, content.length, paint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
        }
        val largest = BubbleTextScaling.findLargestFittingTextSize(width, height) { size ->
            BubbleTextScaling.layoutFits(build(text, width, size), width, height)
        }
        val preferred = BubbleTextScaling.findAutoHorizontalTextSize(
            text = text,
            maxWidth = width,
            maxHeight = height,
            buildLayout = build,
            layoutFits = BubbleTextScaling::layoutFits
        )

        assertTrue(build(text, width, largest).lineCount > build(text, width, preferred).lineCount)
        assertTrue("largest=$largest preferred=$preferred", preferred >= largest * 0.8f)
        assertTrue(BubbleTextScaling.layoutFits(build(text, width, preferred), width, height))
    }

    @Test
    fun horizontalAutoSizePreservesParagraphBreaksWhenTheySetTheLineCount() {
        val paint = TextPaint()
        val text = BubbleTextScaling.prepareTextForLayout("AB\n\nCD")
        val build = { content: String, width: Int, size: Float ->
            paint.textSize = size
            StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
                .setIncludePad(false)
                .build()
        }
        val width = 240
        val height = 240
        val largest = BubbleTextScaling.findLargestFittingTextSize(width, height) { size ->
            BubbleTextScaling.layoutFits(build(text, width, size), width, height)
        }
        val preferred = BubbleTextScaling.findAutoHorizontalTextSize(
            text = text,
            maxWidth = width,
            maxHeight = height,
            buildLayout = build,
            layoutFits = BubbleTextScaling::layoutFits
        )

        assertEquals("AB\n\nCD", text)
        assertEquals(3, build(text, width, largest).lineCount)
        assertEquals(largest, preferred, 0f)
    }

    @Test
    fun smallBubbleRetainsMostOfItsTextArea() {
        val path = android.graphics.Path().apply {
            addRect(0f, 0f, 20f, 60f, android.graphics.Path.Direction.CW)
        }
        val rect = BubbleTextScaling.resolveTextRect(path)
        assertTrue("$rect", rect.width() > 17f)
        assertTrue("$rect", rect.height() > 54f)
        assertTrue(rect.left >= 0f && rect.right <= 20f)
        assertTrue(rect.top >= 0f && rect.bottom <= 60f)
    }

    @Test
    fun autoSizeCanShrinkBelowRemovedUserMinimum() {
        val textSize = BubbleTextScaling.findLargestFittingTextSize(
            maxWidth = 100,
            maxHeight = 40,
            fits = { it <= 3.25f }
        )

        assertTrue(textSize > 3f)
        assertTrue(textSize <= 3.25f)
    }

    @Test
    fun autoSizeReturnsTechnicalFloorWhenNothingFits() {
        val textSize = BubbleTextScaling.findLargestFittingTextSize(
            maxWidth = 1,
            maxHeight = 1,
            fits = { false }
        )

        assertEquals(0.5f, textSize, 0f)
    }

    @Test
    fun denseHorizontalTextShrinksUntilLayoutFitsBubble() {
        val text = "文字".repeat(50)
        val paint = TextPaint()
        val width = 40
        val height = 20
        val buildLayout = { textSize: Float ->
            paint.textSize = textSize
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
        }

        val textSize = BubbleTextScaling.findAutoHorizontalTextSize(
            text = text,
            maxWidth = width,
            maxHeight = height,
            buildLayout = { _, _, size -> buildLayout(size) },
            layoutFits = BubbleTextScaling::layoutFits
        )
        val layout = buildLayout(textSize)

        assertTrue(textSize < 8f)
        assertTrue(BubbleTextScaling.layoutFits(layout, width, height))
    }
}
