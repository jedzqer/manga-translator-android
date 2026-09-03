package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import com.manga.translate.detection.OnnxImagePreprocessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnnxImagePreprocessorTest {

    @Test
    fun `rgb chw buffer is normalized for Ultralytics inputs`() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.rgb(255, 128, 0))

        try {
            assertArrayEquals(
                floatArrayOf(1f, 128f / 255f, 0f),
                OnnxImagePreprocessor.bitmapToRgbChwFloat(bitmap),
                1e-6f
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `short image is stretched to fill input without gray bars`() {
        val source = Bitmap.createBitmap(200, 50, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(200, 10, 30))
        try {
            val result = OnnxImagePreprocessor.letterbox(source, 100, 100)
            try {
                assertEquals(100, result.bitmap.width)
                assertEquals(100, result.bitmap.height)
                assertEquals(0.5f, result.gainX, 1e-6f)
                assertEquals(2f, result.gainY, 1e-6f)
                assertEquals(0f, result.padX, 1e-6f)
                assertEquals(0f, result.padY, 1e-6f)
                // 拉伸填满：四角都是图像内容，不再有上下灰色 pad 带。
                assertEquals(Color.rgb(200, 10, 30), result.bitmap.getPixel(1, 1))
                assertEquals(Color.rgb(200, 10, 30), result.bitmap.getPixel(98, 1))
                assertEquals(Color.rgb(200, 10, 30), result.bitmap.getPixel(1, 98))
                assertEquals(Color.rgb(200, 10, 30), result.bitmap.getPixel(98, 98))
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `tall image keeps uniform letterbox with side padding`() {
        val source = Bitmap.createBitmap(50, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(10, 200, 40))
        try {
            val result = OnnxImagePreprocessor.letterbox(source, 100, 100)
            try {
                assertEquals(0.5f, result.gainX, 1e-6f)
                assertEquals(0.5f, result.gainY, 1e-6f)
                assertEquals(37.5f, result.padX, 1e-6f)
                assertEquals(0f, result.padY, 1e-6f)
                assertEquals(GRAY_PAD, result.bitmap.getPixel(5, 50))
                assertEquals(Color.rgb(10, 200, 40), result.bitmap.getPixel(50, 50))
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `square image fits input without padding`() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(30, 30, 220))
        try {
            val result = OnnxImagePreprocessor.letterbox(source, 100, 100)
            try {
                assertEquals(1f, result.gainX, 1e-6f)
                assertEquals(1f, result.gainY, 1e-6f)
                assertEquals(0f, result.padX, 1e-6f)
                assertEquals(0f, result.padY, 1e-6f)
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `inverse mapping inverts forward transform for stretched short images`() {
        val source = Bitmap.createBitmap(200, 50, Bitmap.Config.ARGB_8888)
        try {
            val result = OnnxImagePreprocessor.letterbox(source, 100, 100)
            try {
                for (originalX in floatArrayOf(0f, 37.5f, 100f, 199f)) {
                    for (originalY in floatArrayOf(0f, 12.25f, 25f, 49f)) {
                        val inputX = originalX * result.gainX + result.padX
                        val inputY = originalY * result.gainY + result.padY
                        assertEquals(
                            originalX,
                            OnnxImagePreprocessor.toOriginalX(inputX, result),
                            1e-3f
                        )
                        assertEquals(
                            originalY,
                            OnnxImagePreprocessor.toOriginalY(inputY, result),
                            1e-3f
                        )
                    }
                }
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `inverse mapping inverts forward transform for letterboxed tall images`() {
        val source = Bitmap.createBitmap(50, 200, Bitmap.Config.ARGB_8888)
        try {
            val result = OnnxImagePreprocessor.letterbox(source, 100, 100)
            try {
                for (originalX in floatArrayOf(0f, 24.5f, 49f)) {
                    for (originalY in floatArrayOf(0f, 100f, 199f)) {
                        val inputX = originalX * result.gainX + result.padX
                        val inputY = originalY * result.gainY + result.padY
                        assertEquals(
                            originalX,
                            OnnxImagePreprocessor.toOriginalX(inputX, result),
                            1e-3f
                        )
                        assertEquals(
                            originalY,
                            OnnxImagePreprocessor.toOriginalY(inputY, result),
                            1e-3f
                        )
                    }
                }
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private companion object {
        val GRAY_PAD = Color.rgb(114, 114, 114)
    }
}
