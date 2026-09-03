package com.manga.translate

import com.manga.translate.storage.PageProgressStatus
import com.manga.translate.storage.TranslationProgressStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the throttled write path: page updates must stay in memory between
 * flush windows, and the on-disk file must still be a complete, readable
 * snapshot whenever it is written.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationProgressStoreThrottleTest {
    private lateinit var folder: File
    // Start well past epoch so the first write behaves like it does with a real clock.
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        folder = File.createTempFile("progress-folder", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        now = 1_700_000_000_000L
    }

    private fun newStore(throttleMillis: Long = 2_000L) =
        TranslationProgressStore(throttleMillis = throttleMillis, clock = { now })

    @Test
    fun `updates within the throttle window write the file once`() = runBlocking {
        val store = newStore()
        val file = store.fileFor(folder)

        repeat(50) { index ->
            store.update(folder, "page$index.jpg", PageProgressStatus.SAVED)
            now += 10L
        }

        // Only the first update falls outside the initial throttle window.
        val writtenAfterBurst = readPageNames(file)
        assertEquals(setOf("page0.jpg"), writtenAfterBurst)

        // In-memory state still reflects every update.
        assertEquals(50, store.load(folder).size)

        store.flush(folder)
        assertEquals(50, readPageNames(file).size)
    }

    @Test
    fun `passing the throttle window writes the full snapshot`() = runBlocking {
        val store = newStore(throttleMillis = 100L)
        val file = store.fileFor(folder)

        store.update(folder, "a.jpg", PageProgressStatus.OCR_DONE)
        now += 500L
        store.update(folder, "b.jpg", PageProgressStatus.SAVED)

        assertEquals(setOf("a.jpg", "b.jpg"), readPageNames(file))
    }

    @Test
    fun `flush persists failure details for resume`() = runBlocking {
        val store = newStore()
        store.update(folder, "broken.jpg", PageProgressStatus.FAILED, "http 429")
        store.update(folder, "ok.jpg", PageProgressStatus.SAVED)
        store.flush(folder)

        val reloaded = newStore().load(folder)
        assertEquals(PageProgressStatus.FAILED, reloaded["broken.jpg"]?.status)
        assertEquals("http 429", reloaded["broken.jpg"]?.lastError)
        assertEquals(PageProgressStatus.SAVED, reloaded["ok.jpg"]?.status)
    }

    @Test
    fun `load reads existing file written by a previous run`() = runBlocking {
        val first = newStore()
        first.update(folder, "page1.jpg", PageProgressStatus.SKIPPED)
        first.flush(folder)

        val second = newStore()
        assertEquals(PageProgressStatus.SKIPPED, second.load(folder)["page1.jpg"]?.status)
    }

    @Test
    fun `clear drops the file and the in-memory snapshot`() = runBlocking {
        val store = newStore()
        store.update(folder, "page1.jpg", PageProgressStatus.SAVED)
        store.flush(folder)
        assertTrue(store.fileFor(folder).exists())

        store.clear(folder)

        assertFalse(store.fileFor(folder).exists())
        assertTrue(store.load(folder).isEmpty())

        // A flush after clear must not resurrect the deleted file.
        store.flush(folder)
        assertFalse(store.fileFor(folder).exists())
    }

    @Test
    fun `flushAll lands every touched folder`() = runBlocking {
        val store = newStore()
        val other = File(folder.parentFile, "${folder.name}-other").apply { mkdirs() }

        store.update(folder, "page1.jpg", PageProgressStatus.SAVED)
        store.update(other, "page1.jpg", PageProgressStatus.SAVED)
        store.flushAll()

        assertEquals(setOf("page1.jpg"), readPageNames(store.fileFor(folder)))
        assertEquals(setOf("page1.jpg"), readPageNames(store.fileFor(other)))
    }

    @Test
    fun `flushAll releases snapshots so later loads see external changes`() = runBlocking {
        val store = newStore()
        store.update(folder, "page1.jpg", PageProgressStatus.SAVED)
        store.flushAll()
        assertEquals(1, store.load(folder).size)

        // Simulate a restore or manual cleanup replacing the file behind our back.
        store.fileFor(folder).delete()

        assertTrue(store.load(folder).isEmpty())
    }

    private fun readPageNames(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        val pages = org.json.JSONObject(file.readText()).optJSONObject("pages")
            ?: return emptySet()
        return pages.keys().asSequence().toSet()
    }
}
