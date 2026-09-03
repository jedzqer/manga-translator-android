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
