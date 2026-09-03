package com.manga.translate.translation

import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.TranslationTaskDescriptor
import com.manga.translate.storage.TranslationTaskPersistence
import java.io.File

/**
 * Builds [FolderTranslationTask] instances and their persisted
 * [TranslationTaskDescriptor]s from library folders.
 *
 * This is the single place where folder-level translation preferences are read
 * (full-translate, glossary, VL direct translate, language) — the logic mirrors
 * what LibraryFragment previously did inline in runTranslation /
 * buildTranslationTasksForFolder / translateSelectedLibraryFolders.
 *
 * Contract for callers:
 *  - [buildFolderTask] reads preferences for [folder] and returns the task for
 *    exactly the given [images] (the caller decides which images are pending).
 *  - [buildTasksForFolder] expands [folder]: a non-collection folder yields a
 *    single task over `repository.listImages(folder)`; a collection folder
 *    (checked via `repository.isCollectionFolder`) yields one task per chapter
 *    from `repository.listChildFolders(folder)`, each reading its own preferences.
 *  - [buildFolderDescriptor] / [buildCollectionDescriptor] / [buildBatchDescriptor]
 *    persist the matching task(s) through [TranslationTaskPersistence] exactly as
 *    LibraryFragment's startTranslationTask callers did.
 */
internal class FolderTranslationTaskFactory(
    private val repository: LibraryRepository,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val settingsStore: SettingsStore
) {

    fun buildFolderTask(folder: File, images: List<File>, force: Boolean): FolderTranslationTask {
        val useLocalOcr = settingsStore.loadOcrApiSettings().useLocalOcr
        val language = TranslationLanguage.resolveForOcr(
            preferencesGateway.getTranslationLanguage(folder),
            useLocalOcr
        )
        return FolderTranslationTask(
            folder = folder,
            images = images,
            force = force,
            fullTranslate = preferencesGateway.isFullTranslateEnabled(folder),
            glossaryProcessingEnabled = preferencesGateway.isGlossaryProcessingEnabled(folder),
            useVlDirectTranslate = preferencesGateway.isVlDirectTranslateEnabled(folder),
            language = language
        )
    }

    fun buildTasksForFolder(folder: File, force: Boolean): List<FolderTranslationTask> {
        if (!repository.isCollectionFolder(folder)) {
            return listOf(buildFolderTask(folder, repository.listImages(folder), force))
        }
        return repository.listChildFolders(folder).map { chapter ->
            buildFolderTask(chapter, repository.listImages(chapter), force)
        }
    }

    fun buildFolderDescriptor(
        folder: File,
        images: List<File>,
        force: Boolean
    ): TranslationTaskDescriptor {
        return TranslationTaskPersistence.fromFolder(buildFolderTask(folder, images, force))
    }

    fun buildCollectionDescriptor(
        collectionFolder: File,
        force: Boolean
    ): TranslationTaskDescriptor {
        val tasks = buildTasksForFolder(collectionFolder, force)
        return TranslationTaskPersistence.fromCollection(collectionFolder, tasks)
    }

    fun buildBatchDescriptor(tasks: List<FolderTranslationTask>): TranslationTaskDescriptor {
        return TranslationTaskPersistence.fromBatch(tasks)
    }
}
