package com.manga.translate.library

import android.content.Context
import android.net.Uri
import com.manga.translate.platform.ResourceAssessment
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.TranslationStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Export orchestration wrapper around [LibraryExporter]. It owns the exporter
 * instance (which in turn uses [ExportStorageBackend] for the storage layer)
 * and exposes the export-facing surface consumed by
 * [LibraryImportExportCoordinator].
 */
internal class ExportCoordinator(
    context: Context,
    repository: LibraryRepository,
    translationStore: TranslationStore,
    settingsStore: SettingsStore,
    preferencesGateway: LibraryPreferencesGateway,
    ui: LibraryUiCallbacks,
    exportTaskHost: ExportTaskHost
) {
    private val exporter = LibraryExporter(
        context, repository, translationStore, settingsStore,
        preferencesGateway, ui, exportTaskHost
    )

    fun isPendingExportCollection(): Boolean = exporter.pendingExportIsCollection

    fun getExportFormatDefault(): ExportFormat = exporter.getExportFormat()
    fun buildExportRootPathPreview(): String = exporter.buildExportRootPreview()
    fun isExportActiveFor(folder: File): Boolean = exporter.isExportActiveFor(folder)

    suspend fun assessExportResources(
        images: List<File>,
        requestedThreads: Int
    ): ResourceAssessment = withContext(Dispatchers.IO) {
        exporter.assessExportResources(images, requestedThreads)
    }

    suspend fun suggestExportThreadCount(images: List<File>): Int = withContext(Dispatchers.IO) {
        exporter.suggestExportThreads(images)
    }

    fun handleStoragePermissionResult(
        granted: Boolean,
        onGranted: () -> Unit
    ) {
        exporter.handleStoragePermissionResult(granted, onGranted)
    }

    fun handleExportTreeSelection(uri: Uri, onReady: () -> Unit) {
        exporter.handleExportTreeSelection(uri, onReady)
    }

    fun handleExportTreeCanceled() {
        exporter.handleExportTreeCanceled()
    }

    fun exportFolder(
        uiContext: Context, folder: File?, images: List<File>,
        exportThreads: Int, exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) {
        exporter.exportFolder(
            uiContext, folder, images, exportThreads, exportFormat,
            requestExportDirectoryPermission, requestLegacyPermission,
            onExitSelectionMode, onSetExportEnabled
        )
    }

    fun exportFolderAfterPermission(
        uiContext: Context, folder: File?, images: List<File>,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) {
        exporter.exportFolderAfterPermission(
            uiContext, folder, images, onExitSelectionMode, onSetExportEnabled
        )
    }

    fun exportCollection(
        uiContext: Context, collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        exportThreads: Int, exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) {
        exporter.exportCollection(
            uiContext, collectionFolder, chapterImages, exportThreads, exportFormat,
            requestExportDirectoryPermission, requestLegacyPermission,
            onExitSelectionMode, onSetExportEnabled
        )
    }

    fun exportCollectionAfterPermission(
        uiContext: Context, collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) {
        exporter.exportCollectionAfterPermission(
            uiContext, collectionFolder, chapterImages, onExitSelectionMode, onSetExportEnabled
        )
    }
}

/**
 * Export destination format. Physically lives here; the library facade keeps a
 * typealias so existing references to
 * `LibraryImportExportCoordinator.ExportFormat` keep working.
 */
enum class ExportFormat {
    IMAGE_DIR,
    CBZ,
    PDF
}
