package com.manga.translate.library

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.manga.translate.platform.ResourceAssessment
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.TranslationStore
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * Facade for library import/export. Kept as the single entry point for
 * [LibraryFragment]; the implementation is delegated to [ImportCoordinator]
 * (imports) and [ExportCoordinator] (export orchestration wrapping
 * [LibraryExporter], which uses [ExportStorageBackend] as its storage layer).
 *
 * `ExportFormat` physically lives in ExportCoordinator.kt; the typealias below
 * keeps `LibraryImportExportCoordinator.ExportFormat` references working
 * unchanged (see [LibraryDialogs] and [LibraryFragment]).
 */
internal class LibraryImportExportCoordinator(
    context: Context,
    repository: LibraryRepository,
    translationStore: TranslationStore,
    settingsStore: SettingsStore,
    prefs: SharedPreferences,
    preferencesGateway: LibraryPreferencesGateway,
    dialogs: LibraryDialogs,
    ui: LibraryUiCallbacks,
    exportTaskHost: ExportTaskHost
) {
    typealias ExportFormat = com.manga.translate.library.ExportFormat

    private val importCoordinator = ImportCoordinator(repository, preferencesGateway, dialogs, ui)
    private val exportCoordinator = ExportCoordinator(
        context, repository, translationStore, settingsStore,
        prefs, preferencesGateway, ui, exportTaskHost
    )

    fun isPendingExportCollection(): Boolean = exportCoordinator.isPendingExportCollection()

    fun getExportFormatDefault(): ExportFormat = exportCoordinator.getExportFormatDefault()
    fun buildExportRootPathPreview(): String = exportCoordinator.buildExportRootPathPreview()
    fun isExportActiveFor(folder: File): Boolean = exportCoordinator.isExportActiveFor(folder)

    suspend fun assessExportResources(
        images: List<File>,
        requestedThreads: Int
    ): ResourceAssessment = exportCoordinator.assessExportResources(images, requestedThreads)

    suspend fun suggestExportThreadCount(images: List<File>): Int =
        exportCoordinator.suggestExportThreadCount(images)

    suspend fun addImages(folder: File, uris: List<Uri>): List<File> =
        importCoordinator.addImages(folder, uris)

    fun requestImportDirectory(
        requestImportPermission: (Uri?) -> Unit
    ) {
        importCoordinator.requestImportDirectory(requestImportPermission)
    }

    suspend fun assessImportMemory(uiContext: Context, uri: Uri): ResourceAssessment =
        importCoordinator.assessImportMemory(uiContext, uri)

    fun importFromArchiveOrPdf(
        uiContext: Context,
        uri: Uri,
        scope: CoroutineScope,
        riskAlreadyAccepted: Boolean,
        onConfirmMemoryRisk: suspend (ResourceAssessment) -> Boolean,
        onShowFolderList: () -> Unit,
        targetCollection: File? = null,
        onImportFinished: (() -> Unit)? = null
    ) {
        importCoordinator.importFromArchiveOrPdf(
            uiContext,
            uri,
            scope,
            riskAlreadyAccepted,
            onConfirmMemoryRisk,
            onShowFolderList,
            targetCollection,
            onImportFinished
        )
    }

    fun handleImportTreeSelection(
        uiContext: Context,
        uri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        importCoordinator.handleImportTreeSelection(uiContext, uri, scope, onShowFolderList)
    }

    fun handleChapterImportTreeSelection(
        uiContext: Context,
        parentFolder: File,
        uri: Uri,
        scope: CoroutineScope
    ) {
        importCoordinator.handleChapterImportTreeSelection(uiContext, parentFolder, uri, scope)
    }

    fun handleStoragePermissionResult(granted: Boolean, onGranted: () -> Unit) {
        exportCoordinator.handleStoragePermissionResult(granted, onGranted)
    }

    fun handleExportTreeSelection(uri: Uri, onReady: () -> Unit) {
        exportCoordinator.handleExportTreeSelection(uri, onReady)
    }

    fun handleExportTreeCanceled() {
        exportCoordinator.handleExportTreeCanceled()
    }

    fun exportFolder(
        uiContext: Context, folder: File?, images: List<File>,
        exportThreads: Int, exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) {
        exportCoordinator.exportFolder(
            uiContext, folder, images, exportThreads, exportFormat,
            requestExportDirectoryPermission, requestLegacyPermission,
            onExitSelectionMode, onSetExportEnabled
        )
    }

    fun exportFolderAfterPermission(
        uiContext: Context, folder: File?, images: List<File>,
        onExitSelectionMode: () -> Unit, onSetExportEnabled: (Boolean) -> Unit
    ) {
        exportCoordinator.exportFolderAfterPermission(
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
        exportCoordinator.exportCollection(
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
        exportCoordinator.exportCollectionAfterPermission(
            uiContext, collectionFolder, chapterImages, onExitSelectionMode, onSetExportEnabled
        )
    }
}
