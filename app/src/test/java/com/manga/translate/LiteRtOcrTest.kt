package com.manga.translate

import com.manga.translate.ocr.decodeLiteRtCtc
import com.manga.translate.ocr.recognitionInput
import com.manga.translate.ocr.resolveLiteRtOcrInputSize
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtOcrTest {
    @Test fun `width buckets cover boundaries and preserve complete long lines`() {
        for ((natural, expected) in listOf(1 to 320, 320 to 320, 321 to 640,
            640 to 640, 641 to 960, 960 to 960, 2000 to 960)) {
            val size = resolveLiteRtOcrInputSize(natural, 48)
            assertEquals(expected, size.width)
            assertEquals(minOf(natural, 960), size.contentWidth)
        }
        assertEquals(321, resolveLiteRtOcrInputSize(667, 100).contentWidth)
    }

    @Test fun `recognition input uses BGR planes and normalized zero padding`() {
        val values = recognitionInput(IntArray(48) { 0xffff0000.toInt() }, 1, 320)
        val plane = 48 * 320
        for (y in 0 until 48) {
            assertEquals(-1f, values[y * 320], 0f)
            assertEquals(-1f, values[plane + y * 320], 0f)
            assertEquals(1f, values[2 * plane + y * 320], 0f)
            assertEquals(0f, values[y * 320 + 1], 0f)
        }
    }

    @Test fun `CTC preserves spaces merges repeats and resets at blank`() {
        val chars = listOf("blank", "字", " ")
        val ids = listOf(2, 1, 1, 0, 1, 2)
        val probabilities = FloatArray(ids.size * chars.size)
        ids.forEachIndexed { time, id -> probabilities[time * chars.size + id] = 0.8f }
        val result = decodeLiteRtCtc(probabilities, chars)
        assertEquals(" 字字 ", result.text)
        assertEquals(0.8f, result.score, 0.0001f)
        assertEquals("", decodeLiteRtCtc(floatArrayOf(1f, 0f, 0f), chars).text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CTC rejects malformed output`() {
        decodeLiteRtCtc(floatArrayOf(1f), listOf("blank", "x"))
    }
}
