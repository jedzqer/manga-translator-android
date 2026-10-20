package com.manga.translate.library

import com.manga.translate.model.FolderItem
import com.manga.translate.model.FolderStatus
import com.manga.translate.model.ImageItem
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.storage.TranslationStore
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface FolderContent {
    data class Chapters(val items: List<FolderItem>) : FolderContent
    data class Images(val items: List<ImageItem>) : FolderContent
}

internal sealed interface FolderFilter {
    data class Status(val status: FolderStatus) : FolderFilter
    data class CustomTag(val tag: String) : FolderFilter
}

/** Disk reads and badge computation shared by the page's list/detail loaders. */
internal class LibraryPageLoader(
    private val repository: LibraryRepository,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val translationStore: TranslationStore
) {
    suspend fun loadContent(folder: File): FolderContent {
        return if (repository.isCollectionFolder(folder)) {
            val items = buildFolderItems(repository.listChildFolders(folder))
            computeAndCacheFolderStats(folder, isCollection = true)
            preferencesGateway.setCachedFolderStatus(folder, resolveFolderStatus(items.map { it.status }))
            FolderContent.Chapters(items)
        } else {
            val items = repository.listImages(folder).map {
                ImageItem(file = it, translated = isImageTranslated(it, folder))
            }
            preferencesGateway.setCachedFolderStats(folder, items.size)
            preferencesGateway.setCachedFolderStatus(folder, resolveFolderStatus(items.map {
                if (it.translated) FolderStatus.TRANSLATED else FolderStatus.UNTRANSLATED
            }))
            FolderContent.Images(items)
        }
    }

    fun buildFolderPreviewItems(folders: List<File>): List<FolderItem> {
        val items = ArrayList<FolderItem>(folders.size)
        for (folder in folders) {
            val cachedStatus = preferencesGateway.getCachedFolderStatus(folder)
            val cachedStats = preferencesGateway.getCachedFolderStats(folder)
            items += FolderItem(
                folder = folder,
                imageCount = cachedStats?.imageCount ?: 0,
                chapterCount = cachedStats?.chapterCount ?: 0,
                isCollection = repository.isCollectionFolder(folder),
                status = cachedStatus ?: FolderStatus.UNTRANSLATED,
                customTags = preferencesGateway.getFolderTags(folder)
                    .sortedWith(String.CASE_INSENSITIVE_ORDER),
                statsLoaded = cachedStats != null,
                statusLoaded = cachedStatus != null
            )
        }
        return items
    }

    /**
     * Unified folder stats computation: counts images and chapters, caches the result, and
     * returns the counts. Used by all three stats-loading paths to ensure consistency.
     */
    private fun computeAndCacheFolderStats(folder: File, isCollection: Boolean): Pair<Int, Int> {
        val chapters = if (isCollection) repository.listChildFolders(folder) else emptyList()
        val imageCount = if (isCollection) {
            chapters.sumOf { chapter -> repository.countImages(chapter) }
        } else {
            repository.countImages(folder)
        }
        preferencesGateway.setCachedFolderStats(folder, imageCount, chapters.size)
        return imageCount to chapters.size
    }

    fun loadAndCacheFolderStats(item: FolderItem): FolderItem {
        val (imageCount, chapterCount) = computeAndCacheFolderStats(item.folder, item.isCollection)
        return item.copy(
            imageCount = imageCount,
            chapterCount = chapterCount,
            statsLoaded = true
        )
    }

    private suspend fun buildFolderItems(folders: List<File>): List<FolderItem> {
        val items = ArrayList<FolderItem>(folders.size)
        for (folder in folders) {
            currentCoroutineContext().ensureActive()
            items += buildFolderItem(folder)
        }
        return items
    }

    private fun buildFolderItem(folder: File): FolderItem {
        val isCollection = repository.isCollectionFolder(folder)
        val chapters = if (isCollection) repository.listChildFolders(folder) else emptyList()
        val images = if (isCollection) {
            chapters.flatMap { repository.listImages(it) }
        } else {
            repository.listImages(folder)
        }
        val (imageCount, chapterCount) = computeAndCacheFolderStats(folder, isCollection)
        return FolderItem(
            folder = folder,
            imageCount = imageCount,
            chapterCount = chapterCount,
            isCollection = isCollection,
            status = resolveFolderStatus(folder, images),
            customTags = preferencesGateway.getFolderTags(folder)
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        )
    }

    private fun resolveFolderStatus(folder: File, images: List<File>): FolderStatus {
        if (images.isEmpty()) return FolderStatus.UNTRANSLATED
        val allTranslated = images.all { image -> isImageTranslated(image, folder) }
        return if (allTranslated) FolderStatus.TRANSLATED else FolderStatus.UNTRANSLATED
    }

    private fun resolveFolderStatus(statuses: List<FolderStatus>): FolderStatus {
        return if (statuses.isNotEmpty() && statuses.all { it == FolderStatus.TRANSLATED }) {
            FolderStatus.TRANSLATED
        } else {
            FolderStatus.UNTRANSLATED
        }
    }

    private fun isImageTranslated(image: File, folder: File): Boolean {
        // Align list/folder badges with engine skip rules: only SUCCESS or manual counts.
        val result = translationStore.load(image) ?: return false
        if (result.metadata.isManual()) return true
        return result.metadata.status == PageTranslationStatus.SUCCESS
    }

}
