package com.manga.translate

import com.manga.translate.storage.GlossaryStore
import com.manga.translate.translation.GlossaryWriteCoalescer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The coalescer only changes glossary write frequency; the persisted content must
 * stay exactly what [GlossaryStore] would have written for the newest snapshot.
 */
@RunWith(RobolectricTestRunner::class)
class GlossaryWriteCoalescerTest {
    private lateinit var folder: File
    private lateinit var glossaryStore: GlossaryStore
    // Start well past epoch so the first write behaves like it does with a real clock.
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        folder = File.createTempFile("glossary-folder", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        glossaryStore = GlossaryStore()
        now = 1_700_000_000_000L
    }

    private fun newCoalescer(throttleMillis: Long = 3_000L) = GlossaryWriteCoalescer(
        glossaryStore = glossaryStore,
        targetKeyProvider = { "zh_hans" },
        throttleMillis = throttleMillis,
        clock = { now }
    )

    @Test
    fun `submits within the throttle window keep only the newest snapshot pending`() = runBlocking {
        val coalescer = newCoalescer()

        coalescer.submit(folder, mapOf("A" to "甲"))
        now += 10L
        coalescer.submit(folder, mapOf("A" to "甲", "B" to "乙"))
        now += 10L
        coalescer.submit(folder, mapOf("A" to "甲", "B" to "乙", "C" to "丙"))

        // The first submit wrote through; later ones are still pending.
        assertEquals(mapOf("A" to "甲"), glossaryStore.load(folder, "zh_hans"))

        coalescer.flush(folder)
        assertEquals(
            mapOf("A" to "甲", "B" to "乙", "C" to "丙"),
            glossaryStore.load(folder, "zh_hans")
        )
    }

    @Test
    fun `passing the throttle window writes through`() = runBlocking {
        val coalescer = newCoalescer(throttleMillis = 100L)

        coalescer.submit(folder, mapOf("A" to "甲"))
        now += 500L
        coalescer.submit(folder, mapOf("A" to "甲", "B" to "乙"))

        assertEquals(mapOf("A" to "甲", "B" to "乙"), glossaryStore.load(folder, "zh_hans"))
    }

    @Test
    fun `writeNow supersedes a pending snapshot`() = runBlocking {
        val coalescer = newCoalescer()

        coalescer.submit(folder, mapOf("A" to "甲"))
        now += 10L
        coalescer.submit(folder, mapOf("A" to "甲", "B" to "乙"))
        coalescer.writeNow(folder, mapOf("A" to "甲", "B" to "乙", "C" to "丙"))

        assertEquals(
            mapOf("A" to "甲", "B" to "乙", "C" to "丙"),
            glossaryStore.load(folder, "zh_hans")
        )

        // Nothing is left pending, so a later flush is a no-op.
        coalescer.flush(folder)
        assertEquals(
            mapOf("A" to "甲", "B" to "乙", "C" to "丙"),
            glossaryStore.load(folder, "zh_hans")
        )
    }

    @Test
    fun `flushAll covers every folder touched by a batch task`() = runBlocking {
        val coalescer = newCoalescer()
        val other = File(folder.parentFile, "${folder.name}-other").apply { mkdirs() }

        coalescer.submit(folder, mapOf("A" to "甲"))
        coalescer.submit(other, mapOf("B" to "乙"))
        now += 10L
        coalescer.submit(folder, mapOf("A" to "甲", "C" to "丙"))
        coalescer.flushAll()

        assertEquals(mapOf("A" to "甲", "C" to "丙"), glossaryStore.load(folder, "zh_hans"))
        assertEquals(mapOf("B" to "乙"), glossaryStore.load(other, "zh_hans"))
    }

    @Test
    fun `target key selects the scoped glossary file`() = runBlocking {
        var targetKey = "zh_hans"
        val coalescer = GlossaryWriteCoalescer(
            glossaryStore = glossaryStore,
            targetKeyProvider = { targetKey },
            throttleMillis = 0L,
            clock = { now }
        )

        coalescer.writeNow(folder, mapOf("A" to "甲"))
        targetKey = "en"
        coalescer.writeNow(folder, mapOf("A" to "alpha"))

        assertEquals(mapOf("A" to "甲"), glossaryStore.load(folder, "zh_hans"))
        assertEquals(mapOf("A" to "alpha"), glossaryStore.load(folder, "en"))
    }
}
