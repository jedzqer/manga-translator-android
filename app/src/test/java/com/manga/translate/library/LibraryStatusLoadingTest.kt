package com.manga.translate.library

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.model.TranslationResult
import com.manga.translate.storage.TranslationStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryStatusLoadingTest {
    @Test fun `preview is immediate and persisted hints are rechecked after edits and deletion`() = runBlocking {
        withLoader { folder, loader, store ->
            val image = File(folder, "1.jpg").apply { writeText("image") }
            val result = TranslationResult(image.name, 10, 10, emptyList(),
                TranslationMetadata(mode = TranslationMetadata.MODE_STANDARD))
            store.save(image, result)
            val first = loader.loadContent(folder) as FolderContent.Images
            assertFalse(first.items.single().statusLoaded)
            var refreshed = first
            loader.refreshContent(folder, first) { refreshed = it as FolderContent.Images }
            assertTrue(refreshed.items.single().translated)
            val cached = loader.loadContent(folder) as FolderContent.Images
            assertTrue(cached.items.single().statusLoaded)
            assertTrue(cached.items.single().translated)
            // Display cache never prevents the actual result from being read.
            store.save(image, result.copy(bubbles = listOf(BubbleTranslation.pending(1, RectF(), "pending"))))
            assertNotNull(store.load(image))
            loader.refreshContent(folder, cached) { refreshed = it as FolderContent.Images }
            assertFalse(refreshed.items.single().translated)
            store.save(image, result)
            loader.refreshContent(folder, refreshed) { refreshed = it as FolderContent.Images }
            assertTrue(refreshed.items.single().translated)
            store.translationFileFor(image).delete()
            loader.refreshContent(folder, refreshed) { refreshed = it as FolderContent.Images }
            assertFalse(refreshed.items.single().translated)
        }
    }

    @Test fun `large directory publishes immutable progressive snapshots and preserves legacy results`() = runBlocking {
        withLoader { folder, loader, store ->
            repeat(300) { index ->
                val image = File(folder, "$index.jpg").apply { writeText("image") }
                store.translationFileFor(image).writeText("""{"bubbles":[{"text":"legacy"}]}""")
            }
            val content = loader.loadContent(folder) as FolderContent.Images
            val snapshots = mutableListOf<FolderContent.Images>()
            loader.refreshContent(folder, content) { snapshots += it as FolderContent.Images }
            assertEquals(listOf(64, 256, 300), snapshots.map { it.items.count { page -> page.statusLoaded } })
            assertTrue(snapshots.last().items.all { it.translated })
            assertEquals(300, folder.listFiles()!!.count { it.extension == "json" })
        }
    }

    @Test fun `collection loads chapter hints without checking translation files`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LibraryRepository(context)
        val folder = repository.createCollection("status-test-${System.nanoTime()}")!!
        val preferences = LibraryPreferencesGateway(context, context.getSharedPreferences("status-test", 0), repository)
        val loader = LibraryPageLoader(repository, preferences, TranslationStore())
        try {
            val chapter = repository.createChildFolder(folder, "chapter")!!
            File(chapter, "1.jpg").writeText("image")
            File(chapter, "1.json").writeText("""{"metadata":{"status":"success"}}""")
            val content = loader.loadContent(folder) as FolderContent.Chapters
            assertFalse(content.items.single().statusLoaded)
            var refreshed = content
            loader.refreshContent(folder, content) { refreshed = it as FolderContent.Chapters }
            assertEquals(1, refreshed.items.single().imageCount)
            assertFalse(refreshed.items.single().statusLoaded)
        } finally { folder.deleteRecursively() }
    }

    private suspend fun withLoader(block: suspend (File, LibraryPageLoader, TranslationStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = kotlin.io.path.createTempDirectory("library-status").toFile()
        val folder = File(root, "manga").apply { mkdirs() }
        val repository = LibraryRepository(context)
        val preferences = LibraryPreferencesGateway(context, context.getSharedPreferences(root.name, 0), repository)
        val store = TranslationStore(File(root, "cache"))
        try { block(folder, LibraryPageLoader(repository, preferences, store), store) }
        finally { root.deleteRecursively() }
    }
}
