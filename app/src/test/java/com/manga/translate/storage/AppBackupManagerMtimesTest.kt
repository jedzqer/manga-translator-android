package com.manga.translate.storage

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppBackupManagerMtimesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val manager = AppBackupManager(RuntimeEnvironment.getApplication())

    // ----- manifest round trip -----

    @Test
    fun manifestRoundTripPreservesPathsAndMillis() {
        val mtimes = linkedMapOf(
            "mangaA/001.jpg" to 1_700_000_000_123L,
            "mangaA/ch2" to 1_700_000_001_000L,
            "mangaA/ch2/p2.png" to 1_700_000_001_234L,
            "glossary.json" to 1L
        )
        val file = temp.newFile("backup_mtimes.json")
        file.writeBytes(manager.mtimesJson(mtimes))

        assertEquals(mtimes, manager.parseMtimesFile(file))
    }

    @Test
    fun parseRejectsUnsafePaths() {
        val values = JSONObject()
            .put("../evil.jpg", 1L)
            .put("/absolute.jpg", 2L)
            .put("ok.jpg", 3L)
        val traversal = temp.newFile("traversal.json")
        traversal.writeText(JSONObject().put("mtimes", values).toString())

        assertThrows(IllegalArgumentException::class.java) { manager.parseMtimesFile(traversal) }
    }

    @Test
    fun parseRejectsManifestWithoutMtimesObject() {
        val file = temp.newFile("no_mtimes.json")
        file.writeText(JSONObject().put("something", "else").toString())

        assertThrows(IllegalArgumentException::class.java) { manager.parseMtimesFile(file) }
    }

    @Test
    fun parseThrowsForMalformedManifest() {
        val file = temp.newFile("broken.json")
        file.writeText("{not json")

        assertThrows(JSONException::class.java) { manager.parseMtimesFile(file) }
    }

    // ----- copyTree tracking -----

    @Test
    fun copyTreeTracksCopiedFilesAndCreatedDirsAndSkipsExisting() {
        val staging = temp.newFolder("staging")
        File(staging, "manga_library/mangaA").mkdirs()
        File(staging, "manga_library/mangaA/001.jpg").writeText("new-image")
        File(staging, "manga_library/mangaA/ch2").mkdirs()
        File(staging, "manga_library/mangaA/ch2/002.jpg").writeText("image2")
        File(staging, "manga_library/glossary.json").writeText("{}")

        val destination = temp.newFolder("library")
        // Pre-existing image: must not be overwritten nor tracked for mtime restore.
        File(destination, "manga_library/mangaA").mkdirs()
        File(destination, "manga_library/mangaA/001.jpg").writeText("existing-image")

        val copiedFiles = mutableListOf<File>()
        val copiedDirs = mutableListOf<File>()
        val count = manager.copyTree(
            File(staging, "manga_library"),
            File(destination, "manga_library"),
            copiedFiles,
            copiedDirs
        )

        assertEquals(2, count)
        assertEquals("existing-image", File(destination, "manga_library/mangaA/001.jpg").readText())
        assertEquals(
            setOf("mangaA/ch2/002.jpg", "glossary.json"),
            copiedFiles.map { it.toRelativeString(File(destination, "manga_library")) }.toSet()
        )
        assertEquals(
            // manga_library and mangaA already existed before the restore; only
            // directories created by this restore are tracked.
            setOf("manga_library/mangaA/ch2"),
            copiedDirs.map { it.toRelativeString(destination) }.toSet()
        )
    }

    // ----- applying timestamps -----

    @Test
    fun applyMtimesRestoresFilesAndThenDirectories() {
        val root = temp.newFolder("root")
        val dir = File(root, "mangaA").apply { mkdirs() }
        val image = File(dir, "001.jpg").apply { writeText("x") }
        // Simulate the copy step bumping the directory timestamp afterwards.
        dir.setLastModified(999_999L)

        val failures = manager.applyMtimes(
            root,
            listOf(image),
            listOf(dir),
            mapOf("mangaA" to 1_000L, "mangaA/001.jpg" to 1_005L)
        )

        assertEquals(0, failures)
        assertEquals(1_005L, image.lastModified())
        assertEquals(1_000L, dir.lastModified())
    }

    @Test
    fun applyMtimesOnlyTouchesEntriesPresentInManifest() {
        val root = temp.newFolder("root")
        val tracked = File(root, "a.jpg").apply { writeText("a") }
        val untracked = File(root, "b.jpg").apply { writeText("b") }
        tracked.setLastModified(1_000L)
        untracked.setLastModified(2_000L)

        val failures = manager.applyMtimes(
            root,
            listOf(tracked, untracked),
            emptyList(),
            mapOf("a.jpg" to 3_000L)
        )

        assertEquals(0, failures)
        assertEquals(3_000L, tracked.lastModified())
        assertEquals(2_000L, untracked.lastModified())
    }

    @Test
    fun applyMtimesIgnoresEmptyManifest() {
        val root = temp.newFolder("root")
        val file = File(root, "a.jpg").apply { writeText("a") }
        file.setLastModified(1_000L)

        val failures = manager.applyMtimes(root, listOf(file), emptyList(), emptyMap())

        assertEquals(0, failures)
        assertEquals(1_000L, file.lastModified())
    }
}
