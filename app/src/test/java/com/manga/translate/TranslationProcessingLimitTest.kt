package com.manga.translate

import com.manga.translate.translation.translationProcessingLimit
import com.manga.translate.translation.forEachPreparedPageWindow
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProcessingLimitTest {
    private val mb = 1024L * 1024L

    @Test fun `automatic limit is conservative and shrinks with heap pressure`() {
        assertEquals(32, translationProcessingLimit(1, 256 * mb, 64 * mb))
        assertEquals(20, translationProcessingLimit(1, 256 * mb, 240 * mb))
        assertEquals(100, translationProcessingLimit(1, 4096 * mb, 0))
        assertEquals(20, translationProcessingLimit(1, 0, 0))
        assertEquals(50, translationProcessingLimit(50, 256 * mb, 240 * mb))
        assertEquals(300, translationProcessingLimit(300, 4096 * mb, 0))
        assertEquals(500, translationProcessingLimit(500, 256 * mb, 240 * mb))
        assertEquals(100, translationProcessingLimit(20, 4096 * mb, 0))
    }

    @Test fun `changing windows preserve order and lookahead for ten thousand pages`() = runBlocking {
        val files = List(10_000) { File("$it.jpg") }
        val prepared = mutableListOf<File>()
        val processed = mutableListOf<File?>()
        var limit = 500
        forEachPreparedPageWindow(
            pages = files,
            lookAhead = true,
            resolveWindowSize = { limit },
            prepare = { window, offset ->
                assertEquals(prepared.size, offset)
                prepared.addAll(window)
                assertTrue(prepared.size - processed.size <= limit + 1)
                window
            },
            process = { window, offset ->
                assertEquals(processed.size, offset)
                assertTrue(window.size <= limit)
                processed.addAll(window)
                limit = if (limit == 500) 20 else 500
            }
        )
        assertEquals(files, prepared)
        assertEquals(files, processed)
    }
}
