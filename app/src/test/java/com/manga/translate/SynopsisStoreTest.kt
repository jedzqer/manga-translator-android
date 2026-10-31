package com.manga.translate

import com.manga.translate.storage.SynopsisStore
import com.manga.translate.translation.FullTranslationSynopsis
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SynopsisStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `continuation loads saved synopsis and preserves old glossary and other languages`() {
        val folder = temp.newFolder()
        val glossary = File(folder, "glossary.json").apply { writeText("{\"name\":\"名字\"}") }
        val store = SynopsisStore()
        assertEquals("", store.load(folder))
        val first = FullTranslationSynopsis(store.load(folder)) { store.save(folder, it) }
        first.commit(listOf("p1"), "剧情".repeat(500))
        val resumed = FullTranslationSynopsis(SynopsisStore().load(folder)) { store.save(folder, it) }
        assertEquals(first.text, resumed.text)
        resumed.commit(listOf("p2"), "继续剧情")
        resumed.commit(listOf("p3"), "")
        assertEquals("继续剧情", SynopsisStore().load(folder))
        store.save(folder, "English plot", "en")
        assertEquals("English plot", store.load(folder, "en"))
        assertEquals("继续剧情", store.load(folder))
        assertEquals("{\"name\":\"名字\"}", glossary.readText())
    }
}
