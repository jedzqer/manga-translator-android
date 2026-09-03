package com.manga.translate.settings.ui

import android.content.Context
import android.net.Uri
import com.manga.translate.platform.AppLogger
import com.manga.translate.rendering.BubbleFontResolver
import com.manga.translate.settings.AiProviderProfilesState
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.AppBackupManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data-facing operations for the settings UI: log-file access, uploaded-font
 * file management and AI provider profile persistence.
 *
 * Keeps file/Store access out of [SettingsFragment] and the dialog classes.
 * The context is supplied lazily through [contextProvider] so the controller
 * can be created once by the Fragment and still always resolve the current
 * (attached) context at call time.
 */
internal class SettingsDataController(
    private val contextProvider: () -> Context,
    private val settingsStore: SettingsStore
) {
    private val backupManager by lazy { AppBackupManager(contextProvider()) }

    suspend fun exportBackup(uri: Uri) = backupManager.exportTo(uri)

    suspend fun importBackup(uri: Uri): AppBackupManager.ImportResult =
        backupManager.importFrom(uri).also { settingsStore.notifyImportedSettings() }

    // Log files -------------------------------------------------------------

    fun readLogs(): String = AppLogger.readLogs()

    fun listLogFiles(): List<File> = AppLogger.listLogFiles()

    fun createErrorLogsArchive(): File? = AppLogger.createErrorLogsArchive(contextProvider())

    // Uploaded fonts --------------------------------------------------------

    fun listUploadedFonts(): List<String> =
        BubbleFontResolver.listUploadedFonts(contextProvider())

    suspend fun importUploadedFont(uri: Uri): String =
        BubbleFontResolver.importUploadedFont(contextProvider(), uri)

    suspend fun deleteUploadedFont(fileName: String): Boolean = withContext(Dispatchers.IO) {
        BubbleFontResolver.deleteUploadedFont(contextProvider(), fileName)
    }

    // AI provider profiles --------------------------------------------------

    fun loadAiProviderProfilesState(): AiProviderProfilesState =
        settingsStore.loadAiProviderProfilesState()

    fun saveCurrentAsAiProviderProfile(name: String): Boolean =
        settingsStore.saveCurrentAsAiProviderProfile(name)

    fun overwriteActiveAiProviderProfile(): Boolean =
        settingsStore.overwriteActiveAiProviderProfile()

    fun applyAiProviderProfile(name: String): Boolean =
        settingsStore.applyAiProviderProfile(name)

    fun deleteAiProviderProfile(name: String): Boolean =
        settingsStore.deleteAiProviderProfile(name)
}
