package com.manga.translate

import android.graphics.RectF
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.deriveStatus
import com.manga.translate.reader.projectPreviousSpillBubbles
import com.manga.translate.translation.CrossPageBubbleMerger
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import com.manga.translate.storage.OcrStore
import com.manga.translate.translation.withRecognizedTextBubblesOnly
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationModelsTest {
    @Test
    fun `manual bubbles keep free rendering but do not use free shrink`() {
        assertTrue(BubbleSource.MANUAL.isFreeBubble)
        assertFalse(BubbleSource.MANUAL.usesFreeBubbleShrink)
        assertTrue(BubbleSource.TEXT_DETECTOR.usesFreeBubbleShrink)
    }

    @Test
    fun `ocr page drops bubbles without recognized text`() {
        val page = PageOcrResult(
            imageFile = File("page.jpg"),
            width = 1000,
            height = 7000,
            bubbles = listOf(
                OcrBubble(0, rect(0), "あ", BubbleSource.BUBBLE_DETECTOR),
                OcrBubble(1, rect(1), "", BubbleSource.BUBBLE_DETECTOR),
                OcrBubble(2, rect(2), "  ", BubbleSource.TEXT_DETECTOR)
            )
        )

        val filtered = page.withRecognizedTextBubblesOnly()

        assertEquals(listOf(0), filtered.bubbles.map { it.id })
    }

    @Test
    fun `standard status ignores bubbles without ocr text`() {
        val result = TranslationResult(
            imageName = "page.jpg",
            width = 1000,
            height = 1600,
            bubbles = buildList {
                repeat(43) { index ->
                    add(
                        BubbleTranslation.translated(
                            id = index,
                            rect = rect(index),
                            originalText = "source $index",
                            translatedText = "translated $index"
                        )
                    )
                }
                add(BubbleTranslation.pending(43, rect(43), originalText = ""))
                add(BubbleTranslation.pending(44, rect(44), originalText = ""))
            },
            metadata = TranslationMetadata(mode = TranslationMetadata.MODE_STANDARD)
        )

        assertEquals(PageTranslationStatus.SUCCESS, result.deriveStatus())
    }

    @Test
    fun `vl status still requires every detected bubble to translate`() {
        val result = TranslationResult(
            imageName = "page.jpg",
            width = 1000,
            height = 1600,
            bubbles = listOf(
                BubbleTranslation.translated(0, rect(0), translatedText = "translated"),
                BubbleTranslation.pending(1, rect(1), originalText = "")
            ),
            metadata = TranslationMetadata(mode = TranslationMetadata.MODE_VL_DIRECT)
        )

        assertEquals(PageTranslationStatus.PARTIAL, result.deriveStatus())
    }

    @Test
    fun `cross page merge returns detached list for single page input`() {
        val pages = mutableListOf(
            PageOcrResult(
                imageFile = File("page.jpg"),
                width = 1000,
                height = 1600,
                bubbles = listOf(
                    OcrBubble(0, rect(0), "hello", BubbleSource.BUBBLE_DETECTOR)
                )
            )
        )

        val merged = CrossPageBubbleMerger.merge(pages)

        assertNotSame(pages, merged)
        pages.clear()
        pages.addAll(merged)

        assertEquals(1, pages.size)
        assertEquals(1, pages.first().bubbles.size)
        assertEquals("hello", pages.first().bubbles.first().text)
    }

    @Test
    fun `cross page merge scales next page coordinates without mutating OCR cache`() {
        val firstImage = File.createTempFile("cross-page-first", ".jpg")
        val secondImage = File.createTempFile("cross-page-second", ".jpg")
        try {
            val merged = CrossPageBubbleMerger.merge(
                pages = listOf(
                    PageOcrResult(
                        imageFile = firstImage,
                        width = 1000,
                        height = 1600,
                        bubbles = listOf(
                            OcrBubble(
                                0,
                                RectF(100f, 1500f, 300f, 1700f),
                                "first",
                                BubbleSource.BUBBLE_DETECTOR
                            )
                        )
                    ),
                    PageOcrResult(
                        imageFile = secondImage,
                        width = 500,
                        height = 1600,
                        bubbles = listOf(
                            OcrBubble(
                                0,
                                RectF(50f, -100f, 150f, 150f),
                                "second",
                                BubbleSource.BUBBLE_DETECTOR
                            )
                        )
                    )
                )
            )

            val mergedBubble = merged.first().bubbles.single()
            assertEquals(100f, mergedBubble.rect.left)
            assertEquals(300f, mergedBubble.rect.right)
            assertEquals(1900f, mergedBubble.rect.bottom)
            assertEquals(null, OcrStore().load(firstImage))
            assertEquals(null, OcrStore().load(secondImage))
        } finally {
            firstImage.delete()
            secondImage.delete()
        }
    }

    @Test
    fun `webtoon projects detector bubbles that spill into the next page`() {
        val previous = TranslationResult(
            imageName = "first.jpg",
            width = 1000,
            height = 1600,
            bubbles = listOf(
                BubbleTranslation.translated(
                    id = 0,
                    rect = RectF(100f, 1500f, 300f, 1900f),
                    originalText = "source",
                    translatedText = "translated text",
                    source = BubbleSource.BUBBLE_DETECTOR
                ),
                BubbleTranslation.translated(
                    id = 1,
                    rect = RectF(400f, 1400f, 600f, 1800f),
                    translatedText = "free text",
                    source = BubbleSource.TEXT_DETECTOR
                )
            )
        )

        val projected = projectPreviousSpillBubbles(
            previous = previous,
            previousImageName = "first.jpg",
            currentWidth = 500,
            currentHeight = 1200
        )

        assertEquals(listOf(BubbleSource.BUBBLE_DETECTOR, BubbleSource.TEXT_DETECTOR), projected.map { it.source })
        assertEquals("translated text", projected.first().translatedText)
        assertEquals("first.jpg", projected.first().ownerImageName)
        assertEquals(50f, projected.first().rect.left)
        assertEquals(-50f, projected.first().rect.top)
        assertEquals(150f, projected.first().rect.right)
        assertEquals(150f, projected.first().rect.bottom)
    }

    @Test
    fun `webtoon spill projection excludes bubbles outside the current continuation`() {
        val previous = TranslationResult(
            imageName = "first.jpg",
            width = 1000,
            height = 1600,
            bubbles = listOf(
                BubbleTranslation.translated(
                    id = 0,
                    rect = RectF(100f, 1200f, 300f, 1500f),
                    translatedText = "inside previous page",
                    source = BubbleSource.BUBBLE_DETECTOR
                ),
                BubbleTranslation.translated(
                    id = 1,
                    rect = RectF(100f, 4000f, 300f, 4300f),
                    translatedText = "beyond current page",
                    source = BubbleSource.TEXT_DETECTOR
                ),
                BubbleTranslation.translated(
                    id = 2,
                    rect = RectF(100f, 1500f, 300f, 1800f),
                    translatedText = "belongs elsewhere",
                    source = BubbleSource.MANUAL,
                    ownerImageName = "other.jpg"
                )
            )
        )

        val projected = projectPreviousSpillBubbles(
            previous = previous,
            previousImageName = "first.jpg",
            currentWidth = 1000,
            currentHeight = 1200
        )

        assertEquals(emptyList<BubbleTranslation>(), projected)
    }

    private fun rect(index: Int): RectF {
        val top = index * 10f
        return RectF(0f, top, 100f, top + 8f)
    }

    @Test
    fun `cross page merged bubble geometry is detected`() {
        val result = TranslationResult(
            imageName = "page.jpg",
            width = 1000,
            height = 1600,
            bubbles = listOf(
                BubbleTranslation.translated(0, RectF(0f, 1500f, 100f, 1750f), translatedText = "x")
            ),
            metadata = TranslationMetadata(mode = TranslationMetadata.MODE_STANDARD)
        )

        assertTrue(result.hasCrossPageBubbleGeometry())
    }

    @Test
    fun `in page bubbles are not treated as cross page geometry`() {
        val result = TranslationResult(
            imageName = "page.jpg",
            width = 1000,
            height = 1600,
            bubbles = listOf(
                BubbleTranslation.translated(0, RectF(0f, 1400f, 100f, 1600f), translatedText = "x"),
                BubbleTranslation.translated(1, RectF(0f, 10f, 100f, 60f), translatedText = "y")
            ),
            metadata = TranslationMetadata(mode = TranslationMetadata.MODE_STANDARD)
        )

        assertFalse(result.hasCrossPageBubbleGeometry())
    }
}
