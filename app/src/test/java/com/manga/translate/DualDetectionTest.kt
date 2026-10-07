package com.manga.translate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.detection.*
import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DualDetectionTest {
    private fun row(bubble: Float = 0.9f, text: Float = 0.1f, x: Float = 0.5f) =
        floatArrayOf(x, 0.5f, 0.2f, 0.4f, bubble, text) + FloatArray(32) { it - 8f }

    private fun decode(vararg rows: FloatArray) = decodeDualDetections(
        FloatBuffer.wrap(FloatArray(38 * rows.size) { rows[it % rows.size][it / rows.size] }),
        rows.size, 832, 832, 0.25f
    )

    @Test
    fun `GPU input is normalized NHWC RGB and reused storage is fully overwritten`() {
        val output = FloatArray(6) { Float.NaN }
        writeDetectionRgbInput(intArrayOf(0xffff0000.toInt(), 0xff0080ff.toInt()), output)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 128f / 255f, 1f), output, 0f)
        writeDetectionRgbInput(intArrayOf(0, 0xffffffff.toInt()), output)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), output, 0f)
    }

    @Test
    fun `channel first head denormalizes coordinates and classifies both labels`() {
        val output = decode(row(), row(bubble = 0.1f, text = 0.8f, x = 0.2f))
        assertEquals(listOf(0, 1), output.map { it.classId })
        assertEquals(416f, output[0].cx, 0.001f)
        assertEquals(166.4f, output[0].width, 0.001f)
        assertEquals(332.8f, output[0].height, 0.001f)
        assertEquals(166.4f, output[1].cx, 0.001f)
        output.forEach { assertArrayEquals(FloatArray(32) { it - 8f }, it.maskCoefficients, 0f) }
    }

    @Test
    fun `NMS keeps strongest same class candidate and overlapping other class`() {
        val output = decode(row(bubble = 0.7f), row(), row(bubble = 0.1f, text = 0.8f), row(x = 0.1f))
        assertEquals(3, output.size)
        assertEquals(2, output.count { it.classId == 0 })
        assertEquals(1, output.count { it.classId == 1 })
        assertFalse(output.any { it.confidence == 0.7f })
    }

    @Test
    fun `invalid candidates and below threshold scores cannot become regions`() {
        assertTrue(decode(
            row(bubble = 0.3f),
            row().apply { this[0] = Float.NaN },
            row().apply { this[2] = -0.1f },
            row().apply { this[4] = Float.POSITIVE_INFINITY },
            row().apply { this[3] = Float.NaN },
            row().apply { this[5] = Float.NaN },
            row().apply { this[6] = Float.NaN }
        ).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed tensor fails rather than caching an empty page`() {
        decodeDualDetections(FloatBuffer.wrap(FloatArray(5)), 1, 832, 832, 0.25f)
    }

    @Test
    fun `letterbox inverse removes padding after normalized head decoding`() {
        val bitmap = Bitmap.createBitmap(832, 832, Bitmap.Config.ARGB_8888)
        try {
            val preprocessing = LetterboxResult(bitmap, 0.5f, 0.5f, 208f, 0f, 832, 832)
            val rect = decode(row()).single().toRect(preprocessing, 832, 1664)
            assertEquals(249.6f, rect.left, 0.01f)
            assertEquals(582.4f, rect.right, 0.01f)
            assertEquals(499.2f, rect.top, 0.01f)
            assertEquals(1164.8f, rect.bottom, 0.01f)
        } finally { bitmap.recycle() }
    }

    @Test
    fun `NHWC prototype uses each pixel channel rather than NCHW plane`() {
        val bitmap = Bitmap.createBitmap(832, 832, Bitmap.Config.ARGB_8888)
        try {
            val proto = FloatArray(8 * 8 * 32) { -10f }
            for (y in 2..5) for (x in 2..5) proto[(y * 8 + x) * 32 + 7] = 10f
            val detection = RawDetection(416f, 416f, 832f, 832f, 0.9f, 0,
                FloatArray(32) { if (it == 7) 1f else 0f })
            val contour = requireNotNull(computeDualMaskContour(detection, FloatBuffer.wrap(proto),
                8, 8, LetterboxResult(bitmap, 1f, 1f, 0f, 0f, 832, 832), 832, 832, 832, 832))
            val xs = contour.filterIndexed { index, _ -> index % 2 == 0 }
            assertEquals(0.25f, xs.min(), 0.0001f)
            assertEquals(0.75f, xs.max(), 0.0001f)
        } finally { bitmap.recycle() }
    }

    @Test
    fun `segmentation preprocessing preserves short image aspect and inverse coordinates`() {
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val result = OnnxImagePreprocessor.letterbox(source, 100, 100, stretchShortImages = false)
        try {
            assertEquals(0.5f, result.gainX, 0f)
            assertEquals(0.5f, result.gainY, 0f)
            assertEquals(0f, result.padX, 0f)
            assertEquals(25f, result.padY, 0f)
            assertEquals(50f, OnnxImagePreprocessor.toOriginalY(50f, result), 0f)
        } finally {
            result.bitmap.recycle()
            source.recycle()
        }
    }

    @Test
    fun `zero prototype logits do not produce a filled bubble contour`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val detection = RawDetection(50f, 50f, 100f, 100f, 0.9f, 0, FloatArray(32))
            assertNull(computeDualMaskContour(detection, FloatBuffer.wrap(FloatArray(8 * 8 * 32)),
                8, 8, LetterboxResult(bitmap, 1f, 1f, 0f, 0f, 100, 100), 100, 100, 100, 100))
        } finally { bitmap.recycle() }
    }

    @Test
    fun `dual model text blocks never masquerade as OCR lines`() {
        val text = RectF(20f, 20f, 80f, 80f)
        val blocks = dualTextBlocks(listOf(text, RectF(20f, 81f, 80f, 120f)), 200, 200)
        assertEquals(2, blocks.size) // Adjacent model blocks must not be regrouped as lines.
        assertTrue(blocks.all { it.lines.isEmpty() })
    }

    @Test
    fun `overlapping text blocks retain the full union and update the mask`() {
        val first = RectF(20f, 20f, 80f, 80f)
        val second = RectF(60f, 20f, 120f, 80f)
        val block = dualTextBlocks(listOf(first, second, RectF(first)), 200, 200).single()
        assertEquals(RectF(20f, 20f, 120f, 80f), block.rect)
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.1f, 0.4f, 0.6f, 0.4f, 0.6f, 0.1f),
            block.maskContour, 0.0001f)
        assertTrue(block.lines.isEmpty())
        assertEquals(RectF(20f, 20f, 80f, 80f), first)
        assertEquals(RectF(60f, 20f, 120f, 80f), second)
    }

    @Test
    fun `contained text and overlap chains merge regardless of input order`() {
        val rects = listOf(RectF(0f, 0f, 100f, 100f), RectF(70f, 0f, 170f, 100f),
            RectF(140f, 0f, 240f, 100f), RectF(10f, 10f, 20f, 20f))
        for (order in listOf(rects, rects.reversed(), listOf(rects[2], rects[0], rects[3], rects[1]))) {
            assertEquals(RectF(0f, 0f, 240f, 100f), dualTextBlocks(order, 300, 200).single().rect)
        }
    }

    @Test
    fun `slight overlap and edge contact keep separate text blocks`() {
        val rects = listOf(RectF(0f, 0f, 100f, 100f), RectF(80f, 0f, 180f, 100f),
            RectF(180f, 0f, 280f, 100f))
        assertEquals(rects, dualTextBlocks(rects, 300, 200).map { it.rect })
        assertTrue(dualTextBlocks(emptyList(), 300, 200).isEmpty())
    }

    @Test
    fun `empty space inside a merged bounding box does not absorb unrelated text`() {
        val rects = listOf(RectF(0f, 0f, 100f, 40f), RectF(0f, 0f, 40f, 100f),
            RectF(60f, 60f, 90f, 90f))
        assertEquals(listOf(RectF(0f, 0f, 100f, 100f), rects[2]),
            dualTextBlocks(rects, 200, 200).map { it.rect })
    }
}
