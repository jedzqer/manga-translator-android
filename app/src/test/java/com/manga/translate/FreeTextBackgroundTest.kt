package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import com.manga.translate.rendering.FreeTextBackground
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeTextBackgroundTest {
    @Test
    fun `glyph backing follows local colors and leaves spaces transparent`() {
        val samples = Bitmap.createBitmap(120, 50, Bitmap.Config.ARGB_8888)
        val sampleCanvas = Canvas(samples)
        sampleCanvas.drawColor(Color.RED)
        sampleCanvas.drawRect(60f, 0f, 120f, 50f, Paint().apply { color = Color.BLUE })
        val output = Bitmap.createBitmap(120, 50, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f }
        FreeTextBackground.draw(samples, RectF(0f, 0f, 120f, 50f), paint, 255) {
            canvas.drawText("A", 8f, 36f, paint)
            canvas.drawText("A", 80f, 36f, paint)
        }
        val left = (4..36).flatMap { x -> (4..40).map { y -> output.getPixel(x, y) } }
        val right = (76..108).flatMap { x -> (4..40).map { y -> output.getPixel(x, y) } }
        assertTrue(left.any { Color.red(it) > Color.blue(it) + 60 })
        assertTrue(right.any { Color.blue(it) > Color.red(it) + 60 })
        assertTrue(left.contains(Color.BLACK))
        assertTrue((0 until 50).all { output.getPixel(50, it) == Color.TRANSPARENT })
    }
}
