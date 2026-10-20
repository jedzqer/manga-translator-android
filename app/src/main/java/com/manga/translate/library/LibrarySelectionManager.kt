package com.manga.translate.library

import android.view.View
import com.manga.translate.R
import com.manga.translate.databinding.FragmentLibraryBinding
import java.io.File

/**
 * Routes root/chapter selection actions to page-owned state and renders action bars.
 * Image actions remain in LibrarySelectionController. Neither controller owns a copy
 * of the selected paths; adapters share the ViewModel's LibrarySelectionState.
 */
internal class LibrarySelectionManager(
    private val folderAdapter: LibraryFolderAdapter,
    private val chapterAdapter: LibraryFolderAdapter,
    private val ui: LibraryUiCallbacks,
    private val bindingProvider: () -> FragmentLibraryBinding?,
    private val onExitImageSelectionMode: () -> Unit
) {
    val isLibrarySelectionMode: Boolean get() = folderAdapter.selectionMode

    val isChapterSelectionMode: Boolean get() = chapterAdapter.selectionMode

    // ---- Library (root folder list) selection ----

    fun enterLibrarySelectionMode(target: File) {
        if (!isLibrarySelectionMode) {

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
