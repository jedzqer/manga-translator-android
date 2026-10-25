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
        assertEquals(2, blocks.size) // Compact neighboring blocks are not paragraph rows.
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

    @Test
    fun `Thai caption rows merge including a short centered last row at any scale`() {
        val rows = listOf(
            RectF(49.3f, 61.3f, 194.4f, 87.6f),
            RectF(47.5f, 101.2f, 197.1f, 133.4f),
            RectF(95.8f, 139.8f, 143.2f, 163.6f)
        )
        for (scale in listOf(0.5f, 1f, 4f)) {
            val scaled = rows.map { RectF(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) }
            for (order in listOf(scaled, scaled.reversed(), listOf(scaled[1], scaled[2], scaled[0]))) {
                val block = dualTextBlocks(order, (240 * scale).toInt(), (300 * scale).toInt()).single()
                assertEquals(RectF(47.5f * scale, 61.3f * scale, 197.1f * scale, 163.6f * scale), block.rect)
                assertTrue(block.lines.isEmpty())
                assertEquals(TextLineOrientation.AMBIGUOUS, block.orientation)
                assertArrayEquals(floatArrayOf(
                    47.5f / 240, 61.3f / 300, 47.5f / 240, 163.6f / 300,
                    197.1f / 240, 163.6f / 300, 197.1f / 240, 61.3f / 300
                ), block.maskContour, 0.0001f)
            }
        }
        assertEquals(RectF(49.3f, 61.3f, 194.4f, 87.6f), rows.first())
    }

    @Test
    fun `large Thai caption merges three separated model rows`() {
        val block = dualTextBlocks(listOf(
            RectF(183.1f, 825.9f, 611.2f, 899.9f),
            RectF(191.5f, 704.7f, 642.5f, 775.8f),
            RectF(152f, 603.5f, 644.4f, 671.8f)
        ), 800, 1000).single()
        assertEquals(RectF(152f, 603.5f, 644.4f, 899.9f), block.rect)
    }

    @Test
    fun `left and right aligned short continuation rows merge`() {
        for (last in listOf(RectF(20f, 48f, 100f, 68f), RectF(140f, 48f, 220f, 68f))) {
            val blocks = dualTextBlocks(listOf(RectF(20f, 20f, 220f, 40f), last), 300, 200)
            assertEquals(RectF(20f, 20f, 220f, 68f), blocks.single().rect)
        }
    }

    @Test
    fun `adjacent rows merge regardless of relative width`() {
        val longRow = RectF(20f, 20f, 420f, 40f)
        // Ten times shorter than the first row; length is not a paragraph boundary.
        for (shortRow in listOf(
            RectF(20f, 48f, 60f, 68f),
            RectF(200f, 48f, 240f, 68f),
            RectF(380f, 48f, 420f, 68f)
        )) {
            for (order in listOf(listOf(longRow, shortRow), listOf(shortRow, longRow))) {
                val block = dualTextBlocks(order, 500, 200).single()
                assertEquals(RectF(20f, 20f, 420f, 68f), block.rect)
                assertTrue(block.lines.isEmpty())
            }
        }
        // A short opening line followed by a long line also belongs to one paragraph.
        assertEquals(RectF(20f, 20f, 420f, 68f), dualTextBlocks(listOf(
            RectF(200f, 20f, 240f, 40f), RectF(20f, 48f, 420f, 68f)
        ), 500, 200).single().rect)
    }

    @Test
    fun `paragraph grouping preserves columns and larger paragraph gaps`() {
        val rows = listOf(
            RectF(20f, 20f, 120f, 40f), RectF(20f, 50f, 120f, 70f),
            RectF(150f, 20f, 250f, 40f), RectF(150f, 50f, 250f, 70f),
            RectF(20f, 110f, 120f, 130f), RectF(20f, 140f, 120f, 160f)
        )
        assertEquals(listOf(
            RectF(20f, 20f, 120f, 70f), RectF(150f, 20f, 250f, 70f),
            RectF(20f, 110f, 120f, 160f)
        ), dualTextBlocks(rows, 300, 200).map { it.rect })
    }

    @Test
    fun `tall text blocks do not bridge large blank gaps on pages or long images`() {
        val blocks = listOf(
            RectF(20f, 20f, 620f, 220f),
            RectF(20f, 340f, 620f, 540f),
            RectF(20f, 660f, 620f, 860f)
        )
        // A detector block can contain several lines: its 200px height is not a
        // font height and must not permit a 120px paragraph gap or a chain of them.
        for (scale in listOf(0.5f, 1f, 2f)) {
            val scaled = blocks.map { RectF(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) }
            for (pageHeight in listOf(1000, 10000)) {
                for (order in listOf(scaled, scaled.reversed())) {
                    assertEquals(order, dualTextBlocks(
                        order, (800 * scale).toInt(), (pageHeight * scale).toInt()
                    ).map { it.rect })
                }
            }
        }
    }

    @Test
    fun `nearby tall text blocks still merge without inflating the paragraph gap`() {
        val blocks = listOf(RectF(20f, 20f, 620f, 220f), RectF(20f, 240f, 620f, 440f))
        assertEquals(RectF(20f, 20f, 620f, 440f), dualTextBlocks(blocks, 800, 1000).single().rect)
    }

    @Test
    fun `two large characters per row do not merge across a large blank gap`() {
        // Approximate two square glyphs in each row, not a multi-line paragraph.
        val largeRows = listOf(RectF(200f, 20f, 600f, 220f), RectF(200f, 340f, 600f, 540f))
        assertEquals(largeRows, dualTextBlocks(largeRows, 800, 1000).map { it.rect })
        // Two characters alone are not a reason to reject normal nearby rows.
        val smallRows = listOf(RectF(200f, 20f, 240f, 40f), RectF(200f, 52f, 240f, 72f))
        assertEquals(RectF(200f, 20f, 240f, 72f), dualTextBlocks(smallRows, 800, 1000).single().rect)
    }

    @Test
    fun `large text does not relax horizontal alignment beyond the short edge scale`() {
        val rows = listOf(RectF(100f, 20f, 500f, 220f), RectF(150f, 240f, 550f, 440f))
        for (scale in listOf(0.5f, 1f, 2f)) {
            val scaled = rows.map { RectF(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) }
            for (pageHeight in listOf(1000, 10000)) {
                assertEquals(scaled, dualTextBlocks(
                    scaled, (800 * scale).toInt(), (pageHeight * scale).toInt()
                ).map { it.rect })
            }
        }
        val aligned = listOf(rows.first(), RectF(120f, 240f, 520f, 440f))
        assertEquals(RectF(100f, 20f, 520f, 440f), dualTextBlocks(aligned, 800, 1000).single().rect)
    }

    @Test
    fun `a wide row cannot skip its nearer continuation to join another column`() {
        val rows = listOf(
            RectF(100f, 20f, 500f, 120f),
            RectF(100f, 125f, 300f, 195f),
            RectF(300f, 198f, 500f, 298f)
        )
        val expected = setOf(RectF(100f, 20f, 500f, 195f), rows.last())
        for (order in listOf(rows, rows.reversed(), listOf(rows[1], rows[0], rows[2]))) {
            assertEquals(expected, dualTextBlocks(order, 1000, 1400).map { it.rect }.toSet())
        }
    }

    @Test
    fun `short edge geometry is invariant to scaling translation and longer canvas edges`() {
        val rows = listOf(
            RectF(20f, 20f, 420f, 40f), RectF(200f, 52f, 240f, 72f),
            RectF(20f, 110f, 420f, 130f), RectF(20f, 138f, 100f, 158f)
        )
        val expected = listOf(RectF(20f, 20f, 420f, 72f), RectF(20f, 110f, 420f, 158f))
        for ((width, height) in listOf(800 to 1000, 800 to 10000, 1000 to 800, 10000 to 800)) {
            for (scale in listOf(0.25f, 1f, 3f)) {
                for (offset in listOf(0f, 100f)) {
                    fun transform(rect: RectF) = RectF(
                        (rect.left + offset) * scale, (rect.top + offset) * scale,
                        (rect.right + offset) * scale, (rect.bottom + offset) * scale
                    )
                    val actual = dualTextBlocks(rows.map(::transform),
                        (width * scale).toInt(), (height * scale).toInt())
                    assertEquals(expected.map(::transform), actual.map { it.rect })
                    assertTrue(actual.all { it.lines.isEmpty() && it.orientation == TextLineOrientation.AMBIGUOUS })
                }
            }
        }
    }

    @Test
    fun `proximity alone does not join different fonts offsets or punctuation`() {
        val first = RectF(20f, 20f, 220f, 40f)
        for (other in listOf(
            RectF(20f, 45f, 220f, 85f), // Different text height.
            RectF(55f, 50f, 235f, 70f), // Overlapping X ranges without alignment.
            RectF(105f, 50f, 125f, 70f), // Punctuation-sized square.
            RectF(20f, 60f, 220f, 80f), // Excessive line gap.
            RectF(230f, 20f, 430f, 40f) // Separate text on the same row.
        )) {
            assertEquals(listOf(first, other), dualTextBlocks(listOf(first, other), 500, 200).map { it.rect })
        }
        val columns = listOf(RectF(20f, 20f, 40f, 120f), RectF(50f, 20f, 70f, 120f))
        assertEquals(columns, dualTextBlocks(columns, 200, 200).map { it.rect })
    }
}
