package com.manga.translate.library

import android.view.View
import com.manga.translate.R
import com.manga.translate.databinding.FragmentLibraryBinding
import java.io.File

/**
 * Owns the selection state for the library (root folder list) and the
 * chapter list shown inside a collection folder.
 *
 * - [LibraryFolderAdapter] (the root list) is driven through the library
 *   selection mode ([isLibrarySelectionMode]).
 * - [chapterAdapter] is driven through the chapter selection mode
 *   ([isChapterSelectionMode]).
 *
 * Image selection inside a plain folder remains owned by
 * [LibrarySelectionController]; entering chapter selection mode exits any
 * active image selection through [onExitImageSelectionMode].
 *
 * The manager only mutates selection state and the selection action bars;
 * the Fragment still refreshes the remaining UI (selection count status,
 * select-all label) through the adapters'
 * `onSelectionChanged` callbacks, reading the state exposed here.
 */
internal class LibrarySelectionManager(
    private val folderAdapter: LibraryFolderAdapter,
    private val chapterAdapter: LibraryFolderAdapter,
    private val ui: LibraryUiCallbacks,
    private val bindingProvider: () -> FragmentLibraryBinding?,
    private val onExitImageSelectionMode: () -> Unit
) {
    var isLibrarySelectionMode: Boolean = false
        private set

    var isChapterSelectionMode: Boolean = false
        private set

    // ---- Library (root folder list) selection ----

    fun enterLibrarySelectionMode(target: File) {
        if (!isLibrarySelectionMode) {
            isLibrarySelectionMode = true
            folderAdapter.setSelectionMode(true)
            bindingProvider()?.librarySelectionActions?.visibility = View.VISIBLE
            bindingProvider()?.addFolderFab?.apply {
                animate().cancel()
                visibility = View.GONE
            }
            ui.clearFolderStatus()
        }
        folderAdapter.toggleSelectionAndNotify(target)
    }

    fun exitLibrarySelectionMode() {
        if (!isLibrarySelectionMode) return
        isLibrarySelectionMode = false
        folderAdapter.setSelectionMode(false)
        bindingProvider()?.librarySelectionActions?.visibility = View.GONE
        bindingProvider()?.let { binding ->
            if (binding.libraryListContainer.visibility == View.VISIBLE &&
                binding.folderDetailContainer.visibility != View.VISIBLE
            ) {
                binding.addFolderFab.alpha = 1f
                binding.addFolderFab.translationY = 0f
                binding.addFolderFab.visibility = View.VISIBLE
            }
        }
        ui.clearFolderStatus()
    }

    fun toggleSelectAllLibraryFolders() {
        if (!isLibrarySelectionMode) return
        if (folderAdapter.areAllSelected()) {
            folderAdapter.clearSelection()
        } else {
            folderAdapter.selectAll()
        }
    }

    fun librarySelectedCount(): Int = folderAdapter.selectedCount()

    fun isLibraryAllSelected(): Boolean = folderAdapter.areAllSelected()

    fun librarySelectedFolders(): List<File> = folderAdapter.getSelectedFolders()

    // ---- Chapter selection ----

    fun enterChapterSelectionMode(target: File) {
        if (!isChapterSelectionMode) {
            isChapterSelectionMode = true
            onExitImageSelectionMode()
            chapterAdapter.setSelectionMode(true)
            bindingProvider()?.folderSelectionActions?.visibility = View.VISIBLE
            bindingProvider()?.folderRetranslateSelected?.apply {
                visibility = View.VISIBLE
                setActionDescription(R.string.translate_selected)
            }
        }
        chapterAdapter.toggleSelectionAndNotify(target)
    }

    fun exitChapterSelectionMode() {
        if (!isChapterSelectionMode) return
        isChapterSelectionMode = false
        chapterAdapter.setSelectionMode(false)
        bindingProvider()?.folderSelectionActions?.visibility = View.GONE
        bindingProvider()?.folderRetranslateSelected?.visibility = View.GONE
        ui.clearFolderStatus()
    }

    fun toggleSelectAllChapters() {
        if (!isChapterSelectionMode) return
        if (chapterAdapter.areAllSelected()) {
            chapterAdapter.clearSelection()
        } else {
            chapterAdapter.selectAll()
        }
    }

    fun chapterSelectedCount(): Int = chapterAdapter.selectedCount()

    fun isChapterAllSelected(): Boolean = chapterAdapter.areAllSelected()

    fun chapterSelectedFolders(): List<File> = chapterAdapter.getSelectedFolders()
}
