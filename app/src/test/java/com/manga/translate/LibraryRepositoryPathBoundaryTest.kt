package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.library.LibraryRepository
import com.manga.translate.platform.ImageFileSupport
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryPathBoundaryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = LibraryRepository(context)
    private val createdFolders = mutableListOf<File>()

    @After
    fun tearDown() {
        createdFolders.forEach { repository.deleteFolder(it) }
    }

    @Test
    fun `rejects unsafe DocumentProvider display names`() {
        listOf(
            "../page.jpg",
            "folder/page.jpg",
            "folder\\page.jpg",
            ".",
            "..",
            "page\u0000.jpg"
        ).forEach { name ->
            assertNull(LibraryRepository.sanitizeImportedImageFileName(name))
        }

        assertTrue(LibraryRepository.sanitizeImportedImageFileName("page 01.jpg") != null)
    }

    @Test
    fun `accepts AVIF imports and resolves PNG output names`() {
        assertEquals("page 01.avif", LibraryRepository.extractImportImageName("pages/page 01.avif"))
        assertEquals("page 01.png", ImageFileSupport.resolveImportedAvifOutputName("page 01.avif"))
        assertEquals("page 01.png", ImageFileSupport.resolveImportedAvifOutputName("page 01"))
        assertTrue(ImageFileSupport.isAvifMimeType("image/avif"))
    }

    @Test
    fun `mutators reject paths outside the library and invalid folder levels`() = runBlocking {
        val collection = createCollection("collection")
        val regularFolder = createFolder("regular")
        val chapter = repository.createChildFolder(collection, "chapter")!!
        val invalidNestedFolder = File(regularFolder, "nested").apply { mkdirs() }
        val outsideFolder = Files.createTempDirectory("outside-library").toFile()
        val escapedFolder = File(
            regularFolder.parentFile!!.parentFile,
            "escaped-library_${System.nanoTime()}"
        ).apply { mkdirs() }
        val pathThatEscapesLibrary = File(regularFolder, "../../${escapedFolder.name}")

        try {
            assertNull(repository.createChildFolder(regularFolder, "not-a-chapter"))
            assertNull(repository.createChildFolder(outsideFolder, "chapter"))
            assertNull(repository.createChildFolder(pathThatEscapesLibrary, "chapter"))
            assertTrue(repository.addImages(outsideFolder, emptyList()).isEmpty())
            assertTrue(repository.addImages(invalidNestedFolder, emptyList()).isEmpty())

            assertFalse(repository.deleteFolder(outsideFolder))
            assertFalse(repository.deleteFolder(pathThatEscapesLibrary))
            assertNull(repository.renameFolder(outsideFolder, "renamed"))
            assertNull(repository.renameFolder(pathThatEscapesLibrary, "renamed"))
            assertNull(repository.renameFolder(invalidNestedFolder, "renamed"))
            assertNull(repository.moveFolderToCollection(outsideFolder, collection))
            assertNull(repository.moveFolderToCollection(pathThatEscapesLibrary, collection))
            assertNull(repository.moveFolderToCollection(regularFolder, outsideFolder))
            assertNull(repository.moveFolderToCollection(chapter, collection))

            assertTrue(outsideFolder.exists())
            assertTrue(escapedFolder.exists())
            assertTrue(invalidNestedFolder.exists())
        } finally {
            outsideFolder.deleteRecursively()
            escapedFolder.deleteRecursively()
        }
    }

    @Test
    fun `moving an imported folder into a collection can resolve a duplicate name`() {
        val collection = createCollection("collection")
        val existingChapter = repository.createChildFolder(collection, "chapter")!!
        val imported = repository.createFolder("chapter")!!.also(createdFolders::add)

        val moved = repository.moveFolderToCollection(
            folder = imported,
            collection = collection,
            uniqueTarget = true
        )

        assertEquals("chapter_1", moved?.name)
        assertFalse(imported.exists())
        assertTrue(existingChapter.exists())
        assertTrue(moved?.exists() == true)
    }

    private fun createFolder(prefix: String): File {
        return repository.createFolder("${prefix}_${System.nanoTime()}")!!.also(createdFolders::add)
    }

    private fun createCollection(prefix: String): File {
        return repository.createCollection("${prefix}_${System.nanoTime()}")!!.also(createdFolders::add)
    }
}
