package com.manga.translate.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.manga.translate.R
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.DeviceResourcePolicy
import com.manga.translate.platform.ImageFileSupport
import com.manga.translate.platform.PdfImageCodec
import com.manga.translate.platform.ResourceAssessment
import com.manga.translate.model.FolderStatus
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library import orchestration: archive/PDF imports, EhViewer directory and
 * collection imports, and child-chapter imports. All library file operations
 * go through [LibraryRepository].
 */
internal class ImportCoordinator(
    private val repository: LibraryRepository,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val dialogs: LibraryDialogs,
    private val ui: LibraryUiCallbacks
) {
    private val pendingPdfPlans = ConcurrentHashMap<String, PdfImageCodec.PdfImportPlan>()

    suspend fun addImages(folder: File, uris: List<Uri>): List<File> =
        withImportProgress { onConversionStarted ->
            repository.addImages(folder, uris, onConversionStarted)
        }.also { added ->
            if (added.isNotEmpty()) {
                preferencesGateway.setCachedFolderStatus(folder, FolderStatus.UNTRANSLATED)
                preferencesGateway.invalidateCachedFolderStats(folder)
            }
        }

    fun requestImportDirectory(
        requestImportPermission: (Uri?) -> Unit
    ) {
        requestImportPermission(preferencesGateway.buildImportInitialUri())
    }

    suspend fun assessImportMemory(uiContext: Context, uri: Uri): ResourceAssessment {
        val snapshot = DeviceResourcePolicy.readSnapshot(uiContext)
        val displayName = runCatching {
            uiContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull().orEmpty()
        val isPdf = displayName.substringAfterLast('.', "").lowercase() == "pdf"
        val peakBytes = if (isPdf) {
            pendingPdfPlans.clear()
            PdfImageCodec.estimateImportPlan(
                contentResolver = uiContext.contentResolver,
                uri = uri
            )?.also { plan ->
                if (plan.reusable) pendingPdfPlans[uri.toString()] = plan
            }?.peakBytes
        } else {
            null
        }
        return DeviceResourcePolicy.assessImport(snapshot, peakBytes)
    }

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
        scope.launch(Dispatchers.IO) {
            val displayName = runCatching {
                uiContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull().orEmpty()
            val isPdf = displayName.substringAfterLast('.', "").lowercase() == "pdf"
            val collectionWasEmpty = targetCollection?.let { !collectionHasAnyImages(it) } ?: false
            val result = withImportProgress { onConversionStarted ->
                if (isPdf) {
                    repository.importPdf(uri, pendingPdfPlans.remove(uri.toString()))
                } else {
                    repository.importCbz(
                        uri = uri,
                        onAvifConversionStarted = onConversionStarted,
                        riskAlreadyAccepted = riskAlreadyAccepted,
                        confirmMemoryRisk = { assessment ->
                            withContext(Dispatchers.Main.immediate) {
                                onConfirmMemoryRisk(assessment)
                            }
                        }
                    )
                }
            }
            var cancelled = false
            try {
                val importedFolder = result?.folder?.let { folder ->
                    targetCollection?.let { collection ->
                        repository.moveFolderToCollection(folder, collection, uniqueTarget = true)
                            ?: run {
                                repository.deleteFolder(folder)
                                null
                            }
                    } ?: folder
                }
                importedFolder?.let { importedFolder ->
                    if (result.importedCount > 0) {
                        val images = repository.listImages(importedFolder)
                        preferencesGateway.autoDetectAndSetReadingMode(
                            importedFolder,
                            images
                        )
                        if (targetCollection != null) {
                            preferencesGateway.setCachedFolderStatus(
                                importedFolder,
                                FolderStatus.UNTRANSLATED
                            )
                            preferencesGateway.setCachedFolderStats(
                                importedFolder,
                                imageCount = images.size
                            )
                            preferencesGateway.setCachedFolderStatus(
                                targetCollection,
                                FolderStatus.UNTRANSLATED
                            )
                            preferencesGateway.invalidateCachedFolderStats(targetCollection)
                            if (collectionWasEmpty) {
                                val samples = repository.listChildFolders(targetCollection)
                                    .flatMap(repository::listImages)
                                    .take(READING_MODE_SAMPLE_LIMIT)
                                preferencesGateway.autoDetectAndSetReadingMode(
                                    targetCollection,
                                    samples
                                )
                            }
                        }
                    }
                }
                val importedCount = result?.importedCount ?: 0
                withContext(Dispatchers.Main) {
                    when {
                        result?.outOfMemory == true -> ui.showToast(R.string.import_out_of_memory)
                        result?.errorMessageRes != null -> ui.showToast(result.errorMessageRes)
                        result == null -> ui.showToast(
                            if (isPdf) R.string.pdf_import_failed else R.string.cbz_import_failed
                        )
                        importedFolder == null && importedCount > 0 -> ui.showToast(R.string.import_failed)
                        importedCount <= 0 -> ui.showToast(
                            if (isPdf) R.string.pdf_import_no_images else R.string.cbz_import_no_images
                        )
                        else -> ui.showToastMessage(
                            uiContext.getString(
                                if (isPdf) R.string.pdf_import_done else R.string.cbz_import_done,
                                importedCount
                            )
                        )
                    }
                    ui.refreshFolders()
                    if (ui.isFragmentActive()) {
                        onShowFolderList()
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Post-import UI update failed", e)
            } catch (e: OutOfMemoryError) {
                AppLogger.log("Library", "Post-import UI update ran out of memory", e)
            } finally {
                if (!cancelled) {
                    withContext(Dispatchers.Main.immediate) {
                        onImportFinished?.invoke()
                    }
                }
            }
        }
    }

    fun handleImportTreeSelection(
        uiContext: Context,
        uri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            uiContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist import permission failed", e)
        }
        preferencesGateway.setImportTreeUri(uri)
        showImportFolderPicker(uiContext, uri, scope, onShowFolderList)
    }

    fun handleChapterImportTreeSelection(
        uiContext: Context,
        parentFolder: File,
        uri: Uri,
        scope: CoroutineScope
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            uiContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist chapter import permission failed", e)
        }
        preferencesGateway.setImportTreeUri(uri)
        showChapterImportFolderPicker(uiContext, parentFolder, uri, scope)
    }

    private fun showImportFolderPicker(
        uiContext: Context,
        treeUri: Uri,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(uiContext, treeUri)
            if (root == null || !root.canRead()) {
                withContext(Dispatchers.Main) { ui.showToast(R.string.import_permission_required) }
                return@launch
            }
            val files = root.listFiles()
            val rootImages = files.filter { it.isFile && isImageDocument(it) }
            val chapters = files.mapNotNull { file ->
                if (!file.isDirectory) return@mapNotNull null
                val images = file.listFiles().filter { child -> child.isFile && isImageDocument(child) }
                images.takeIf { it.isNotEmpty() }?.let { ImportChapterSource(file.name.orEmpty(), file, it) }
            }
            withContext(Dispatchers.Main) {
                when {
                    chapters.isNotEmpty() -> dialogs.showEhViewerImportNameDialog(uiContext, root.name ?: "") { importName ->
                        importEhViewerCollection(uiContext, chapters, rootImages, importName, scope, onShowFolderList)
                    }
                    rootImages.isNotEmpty() -> dialogs.showEhViewerImportNameDialog(uiContext, root.name ?: "") { importName ->
                        importEhViewerFolder(uiContext, rootImages, importName, scope, onShowFolderList)
                    }
                    else -> ui.showToast(R.string.import_no_folders)
                }
            }
        }
    }

    private fun importEhViewerFolder(
        uiContext: Context,
        images: List<DocumentFile>,
        importName: String,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        if (images.isEmpty()) {
            ui.showToast(R.string.import_no_images)
            return
        }
        scope.launch(Dispatchers.IO) {
            val stagedImport = repository.beginFolderImport(importName)
            if (stagedImport == null) {
                withContext(Dispatchers.Main) { ui.showToast(R.string.import_folder_exists) }
                return@launch
            }
            var added = emptyList<File>()
            var importedFolder: File? = null
            try {
                added = withImportProgress { onConversionStarted ->
                    repository.addImages(stagedImport.folder, images.map { it.uri }, onConversionStarted)
                }
                if (added.isNotEmpty()) {
                    importedFolder = stagedImport.commit()
                }
                importedFolder?.let { folder ->
                    preferencesGateway.setCachedFolderStatus(folder, FolderStatus.UNTRANSLATED)
                    preferencesGateway.setCachedFolderStats(folder, imageCount = added.size)
                    preferencesGateway.autoDetectAndSetReadingMode(folder, repository.listImages(folder))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Directory import failed", e)
            } finally {
                if (importedFolder == null) {
                    stagedImport.discard()
                }
            }
            withContext(Dispatchers.Main) {
                if (importedFolder == null) {
                    ui.showToast(
                        if (images.isNotEmpty() && added.isEmpty()) {
                            R.string.import_images_failed
                        } else {
                            R.string.import_failed
                        }
                    )
                } else {
                    ui.showToastMessage(uiContext.getString(R.string.import_done, added.size))
                }
                ui.refreshFolders()
                if (ui.isFragmentActive()) {
                    onShowFolderList()
                }
            }
        }
    }

    private fun importEhViewerCollection(
        uiContext: Context,
        sources: List<ImportChapterSource>,
        rootImages: List<DocumentFile>,
        importName: String,
        scope: CoroutineScope,
        onShowFolderList: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val stagedImport = repository.beginFolderImport(importName, collection = true)
            if (stagedImport == null) {
                withContext(Dispatchers.Main) { ui.showToast(R.string.import_folder_exists) }
                return@launch
            }
            var importedChapters = 0
            var importedImages = 0
            var skippedChapters = 0
            var collection: File? = null

            try {
                withImportProgress { onConversionStarted ->
                    val sourceNames = sources.mapNotNull { it.name.trim().takeIf(String::isNotEmpty) }
                    val chapterSources = buildList {
                        addAll(sources)
                        if (rootImages.isNotEmpty()) {
                            // A mixed directory is always imported as a collection. Its direct
                            // images become a dedicated chapter instead of being silently ignored.
                            add(ImportChapterSource(
                                name = rootImageChapterName(importName, sourceNames),
                                folder = null,
                                images = rootImages
                            ))
                        }
                    }
                    for (source in chapterSources) {
                        val sourceName = source.name
                        if (sourceName.isEmpty()) {
                            skippedChapters += 1
                            continue
                        }
                        val chapterFolder = repository.createChildFolder(stagedImport.folder, sourceName)
                        if (chapterFolder == null) {
                            skippedChapters += 1
                            continue
                        }
                        if (source.images.isEmpty()) {
                            chapterFolder.deleteRecursively()
                            skippedChapters += 1
                            continue
                        }
                        val added = repository.addImages(
                            chapterFolder,
                            source.images.map { it.uri },
                            onConversionStarted
                        )
                        if (added.isEmpty()) {
                            chapterFolder.deleteRecursively()
                            skippedChapters += 1
                            continue
                        }
                        importedChapters += 1
                        importedImages += added.size
                    }
                }
                if (importedChapters > 0) {
                    collection = stagedImport.commit()
                }
                collection?.let { importedCollection ->
                    preferencesGateway.setCachedFolderStatus(importedCollection, FolderStatus.UNTRANSLATED)
                    val importedChapterFolders = repository.listChildFolders(importedCollection)
                    importedChapterFolders.forEach { chapter ->
                        preferencesGateway.setCachedFolderStatus(chapter, FolderStatus.UNTRANSLATED)
                        preferencesGateway.setCachedFolderStats(
                            chapter,
                            imageCount = repository.listImages(chapter).size
                        )
                    }
                    preferencesGateway.setCachedFolderStats(
                        importedCollection,
                        imageCount = importedImages,
                        chapterCount = importedChapterFolders.size
                    )
                    val samples = importedChapterFolders
                        .flatMap(repository::listImages)
                        .take(READING_MODE_SAMPLE_LIMIT)
                    preferencesGateway.autoDetectAndSetReadingMode(importedCollection, samples)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Collection import failed", e)
            } finally {
                if (collection == null) {
                    stagedImport.discard()
                }
            }

            withContext(Dispatchers.Main) {
                when {
                    collection == null -> ui.showToast(
                        if (sources.isNotEmpty() && importedImages == 0 && skippedChapters == sources.size) {
                            R.string.import_images_failed
                        } else {
                            R.string.import_failed
                        }
                    )
                    skippedChapters > 0 -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done_with_skipped,
                            importedChapters,
                            importedImages,
                            skippedChapters
                        )
                    )
                    else -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done,
                            importedChapters,
                            importedImages
                        )
                    )
                }
                ui.refreshFolders()
                if (ui.isFragmentActive()) {
                    onShowFolderList()
                }
            }
        }
    }

    private fun showChapterImportFolderPicker(
        uiContext: Context,
        parentFolder: File,
        treeUri: Uri,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(uiContext, treeUri)
            if (root == null || !root.canRead()) {
                withContext(Dispatchers.Main) { ui.showToast(R.string.import_permission_required) }
                return@launch
            }
            val files = root.listFiles()
            val chapters = files.mapNotNull { folder ->
                if (!folder.isDirectory) return@mapNotNull null
                val images = folder.listFiles().filter { child -> child.isFile && isImageDocument(child) }
                images.takeIf { it.isNotEmpty() }?.let { ImportChapterSource(folder.name.orEmpty(), folder, it) }
            }
            val rootImages = files.filter { it.isFile && isImageDocument(it) }
            withContext(Dispatchers.Main) {
                if (chapters.isNotEmpty()) {
                    val importSources = buildList {
                        addAll(chapters)
                        if (rootImages.isNotEmpty()) {
                            add(
                                ImportChapterSource(
                                    name = rootImageChapterName(root.name.orEmpty(), chapters.map { it.name }),
                                    folder = root,
                                    images = rootImages
                                )
                            )
                        }
                    }
                    val folders = importSources.mapNotNull { it.folder }
                    dialogs.showDocumentFolderMultiPicker(uiContext, R.string.chapter_import_select_folders, folders) { selected ->
                        if (selected.isEmpty()) ui.showToast(R.string.chapter_import_no_folders)
                        else {
                            val selectedUris = selected.mapTo(HashSet()) { it.uri }
                            importChildChapters(
                                uiContext,
                                parentFolder,
                                importSources.filter { it.folder?.uri in selectedUris },
                                scope
                            )
                        }
                    }
                } else if (files.any { it.isFile && isImageDocument(it) }) {
                    importChildChapters(
                        uiContext,
                        parentFolder,
                        listOf(ImportChapterSource(root.name.orEmpty(), root, files.filter { it.isFile && isImageDocument(it) })),
                        scope
                    )
                } else {
                    ui.showToast(R.string.chapter_import_no_folders)
                }
            }
        }
    }

    private fun importChildChapters(
        uiContext: Context,
        parentFolder: File,
        sources: List<ImportChapterSource>,
        scope: CoroutineScope
    ) {
        scope.launch(Dispatchers.IO) {
            val collectionWasEmpty = !collectionHasAnyImages(parentFolder)
            var importedChapters = 0
            var importedImages = 0
            var skippedChapters = 0
            val stagedImport = repository.beginChildChapterImport(parentFolder)
            if (stagedImport == null) {
                withContext(Dispatchers.Main) { ui.showToast(R.string.import_failed) }
                return@launch
            }
            var committedChapters: List<File>? = null

            try {
                withImportProgress { onConversionStarted ->
                    for (source in sources) {
                        val sourceName = source.name.trim()
                        if (sourceName.isEmpty() || !repository.canCreateChildFolder(parentFolder, sourceName)) {
                            skippedChapters += 1
                            continue
                        }
                        val chapterFolder = repository.createChildFolder(stagedImport.folder, sourceName)
                        if (chapterFolder == null) {
                            skippedChapters += 1
                            continue
                        }
                        val images = source.images
                        if (images.isEmpty()) {
                            chapterFolder.deleteRecursively()
                            skippedChapters += 1
                            continue
                        }
                        val added = repository.addImages(
                            chapterFolder,
                            images.map { it.uri },
                            onConversionStarted
                        )
                        if (added.isEmpty()) {
                            chapterFolder.deleteRecursively()
                            skippedChapters += 1
                            continue
                        }
                        importedChapters += 1
                        importedImages += added.size
                    }
                }
                if (importedChapters > 0) {
                    committedChapters = stagedImport.commit()
                }
                committedChapters.orEmpty().forEach { chapter ->
                    preferencesGateway.setCachedFolderStatus(chapter, FolderStatus.UNTRANSLATED)
                    preferencesGateway.setCachedFolderStats(
                        chapter,
                        imageCount = repository.listImages(chapter).size
                    )
                }
                if (!committedChapters.isNullOrEmpty()) {
                    preferencesGateway.setCachedFolderStatus(parentFolder, FolderStatus.UNTRANSLATED)
                    preferencesGateway.invalidateCachedFolderStats(parentFolder)
                }
                if (collectionWasEmpty && !committedChapters.isNullOrEmpty()) {
                    val committedSamples = committedChapters.orEmpty()
                        .flatMap(repository::listImages)
                        .take(READING_MODE_SAMPLE_LIMIT)
                    preferencesGateway.autoDetectAndSetReadingMode(parentFolder, committedSamples)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log("Library", "Chapter import failed", e)
            } finally {
                if (committedChapters == null) {
                    stagedImport.discard()
                }
            }

            withContext(Dispatchers.Main) {
                when {
                    committedChapters.isNullOrEmpty() -> ui.showToast(
                        if (sources.isNotEmpty() && importedImages == 0 && skippedChapters == sources.size) {
                            R.string.import_images_failed
                        } else {
                            R.string.import_failed
                        }
                    )
                    skippedChapters > 0 -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done_with_skipped,
                            importedChapters,
                            importedImages,
                            skippedChapters
                        )
                    )
                    else -> ui.showToastMessage(
                        uiContext.getString(
                            R.string.chapter_import_done,
                            importedChapters,
                            importedImages
                        )
                    )
                }
                ui.refreshImages(parentFolder)
                ui.refreshFolders()
            }
        }
    }

    private fun collectionHasAnyImages(collectionFolder: File): Boolean {
        return repository.listChildFolders(collectionFolder).any { chapter ->
            repository.listImages(chapter).isNotEmpty()
        }
    }

    private fun rootImageChapterName(importName: String, sourceNames: List<String>): String {
        val occupied = sourceNames.toHashSet()
        val base = "$importName - root"
        return generateSequence(1) { it + 1 }
            .map { index -> if (index == 1) base else "$base $index" }
            .first { it !in occupied }
    }

    private data class ImportChapterSource(
        val name: String,
        val folder: DocumentFile?,
        val images: List<DocumentFile>
    )

    private fun isImageDocument(file: DocumentFile): Boolean {
        val name = file.name ?: return false
        return ImageFileSupport.isSupportedImportImageFileName(name)
    }

    private suspend fun <T> withImportProgress(
        block: suspend (onConversionStarted: suspend () -> Unit) -> T
    ): T {
        var progressShown = false
        return try {
            withContext(Dispatchers.Main.immediate) {
                ui.showImportProgress(R.string.import_progress)
            }
            block {
                if (!progressShown) {
                    progressShown = true
                    withContext(Dispatchers.Main.immediate) {
                        ui.showImportProgress(R.string.avif_conversion_progress)
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                ui.hideImportProgress()
            }
        }
    }

    private companion object {
        private const val READING_MODE_SAMPLE_LIMIT = 6
    }
}
