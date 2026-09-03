package com.manga.translate.library

import android.content.Context
import android.view.View
import android.widget.Toast
import com.manga.translate.R
import com.manga.translate.databinding.FragmentLibraryBinding
import com.manga.translate.platform.AppLogger
import com.manga.translate.storage.OcrStore
import com.manga.translate.storage.TranslationStore
import com.manga.translate.model.FolderStatus
import java.io.File

internal class LibrarySelectionController(
    private val imageAdapter: FolderImageAdapter,
    private val translationStore: TranslationStore,
    private val ocrStore: OcrStore,
    private val repository: LibraryRepository,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val ui: LibraryUiCallbacks,
    private val dialogs: LibraryDialogs,
    private val bindingProvider: () -> FragmentLibraryBinding?,
    private val contextProvider: () -> Context?,
    private val onRetranslateRequested: (File, List<File>, Boolean) -> Unit
) {
    var isSelectionMode: Boolean = false
        private set

    fun enterSelectionMode(target: File) {
        if (!isSelectionMode) {
            isSelectionMode = true
            imageAdapter.setSelectionMode(true)
            bindingProvider()?.folderSelectionActions?.visibility = View.VISIBLE
        }
        imageAdapter.toggleSelectionAndNotify(target)
        updateSelectionActions()
    }

    fun exitSelectionMode() {
        if (!isSelectionMode) return
        isSelectionMode = false
        imageAdapter.setSelectionMode(false)
        bindingProvider()?.folderSelectionActions?.visibility = View.GONE
        ui.clearFolderStatus()
    }

    fun updateSelectionActions() {
        if (!isSelectionMode) return
        val context = contextProvider() ?: return
        val count = imageAdapter.selectedCount()
        ui.setFolderStatus(context.getString(R.string.folder_selection_count, count))
        val buttonText = if (imageAdapter.areAllSelected()) {
            context.getString(R.string.clear_all)
        } else {
            context.getString(R.string.select_all)
        }
        bindingProvider()?.folderSelectAll?.text = buttonText
    }

    fun toggleSelectAllImages() {
        if (!isSelectionMode) return
        if (imageAdapter.areAllSelected()) {
            imageAdapter.clearSelection()
        } else {
            imageAdapter.selectAll()
        }
        updateSelectionActions()
    }

    fun confirmDeleteSelectedImages(folder: File?) {
        val context = contextProvider() ?: return
        if (folder == null) return
        val selected = imageAdapter.getSelectedFiles()
        if (selected.isEmpty()) {
            ui.setFolderStatus(context.getString(R.string.delete_images_empty))
            return
        }
        dialogs.confirmDeleteSelectedImages(context, selected.size) {
            val failedFiles = mutableListOf<File>()
            for (file in selected) {
                if (!deleteImageAndSidecars(
                        imageFile = file,
                        translationFile = translationStore.translationFileFor(file),
                        ocrFile = ocrStore.ocrFileFor(file)
                    )
                ) {
                    failedFiles += file
                    AppLogger.log(
                        "Library",
                        "Failed to delete ${file.name}; translation and OCR sidecars were retained"
                    )
                }
            }
            if (failedFiles.size < selected.size) {
                // Any successful deletion changes the chapter count, which also feeds the
                // parent collection's aggregated card, so both caches must be invalidated.
                preferencesGateway.invalidateCachedFolderStats(folder)
                folder.parentFile
                    ?.takeIf(repository::isCollectionFolder)
                    ?.let(preferencesGateway::invalidateCachedFolderStats)
            }
            if (failedFiles.isNotEmpty()) {
                AppLogger.log(
                    "Library",
                    "Failed to delete ${failedFiles.size} selected images from ${folder.name}"
                )
                val message = context.getString(R.string.delete_images_failed) + ": " +
                    failedFiles.joinToString { it.name }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } else {
                AppLogger.log("Library", "Deleted ${selected.size} images from ${folder.name}")
                preferencesGateway.setCachedFolderStatus(folder, FolderStatus.UNTRANSLATED)
                exitSelectionMode()
            }
            ui.refreshImages(folder)
            ui.refreshFolders()
        }
    }

    fun retranslateSelectedImages(folder: File?) {
        val context = contextProvider() ?: return
        if (folder == null) return
        val selected = imageAdapter.getSelectedFiles()
        if (selected.isEmpty()) {
            ui.setFolderStatus(context.getString(R.string.retranslate_images_empty))
            return
        }
        exitSelectionMode()
        onRetranslateRequested(folder, selected, true)
    }
}

internal fun deleteImageAndSidecars(
    imageFile: File,
    translationFile: File,
    ocrFile: File
): Boolean {
    if (!imageFile.delete()) return false
    translationFile.delete()
    ocrFile.delete()
    return true
}
