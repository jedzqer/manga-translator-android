package com.manga.translate.translation

import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationResult
import com.manga.translate.platform.AppLogger
import com.manga.translate.storage.PageProgressStatus
import com.manga.translate.storage.TranslationProgressStore
import java.io.File

internal class PageResultCommitter(
    private val translationPipeline: TranslationPipeline,
    private val progressStore: TranslationProgressStore
) {
    suspend fun commit(
        folder: File,
        image: File,
        execution: PageTranslationExecutionResult?,
        errorMessage: String?
    ): Boolean {
        val result = execution?.result
        if (result != null) {
            saveResult(folder, image, result)
            return false
        }
        if (execution?.recoveredFromModelError == true) {
            progressStore.update(folder, image.name, PageProgressStatus.SKIPPED)
            return false
        }
        recordFailure(folder, image, errorMessage)
        return true
    }

    suspend fun recordFailure(folder: File, image: File, errorMessage: String?) {
        progressStore.update(folder, image.name, PageProgressStatus.FAILED, errorMessage)
        val existing = translationPipeline.loadAnyTranslation(image) ?: return
        if (existing.metadata.status == PageTranslationStatus.SUCCESS || existing.metadata.isManual()) return

        val failedResult = existing.copy(
            metadata = existing.metadata.copy(status = PageTranslationStatus.FAILED)
        )
        try {
            translationPipeline.saveResult(image, failedResult)
        } catch (e: Exception) {
            AppLogger.log("Library", "Failed to record FAILED status for ${image.name}", e)
        }
    }

    private suspend fun saveResult(folder: File, image: File, result: TranslationResult) {
        translationPipeline.saveResult(image, result)
        val status = if (result.metadata.status == PageTranslationStatus.SKIPPED) {
            PageProgressStatus.SKIPPED
        } else {
            PageProgressStatus.SAVED
        }
        progressStore.update(folder, image.name, status)
    }
}
