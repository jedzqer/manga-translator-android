package com.manga.translate.background

import com.manga.translate.library.LibraryUiBridge
import com.manga.translate.library.LibraryUiCallbacks
import java.io.File

internal class ServiceLibraryUiCallbacks(private val taskId: String) : LibraryUiCallbacks {
    override fun setFolderStatus(left: String, right: String) {
        com.manga.translate.platform.TaskProgressWriter(taskId).status(left, right)
    }

    override fun clearFolderStatus() {
        com.manga.translate.platform.TaskProgressWriter(taskId).status("")
    }

    override fun setTranslationActionsEnabled(enabled: Boolean) {
        // Enabled state is derived from the task snapshot.
    }

    override fun setFolderExportEnabled(folder: File, enabled: Boolean) {
        LibraryUiBridge.dispatch(taskId) { LibraryUiBridge.setFolderExportEnabled(folder, enabled) }
    }

    override fun showToast(resId: Int) {
        LibraryUiBridge.dispatch(taskId) { LibraryUiBridge.showToast(resId) }
    }

    override fun showToastMessage(message: String) {
        LibraryUiBridge.dispatch(taskId) { LibraryUiBridge.showToastMessage(message) }
    }

    override fun showApiError(code: String, detail: String?) {
        LibraryUiBridge.showApiError(taskId, code, detail)
    }

    override fun showModelError(
        content: String,
        useSystemOverlay: Boolean,
        onRetry: (() -> Unit)?,
        onSkip: (() -> Unit)?
    ) {
        LibraryUiBridge.showModelError(taskId, content, useSystemOverlay, onRetry, onSkip)
    }

    override fun refreshFolders() {
        LibraryUiBridge.dispatch(taskId) { LibraryUiBridge.refreshFolders() }
    }

    override fun refreshImages(folder: File) {
        LibraryUiBridge.dispatch(taskId) { LibraryUiBridge.refreshImages(folder) }
    }

    override fun showExportSuccess(path: String) {
        LibraryUiBridge.dispatch(taskId) { LibraryUiBridge.showExportSuccess(path) }
    }

    override fun clearModelErrors() = LibraryUiBridge.clearModelErrors(taskId)

    override fun isUiAttached(): Boolean = LibraryUiBridge.hasAttachedUi()

    override fun isFragmentActive(): Boolean = LibraryUiBridge.hasAttachedUi()

    override fun isAppInForeground(): Boolean = LibraryUiBridge.isAppInForeground()

    override fun isLibraryInForeground(): Boolean = LibraryUiBridge.hasAttachedUi()

    override fun canShowSystemOverlay(): Boolean = LibraryUiBridge.canShowSystemOverlay()
}
