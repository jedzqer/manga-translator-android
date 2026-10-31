package com.manga.translate

import android.graphics.RectF
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import com.manga.translate.translation.CrossPageBubbleMerger
import com.manga.translate.translation.TRANSLATION_WINDOW_PAGES
import com.manga.translate.translation.forEachPreparedPageWindow
import com.manga.translate.translation.glossaryPageUnits
import com.manga.translate.translation.mergeOcrWindow
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationWindowTest {
    @Test
    fun `ten thousand pages finish each window before preparing more and visit each page once`() = runBlocking {
        for (lookAhead in listOf(false, true)) {
            val files = List(10_000) { File("$it.jpg") }
            val preparedFiles = mutableListOf<File>()
            val processedFiles = mutableListOf<File?>()
            var windows = 0
            forEachPreparedPageWindow(
                pages = files,
                lookAhead = lookAhead,
                prepare = { window, offset ->
                    assertEquals(preparedFiles.size, offset)
                    assertEquals(windows * TRANSLATION_WINDOW_PAGES, processedFiles.size)
                    preparedFiles.addAll(window)
                    assertTrue(preparedFiles.size - processedFiles.size <= TRANSLATION_WINDOW_PAGES + if (lookAhead) 1 else 0)
                    window
                },
                process = { window, offset ->
                    assertEquals(processedFiles.size, offset)
                    assertTrue(window.size <= TRANSLATION_WINDOW_PAGES)
                    // Simulate a suspending extraction/translation/save step.
                    yield()
                    processedFiles.addAll(window)
                    windows++
                }
            )
            assertEquals(files, preparedFiles)
            assertEquals(files, processedFiles)
        assertEquals(100, windows)
        }
    }

    @Test
    fun `short final windows and failed lookahead preserve ordering without retries`() = runBlocking {
        for (count in listOf(1, 49, 50, 51, 99, 100, 101)) {
            val files = List(count) { File("$it.jpg") }
            val expected = files.mapIndexed { index, file -> if (index % 50 == 0) null else file }
            val processed = mutableListOf<File?>()
            val preparations = mutableListOf<File>()
            forEachPreparedPageWindow(
                pages = files,
                lookAhead = true,
                prepare = { window, offset ->
                    preparations.addAll(window)
                    expected.subList(offset, offset + window.size)
                },
                process = { window, offset ->
                    assertEquals(processed.size, offset)
                    processed.addAll(window)
                }
            )
            assertEquals(files, preparations)
            assertEquals(expected, processed)
        }
    }

    @Test
    fun `webtoon window boundaries match whole sequence merging without mutating raw OCR`() = runBlocking {
        val pages = List(103) { index ->
            page(index, listOf(
                bubble(0, "top $index", RectF(100f, -50f, 300f, 100f)),
                bubble(1, "bottom $index", RectF(100f, 950f, 300f, 1100f))
            ))
        }
        val originals = pages.map { it.bubbles.toList() }
        val positions = pages.mapIndexed { index, page -> page.imageFile to index }.toMap()
        val expected = CrossPageBubbleMerger.merge(pages)
        for (size in listOf(1, 2, TRANSLATION_WINDOW_PAGES)) {
            val actual = mutableListOf<PageOcrResult?>()
            forEachPreparedPageWindow(
                pages = pages.map { it.imageFile },
                windowSize = size,
                lookAhead = true,
                prepare = { files, offset -> pages.subList(offset, offset + files.size) },
                transform = { mergeOcrWindow(it, positions) },
                process = { window, _ -> actual.addAll(window) }
            )
            assertEquals(expected, actual)
            assertEquals("bottom 49\ntop 50", actual[49]!!.bubbles.last().text)
            assertFalse(actual[50]!!.bubbles.any { it.text == "top 50" })
            assertEquals(originals, pages.map { it.bubbles })
        }
    }

    @Test
    fun `failed or cached pages break cross page adjacency`() {
        val previous = page(0, listOf(bubble(0, "previous", RectF(100f, 950f, 300f, 1100f))))
        val missing = page(1, emptyList())
        val next = page(2, listOf(bubble(0, "next", RectF(100f, -50f, 300f, 100f))))
        val positions = listOf(previous, missing, next).mapIndexed { index, page -> page.imageFile to index }.toMap()
        assertEquals(listOf(previous, null, next), mergeOcrWindow(listOf(previous, null, next), positions))
        assertEquals(listOf(previous, next), mergeOcrWindow(listOf(previous, next), positions))
    }

    @Test
    fun `cross page merging never crosses folder boundary`() {
        val previous = page(0, listOf(bubble(0, "previous", RectF(100f, 950f, 300f, 1100f))))
            .copy(imageFile = File("/library/chapter-a/001.png"))
        val next = page(1, listOf(bubble(0, "next", RectF(100f, -50f, 300f, 100f))))
            .copy(imageFile = File("/library/chapter-b/001.png"))
        val positions = mapOf(previous.imageFile to 0, next.imageFile to 1)

        val actual = mergeOcrWindow(listOf(previous, next), positions)

        assertEquals(listOf(previous, next), actual)
    }

    @Test
    fun `ordinary windows never combine page geometry`() = runBlocking {
        val pages = listOf(
            page(0, listOf(bubble(0, "first", RectF(100f, 950f, 300f, 1100f)))),
            page(1, listOf(bubble(0, "second", RectF(100f, -50f, 300f, 100f))))
        )
        val actual = mutableListOf<PageOcrResult?>()
        forEachPreparedPageWindow(
            pages = pages.map { it.imageFile },
            windowSize = 1,
            prepare = { files, offset -> pages.subList(offset, offset + files.size) },
            process = { window, _ -> actual.addAll(window) }
        )
        assertEquals(pages, actual)
    }

    @Test
    fun `glossary units retain entire images and reading order`() {
        val longText = "漢字😀".repeat(10_000)
        val pages = listOf(
            page(0, listOf(
                bubble(1, " last ", RectF(0f, 20f, 20f, 40f)),
                bubble(0, "first", RectF(0f, 0f, 20f, 10f)),
                bubble(2, "   ", RectF(0f, 50f, 20f, 60f))
            )),
            page(1, listOf(bubble(0, longText)))
        )
        val units = glossaryPageUnits(pages)
        assertEquals(2, units.size)
        assertEquals(pages.map { it.imageFile.absolutePath }, units.map { it.sourceId })
        assertEquals("<b>first</b>\n<b>last</b>", units[0].serializedText)
        assertEquals("<b>$longText</b>", units[1].serializedText)
        assertTrue(glossaryPageUnits(listOf(page(0, emptyList()))).isEmpty())
    }

    @Test
    fun `cancellation or request failure never prepares a subsequent window`() {
        for (error in listOf(CancellationException("cancelled"), IllegalStateException("request failed"))) {
            val prepared = mutableListOf<File>()
            val files = List(101) { File("$it.jpg") }
            val thrown = assertThrows(error.javaClass) {
                runBlocking {
                    forEachPreparedPageWindow(
                        pages = files,
                        lookAhead = true,
                        prepare = { window, _ -> prepared.addAll(window); window },
                        process = { _, _ -> throw error }
                    )
                }
            }
            assertSame(error, thrown)
            assertEquals(files.take(TRANSLATION_WINDOW_PAGES + 1), prepared)
        }
    }

    @Test
    fun `empty input performs no work`() = runBlocking {
        forEachPreparedPageWindow<File>(
            pages = emptyList(),
            prepare = { _, _ -> error("unexpected preparation") },
            process = { _, _ -> error("unexpected translation") }
        )
    }

    private fun page(index: Int, bubbles: List<OcrBubble>) = PageOcrResult(
        imageFile = File("$index.jpg"), width = 1000, height = 1000, bubbles = bubbles
    )

    private fun bubble(id: Int, text: String, rect: RectF = RectF(0f, 0f, 20f, 20f)) =
        OcrBubble(id, rect, text)
}
