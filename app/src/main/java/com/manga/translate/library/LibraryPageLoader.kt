package com.manga.translate.library

import com.manga.translate.model.FolderItem
import com.manga.translate.model.FolderStatus
import com.manga.translate.model.ImageItem
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
        if (repository.isCollectionFolder(folder)) {
            // Opening a collection must not inspect every chapter's translation files.
            return FolderContent.Chapters(buildFolderPreviewItems(repository.listChildFolders(folder)))
        }
        val cached = translationStore.displayStatusCache.load(folder)
        val items = repository.listImages(folder).map { image ->
            val entry = cached[image.name]
            ImageItem(image, entry?.translated ?: false, statusLoaded = entry != null)
        }
        preferencesGateway.setCachedFolderStats(folder, items.size)
        return FolderContent.Images(items)
    }

    suspend fun refreshContent(folder: File, content: FolderContent, publish: suspend (FolderContent) -> Unit) {
        when (content) {
            is FolderContent.Chapters -> {
                val items = content.items.map { item ->
                    currentCoroutineContext().ensureActive()
                    if (item.statsLoaded) item else loadAndCacheFolderStats(item)
                }
                preferencesGateway.setCachedFolderStats(folder, items.sumOf { it.imageCount }, items.size)
                if (items.all { it.statusLoaded }) {
                    preferencesGateway.setCachedFolderStatus(folder, resolveFolderStatus(items.map { it.status }))
                }
                if (items != content.items) publish(FolderContent.Chapters(items))
            }
            is FolderContent.Images -> {
                val cached = translationStore.displayStatusCache.load(folder)
                val entries = mutableMapOf<String, com.manga.translate.storage.LibraryStatusCache.Entry>()
                val items = content.items.toMutableList()
                var nextPublication = 64
                for (index in items.indices) {
                    currentCoroutineContext().ensureActive()
                    val image = items[index].file
                    val file = translationStore.translationFileFor(image)
                    val modified = file.lastModified()
                    val size = file.length()
                    val entry = cached[image.name]?.takeIf { it.modified == modified && it.size == size }
                        ?: com.manga.translate.storage.LibraryStatusCache.Entry(modified, size, isImageTranslated(image))
                    entries[image.name] = entry
                    items[index] = ImageItem(image, entry.translated)
                    // Geometric checkpoints avoid copying and diffing the full list once per small batch.
                    if (index + 1 == nextPublication && index < items.lastIndex) {
                        publish(FolderContent.Images(items.toList()))
                        nextPublication *= 4
                    }
                }
                currentCoroutineContext().ensureActive()
                translationStore.displayStatusCache.save(folder, entries)
                preferencesGateway.setCachedFolderStatus(folder, resolveFolderStatus(items.map {
                    if (it.translated) FolderStatus.TRANSLATED else FolderStatus.UNTRANSLATED
                }))
                publish(FolderContent.Images(items.toList()))
            }
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

    private fun resolveFolderStatus(statuses: List<FolderStatus>): FolderStatus {
        return if (statuses.isNotEmpty() && statuses.all { it == FolderStatus.TRANSLATED }) {
            FolderStatus.TRANSLATED
        } else {
            FolderStatus.UNTRANSLATED
        }
    }

    private fun isImageTranslated(image: File): Boolean {
        return translationStore.isTranslatedForDisplay(image)
    }

}
