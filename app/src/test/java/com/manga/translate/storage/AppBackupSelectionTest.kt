package com.manga.translate.storage

import android.content.Context
import android.net.Uri
import com.manga.translate.settings.SettingsStore
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppBackupSelectionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = AppBackupManager(context)
    private val library get() = File(context.getExternalFilesDir(null), "manga_library")
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun seed() {
        File(library, "chapter").mkdirs()
        File(library, "chapter/page.jpg").writeText("image")
        File(library, "chapter/page.json").writeText("saved translation")
        File(library, "chapter/page.ocr.json").writeText("ocr")
        File(library, "chapter/glossary.json").writeText("glossary")
        prefs("library_prefs").edit().putString("folder", "library setting").commit()
        prefs(ReadingProgressStore.PREFS_NAME).edit().putInt("chapter", 7).commit()
        prefs(SettingsStore.PREFS_NAME).edit().putString("test_setting", "saved setting").commit()
        File(context.filesDir, SettingsStore.AI_PROVIDER_PROFILES_FILE_NAME).writeText("profiles")
        File(context.filesDir, "custom_fonts").mkdirs()
        File(context.filesDir, "custom_fonts/font.ttf").writeText("font")
    }

    @Test
    fun allNonemptySelectionsExportOnlyTheirCategoriesAndRestoreIndependently() = runBlocking {
        listOf(
            AppBackupManager.ExportOptions(true, false),
            AppBackupManager.ExportOptions(false, true),
            AppBackupManager.ExportOptions()
        ).forEachIndexed { index, options ->
            seed()
            val archive = File(context.cacheDir, "selection-$index.zip")
            archive.delete()
            manager.exportTo(Uri.fromFile(archive), options)
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(names.containsAll(listOf("backup_manifest.json", "backup_mtimes.json")))
                assertEquals(options.includeManga, "manga_library/chapter/page.json" in names)
                assertEquals(options.includeManga, "preferences/library_prefs.json" in names)
                assertEquals(options.includeManga, "preferences/reading_progress.json" in names)
                assertEquals(options.includeSettings, "preferences/${SettingsStore.PREFS_NAME}.json" in names)
                assertEquals(options.includeSettings, "files/${SettingsStore.AI_PROVIDER_PROFILES_FILE_NAME}" in names)
                assertEquals(options.includeSettings, "files/custom_fonts/font.ttf" in names)
            }
            library.deleteRecursively()
            prefs("library_prefs").edit().clear().putString("untouched", "library").commit()
            prefs(ReadingProgressStore.PREFS_NAME).edit().clear().putInt("chapter", 2).commit()
            prefs(SettingsStore.PREFS_NAME).edit().putString("test_setting", "current setting").commit()
            manager.importFrom(Uri.fromFile(archive))
            assertEquals(options.includeManga, File(library, "chapter/page.json").exists())
            assertEquals(if (options.includeManga) 7 else 2,
                prefs(ReadingProgressStore.PREFS_NAME).getInt("chapter", 0))
            assertEquals(if (options.includeSettings) "saved setting" else "current setting",
                prefs(SettingsStore.PREFS_NAME).getString("test_setting", null))
            if (options.includeManga) {
                File(library, "chapter/page.json").writeText("new user translation")
                manager.importFrom(Uri.fromFile(archive))
                assertEquals("new user translation", File(library, "chapter/page.json").readText())
            } else {
                assertEquals("library", prefs("library_prefs").getString("untouched", null))
            }
        }
    }

    @Test
    fun legacyV2BackupWithoutReadingProgressStillImports() = runBlocking {
        seed()
        val full = File(context.cacheDir, "full-source.zip")
        manager.exportTo(Uri.fromFile(full))
        val legacy = File(context.cacheDir, "legacy-v2.zip")
        ZipFile(full).use { source ->
            ZipOutputStream(legacy.outputStream()).use { target ->
                source.entries().asSequence()
                    .filter { it.name != "preferences/reading_progress.json" }
                    .forEach { entry ->
                        target.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).use { it.copyTo(target) }
                        target.closeEntry()
                    }
            }
        }
        library.deleteRecursively()
        prefs(ReadingProgressStore.PREFS_NAME).edit().putInt("chapter", 3).commit()
        manager.importFrom(Uri.fromFile(legacy))
        assertEquals("saved translation", File(library, "chapter/page.json").readText())
        assertEquals(3, prefs(ReadingProgressStore.PREFS_NAME).getInt("chapter", 0))
    }

    @Test
    fun emptySelectionDoesNotTouchDestination() = runBlocking {
        val archive = File(context.cacheDir, "empty-selection.zip").apply { writeText("existing") }
        val failure = runCatching {
            manager.exportTo(Uri.fromFile(archive), AppBackupManager.ExportOptions(false, false))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("existing", archive.readText())
    }
}
