package com.manga.translate.library

import java.io.File

internal interface LibraryUiCallbacks {
    fun setFolderStatus(left: String, right: String = "")
    fun clearFolderStatus()
    fun setTranslationActionsEnabled(enabled: Boolean)
    fun setFolderExportEnabled(folder: File, enabled: Boolean) = Unit
    fun showToast(resId: Int)
    fun showToastMessage(message: String)
    fun showImportProgress(messageRes: Int) = Unit
    fun hideImportProgress() = Unit
    fun showApiError(code: String, detail: String? = null)
    fun clearModelErrors() = Unit
    fun showModelError(
        content: String,
        useSystemOverlay: Boolean,
        onRetry: (() -> Unit)?,
        onSkip: (() -> Unit)? = null
    )
    fun refreshFolders()
    fun refreshImages(folder: File)
    fun showExportSuccess(path: String) = Unit
    fun isUiAttached(): Boolean
    fun isFragmentActive(): Boolean
    fun isAppInForeground(): Boolean
    fun isLibraryInForeground(): Boolean
    fun canShowSystemOverlay(): Boolean
}
