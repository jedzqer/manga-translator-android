package com.manga.translate.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.manga.translate.R
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.model.TranslationResult
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.DeviceResourcePolicy
import com.manga.translate.platform.ImageFileSupport
import com.manga.translate.platform.PdfImageCodec
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.ResourceAssessment
import com.manga.translate.rendering.BubbleRenderer
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.TranslationStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class LibraryExporter(
    context: Context,
    private val repository: LibraryRepository,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val ui: LibraryUiCallbacks,
    private val exportTaskHost: ExportTaskHost
) {
    private val appContext = context.applicationContext
    private val storageBackend = ExportStorageBackend(context)
    private var pendingExportAfterPermission = false
    private var pendingExportAfterExportTreeSelection = false
    var pendingExportIsCollection = false
        private set
    private var pendingExportThreads = loadExportThreads()
    private var pendingExportFormat: ExportFormat = loadExportFormatDefault()

    fun getExportFormat(): ExportFormat = loadExportFormatDefault()
    fun isExportActiveFor(folder: File): Boolean = exportTaskHost.isExportActiveFor(folder)
    fun buildExportRootPreview(): String {
        val treeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        return treeUri?.let(storageBackend::buildExportRootPathHint) ?: "/Documents/manga-translate"
    }

    fun assessExportResources(images: List<File>, requestedThreads: Int): ResourceAssessment {
        val perWorkerBytes = images.asSequence()
            .mapNotNull(PipelineBitmapDecoder::readImageSize)
            .map { size -> DeviceResourcePolicy.estimateExportWorkerBytes(size.width, size.height) }
            .maxOrNull()
            ?: DEFAULT_EXPORT_WORKER_BYTES
        return DeviceResourcePolicy.assessConcurrency(
            snapshot = DeviceResourcePolicy.readSnapshot(appContext),
            perWorkerBytes = perWorkerBytes,
            requestedConcurrency = normalizeExportThreads(requestedThreads),
            hardCap = MAX_EXPORT_THREADS
        )
    }

    fun suggestExportThreads(images: List<File>): Int {
        val saved = loadExportThreads()
        val recommended = assessExportResources(images, saved).recommendedConcurrency ?: saved
        return minOf(saved, recommended).coerceIn(MIN_EXPORT_THREADS, MAX_EXPORT_THREADS)
    }

    fun handleStoragePermissionResult(
        granted: Boolean,
        onGranted: () -> Unit
    ) {
        if (pendingExportAfterPermission && granted) {
            pendingExportAfterPermission = false
            onGranted()
            return
        }
        pendingExportAfterPermission = false
        pendingExportIsCollection = false
        if (!granted) {
            ui.showToast(R.string.export_permission_denied)
            ui.setFolderStatus(appContext.getString(R.string.export_permission_denied))
        }
    }

    fun handleExportTreeSelection(uri: Uri, onReady: () -> Unit) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            AppLogger.log("Library", "Persist export permission failed", e)
        }
        preferencesGateway.setExportTreeUri(uri)
        if (pendingExportAfterExportTreeSelection) {
            pendingExportAfterExportTreeSelection = false
            onReady()
        }
    }

    fun handleExportTreeCanceled() {
        pendingExportAfterExportTreeSelection = false
        pendingExportIsCollection = false
    }

    fun exportFolder(
        uiContext: Context,
        folder: File?,
        images: List<File>,
        exportThreads: Int,
        exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        if (folder == null) return
        pendingExportIsCollection = false
        pendingExportThreads = normalizeExportThreads(exportThreads)
        pendingExportFormat = exportFormat
        preferencesGateway.setExportPreferences(pendingExportThreads, pendingExportFormat.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val treeUri = preferencesGateway.getExportTreeUri()
            if (treeUri == null || !preferencesGateway.hasExportPermission(treeUri)) {
                pendingExportAfterExportTreeSelection = true
                ui.showToast(R.string.export_directory_required)
                requestExportDirectoryPermission(preferencesGateway.buildExportInitialUri())
                return
            }
        } else {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(
                uiContext,
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportAfterPermission = true
                requestLegacyPermission()
                return
            }
        }
        exportFolderInternal(
            folder = folder,
            images = images,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportFolderAfterPermission(
        uiContext: Context,
        folder: File?,
        images: List<File>,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        if (folder == null) return
        exportFolderInternal(
            folder = folder,
            images = images,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportCollection(
        uiContext: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        exportThreads: Int,
        exportFormat: ExportFormat,
        requestExportDirectoryPermission: (Uri?) -> Unit,
        requestLegacyPermission: () -> Unit,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        pendingExportIsCollection = true
        pendingExportThreads = normalizeExportThreads(exportThreads)
        pendingExportFormat = exportFormat
        preferencesGateway.setExportPreferences(pendingExportThreads, pendingExportFormat.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val treeUri = preferencesGateway.getExportTreeUri()
            if (treeUri == null || !preferencesGateway.hasExportPermission(treeUri)) {
                pendingExportAfterExportTreeSelection = true
                ui.showToast(R.string.export_directory_required)
                requestExportDirectoryPermission(preferencesGateway.buildExportInitialUri())
                return
            }
        } else {
            val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(
                uiContext,
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingExportAfterPermission = true
                requestLegacyPermission()
                return
            }
        }
        exportCollectionInternal(
            collectionFolder = collectionFolder,
            chapterImages = chapterImages,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    fun exportCollectionAfterPermission(
        uiContext: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        exportCollectionInternal(
            collectionFolder = collectionFolder,
            chapterImages = chapterImages,
            exportThreads = pendingExportThreads,
            exportFormat = pendingExportFormat,
            onExitSelectionMode = onExitSelectionMode,
            onSetExportEnabled = onSetExportEnabled
        )
    }

    private fun exportFolderInternal(
        folder: File,
        images: List<File>,
        exportThreads: Int,
        exportFormat: ExportFormat,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        val exportImages = images
        if (exportImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }
        val exportTreeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        val verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText

        val target = exportTarget(folder, exportFormat, exportTreeUri)
        if (!launchExportTask(target) exportTask@{
            val exportTaskId = TranslationKeepAliveService.startExportTask(
                appContext,
                appContext.getString(R.string.export_keepalive_title),
                appContext.getString(R.string.translation_keepalive_message),
                appContext.getString(R.string.exporting_progress, 0, exportImages.size)
            )
            var failed = false
            var exportDir: DocumentFile? = null
            var exportDirReady = true
            try {
                withContext(Dispatchers.IO) {
                    if (exportTreeUri != null && exportFormat == ExportFormat.IMAGE_DIR) {
                        exportDir = storageBackend.resolveExportDirectory(exportTreeUri, folder.name)
                        if (exportDir == null) {
                            exportDirReady = false
                        } else {
                            storageBackend.ensureNoMediaFile(exportDir)
                        }
                    } else if (exportFormat == ExportFormat.IMAGE_DIR) {
                        storageBackend.ensureNoMediaFile(folder.name)
                    }
                }
                if (!exportDirReady) {
                    failed = true

                    return@exportTask
                }

                var successPathHint: String? = null

                when (exportFormat) {
                    ExportFormat.CBZ -> {
                        val result = exportCbzWithBubbles(
                            context = appContext,
                            folder = folder,
                            images = exportImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri
                        ) { count ->
                            withContext(Dispatchers.Main) {

                                TranslationKeepAliveService.updateExportProgress(
                                    appContext,
                                    exportTaskId,
                                    count,
                                    exportImages.size,
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                )
                            }
                        }
                        failed = !result.success
                        successPathHint = result.pathHint
                    }
                    ExportFormat.PDF -> {
                        val result = exportPdfWithBubbles(
                            context = appContext,
                            folder = folder,
                            images = exportImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri
                        ) { count ->
                            withContext(Dispatchers.Main) {

                                TranslationKeepAliveService.updateExportProgress(
                                    appContext,
                                    exportTaskId,
                                    count,
                                    exportImages.size,
                                    appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                )
                            }
                        }
                        failed = !result.success
                        successPathHint = result.pathHint
                    }
                    ExportFormat.IMAGE_DIR -> {
                        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
                        val exportedCount = AtomicInteger(0)
                        val hasFailures = AtomicBoolean(false)

                        coroutineScope {
                            val tasks = exportImages.map { image ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        val renderer = BubbleRenderer(appContext)
                                        val success = exportImageWithBubbles(
                                            appContext,
                                            renderer,
                                            image,
                                            folder.name,
                                            verticalLayoutEnabled,
                                            exportDir
                                        )
                                        if (!success) {
                                            hasFailures.set(true)
                                        }
                                        val count = exportedCount.incrementAndGet()
                                        withContext(Dispatchers.Main) {

                                            TranslationKeepAliveService.updateExportProgress(
                                                appContext,
                                                exportTaskId,
                                                count,
                                                exportImages.size,
                                                appContext.getString(R.string.exporting_progress, count, exportImages.size)
                                            )
                                        }
                                    }
                                }
                            }
                            tasks.awaitAll()
                        }
                        failed = hasFailures.get()
                    }
                }


                if (!failed) {
                    val path = successPathHint ?: if (exportTreeUri != null) {
                        buildExportPathHint(exportTreeUri, folder.name)
                    } else {
                        "/Documents/manga-translate/${folder.name}"
                    }
                    LibraryUiBridge.showExportSuccess(path)
                }
                AppLogger.log(
                    "Library",
                    "Export ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
            } catch (e: CancellationException) {
                failed = true
                throw e
            } catch (e: Exception) {
                failed = true
                AppLogger.log("Library", "Export failed: ${folder.name}", e)

            } finally {
                LibraryUiBridge.setFolderExportEnabled(folder, true)
                TranslationKeepAliveService.finishExportTask(
                    context = appContext,
                    taskId = exportTaskId,
                    failed = failed,
                    content = appContext.getString(
                        if (failed) R.string.export_failed else R.string.export_done
                    )
                )
            }
        }) {

            return
        }
        onExitSelectionMode()
        onSetExportEnabled(false)
        LibraryUiBridge.setFolderExportEnabled(folder, false)
    }

    private fun exportCollectionInternal(
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        exportThreads: Int,
        exportFormat: ExportFormat,
        onExitSelectionMode: () -> Unit,
        onSetExportEnabled: (Boolean) -> Unit
    ) {
        val allImages = chapterImages.flatMap { it.second }
        if (allImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_chapters_empty))
            return
        }
        val exportTreeUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            preferencesGateway.getExportTreeUri()?.takeIf { preferencesGateway.hasExportPermission(it) }
        } else {
            null
        }
        val verticalLayoutEnabled = !settingsStore.loadNormalBubbleRenderSettings().useHorizontalText

        val target = exportTarget(collectionFolder, exportFormat, exportTreeUri)
        if (!launchExportTask(target) {
            val exportTaskId = TranslationKeepAliveService.startExportTask(
                appContext,
                appContext.getString(R.string.export_keepalive_title),
                appContext.getString(R.string.translation_keepalive_message),
                appContext.getString(R.string.exporting_progress, 0, allImages.size)
            )
            var failed = false
            try {
                when (exportFormat) {
                    ExportFormat.IMAGE_DIR -> {
                        failed = exportCollectionAsImageDir(
                            context = appContext,
                            collectionName = collectionFolder.name,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri,
                            totalImages = allImages.size,
                            exportTaskId = exportTaskId
                        )
                    }
                    ExportFormat.CBZ -> {
                        val result = exportCollectionAsCbz(
                            context = appContext,
                            collectionFolder = collectionFolder,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri,
                            exportTaskId = exportTaskId
                        )
                        failed = !result.success
                        if (!failed) {
                            val path = result.pathHint ?: if (exportTreeUri != null) {
                                "${storageBackend.buildExportRootPathHint(exportTreeUri)}/${collectionFolder.name}.cbz"
                            } else {
                                "/Documents/manga-translate/${collectionFolder.name}.cbz"
                            }
                            LibraryUiBridge.showExportSuccess(path)
                        }
                    }
                    ExportFormat.PDF -> {
                        val result = exportCollectionAsPdfWithOutlines(
                            context = appContext,
                            collectionFolder = collectionFolder,
                            chapterImages = chapterImages,
                            verticalLayoutEnabled = verticalLayoutEnabled,
                            exportThreads = normalizeExportThreads(exportThreads),
                            exportTreeUri = exportTreeUri,
                            exportTaskId = exportTaskId
                        )
                        failed = !result.success
                        if (!failed) {
                            val path = result.pathHint ?: if (exportTreeUri != null) {
                                "${storageBackend.buildExportRootPathHint(exportTreeUri)}/${collectionFolder.name}.pdf"
                            } else {
                                "/Documents/manga-translate/${collectionFolder.name}.pdf"
                            }
                            LibraryUiBridge.showExportSuccess(path)
                        }
                    }
                }


                AppLogger.log(
                    "Library",
                    "Collection export ${if (failed) "completed with failures" else "completed"}: ${collectionFolder.name}"
                )
            } catch (e: CancellationException) {
                failed = true
                throw e
            } catch (e: Exception) {
                failed = true
                AppLogger.log("Library", "Collection export failed: ${collectionFolder.name}", e)

            } finally {
                LibraryUiBridge.setFolderExportEnabled(collectionFolder, true)
                TranslationKeepAliveService.finishExportTask(
                    context = appContext,
                    taskId = exportTaskId,
                    failed = failed,
                    content = appContext.getString(
                        if (failed) R.string.export_failed else R.string.export_done
                    )
                )
            }
        }) {

            return
        }
        onExitSelectionMode()
        onSetExportEnabled(false)
        LibraryUiBridge.setFolderExportEnabled(collectionFolder, false)
    }

    private fun launchExportTask(target: ExportTaskTarget, block: suspend () -> Unit): Boolean {
        return exportTaskHost.launch(target, block)
    }

    private fun exportTarget(folder: File, format: ExportFormat, exportTreeUri: Uri?): ExportTaskTarget {
        val extension = when (format) {
            ExportFormat.IMAGE_DIR -> "directory"
            ExportFormat.CBZ -> "cbz"
            ExportFormat.PDF -> "pdf"
        }
        return ExportTaskTarget(
            folderPath = folder.absolutePath,
            destination = "${exportTreeUri ?: "legacy"}|${folder.name}|$extension"
        )
    }

    private suspend fun exportCollectionAsImageDir(
        context: Context,
        collectionName: String,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        totalImages: Int,
        exportTaskId: String
    ): Boolean {
        var collectionDir: DocumentFile? = null
        var collectionDirReady = true

        if (exportTreeUri != null) {
            collectionDir = storageBackend.resolveExportDirectory(exportTreeUri, collectionName)
            if (collectionDir == null) {
                collectionDirReady = false
            } else {
                storageBackend.ensureNoMediaFile(collectionDir)
            }
        } else {
            storageBackend.ensureNoMediaFile(collectionName)
        }

        if (!collectionDirReady) {
            return true
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val exportedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)

        coroutineScope {
            val tasks = chapterImages.flatMap { (chapter, images) ->
                images.map { image ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val renderer = BubbleRenderer(context)
                            val chapterExportDir = if (collectionDir != null) {
                                val existing = collectionDir.findFile(chapter.name)
                                when {
                                    existing == null -> collectionDir.createDirectory(chapter.name)
                                    existing.isDirectory -> existing
                                    else -> null
                                }
                            } else {
                                null
                            }
                            val success = exportImageWithBubbles(
                                context,
                                renderer,
                                image,
                                if (collectionDir != null) "" else "$collectionName/${chapter.name}",
                                verticalLayoutEnabled,
                                chapterExportDir
                            )
                            if (!success) {
                                hasFailures.set(true)
                            }
                            val count = exportedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {

                                TranslationKeepAliveService.updateExportProgress(
                                    appContext,
                                    exportTaskId,
                                    count,
                                    totalImages,
                                    appContext.getString(R.string.exporting_progress, count, totalImages)
                                )
                            }
                        }
                    }
                }
            }
            tasks.awaitAll()
        }

        if (!hasFailures.get()) {
            val path = if (exportTreeUri != null) {
                buildExportPathHint(exportTreeUri, collectionName)
            } else {
                "/Documents/manga-translate/$collectionName"
            }
            withContext(Dispatchers.Main) {
                LibraryUiBridge.showExportSuccess(path)
            }
        }
        return hasFailures.get()
    }

    private suspend fun exportCollectionAsCbz(
        context: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        exportTaskId: String
    ): CbzExportResult {
        val preparedEntries = prepareCollectionArchiveEntries(
            context = context,
            chapterImages = chapterImages,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            exportTaskId = exportTaskId
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportCbzToDocumentTree(
                context = context,
                folder = collectionFolder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportCbzToLegacyStorage(
                folder = collectionFolder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportCollectionAsPdfWithOutlines(
        context: Context,
        collectionFolder: File,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        exportTaskId: String
    ): CbzExportResult {
        val preparedEntries = prepareCollectionArchiveEntries(
            context = context,
            chapterImages = chapterImages,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            exportTaskId = exportTaskId
        ) ?: return CbzExportResult(success = false, pathHint = null)

        val sortedEntries = preparedEntries.sortedBy { it.index }
        val orderedImages = sortedEntries.map { it.tempFile }

        var pageIndex = 0
        val chapterOutlines = chapterImages.map { (chapter, images) ->
            val outline = chapter.name to pageIndex
            pageIndex += images.size
            outline
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            writeCollectionPdfToDocumentTree(
                context = context,
                folder = collectionFolder,
                exportTreeUri = exportTreeUri,
                chapterOutlines = chapterOutlines,
                orderedImages = orderedImages,
                preparedEntries = preparedEntries
            )
        } else {
            writeCollectionPdfToLegacyStorage(
                folder = collectionFolder,
                chapterOutlines = chapterOutlines,
                orderedImages = orderedImages,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun writeCollectionPdfToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        chapterOutlines: List<Pair<String, Int>>,
        orderedImages: List<File>,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val pdfName = storageBackend.resolveUniquePdfName(root, folder.name)
        val pathHint = "${storageBackend.buildExportRootPathHint(exportTreeUri)}/$pdfName"

        return try {
            val success = storageBackend.writeDocumentFileTransaction(
                exportDir = root,
                finalName = pdfName,
                mimeType = "application/pdf",
                replaceExisting = false
            ) { stream ->
                PdfImageCodec.writeImagesToPdfWithOutlines(chapterOutlines, orderedImages, stream)
            }
            CbzExportResult(success = success, pathHint = pathHint.takeIf { success })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun writeCollectionPdfToLegacyStorage(
        folder: File,
        chapterOutlines: List<Pair<String, Int>>,
        orderedImages: List<File>,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }

        return try {
            val target = storageBackend.writeLegacyExportTransaction(root, "${folder.name}.pdf") { stream ->
                PdfImageCodec.writeImagesToPdfWithOutlines(chapterOutlines, orderedImages, stream)
            }
            CbzExportResult(
                success = target != null,
                pathHint = target?.let { "/Documents/manga-translate/${it.name}" }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun prepareCollectionArchiveEntries(
        context: Context,
        chapterImages: List<Pair<File, List<File>>>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTaskId: String
    ): List<PreparedCbzEntry>? {
        val allImages = chapterImages.flatMap { (chapter, images) ->
            images.map { chapter to it }
        }
        if (allImages.isEmpty()) return null

        val tempDir = File(context.cacheDir, "cbz_export_${System.currentTimeMillis()}_collection")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            AppLogger.log("Library", "Create CBZ temp directory failed: ${tempDir.absolutePath}")
            return null
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val renderedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)
        val totalImages = allImages.size
        val entries = MutableList<PreparedCbzEntry?>(totalImages) { null }

        return try {
            coroutineScope {
                val tasks = allImages.mapIndexed { index, (chapter, image) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val prepared = renderImageToTempFileWithPrefix(
                                context = context,
                                imageFile = image,
                                prefix = chapter.name,
                                verticalLayoutEnabled = verticalLayoutEnabled,
                                tempDir = tempDir,
                                index = index
                            )
                            if (prepared == null) {
                                hasFailures.set(true)
                            } else {
                                entries[index] = prepared
                            }
                            val count = renderedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {

                                TranslationKeepAliveService.updateExportProgress(
                                    appContext,
                                    exportTaskId,
                                    count,
                                    totalImages,
                                    appContext.getString(R.string.exporting_progress, count, totalImages)
                                )
                            }
                        }
                    }
                }
                tasks.awaitAll()
            }
            if (hasFailures.get() || entries.any { it == null }) {
                null
            } else {
                entries.filterNotNull()
            }
        } finally {
            if (hasFailures.get() || entries.any { it == null }) {
                runCatching { tempDir.deleteRecursively() }
            }
        }
    }

    private suspend fun renderImageToTempFileWithPrefix(
        context: Context,
        imageFile: File,
        prefix: String,
        verticalLayoutEnabled: Boolean,
        tempDir: File,
        index: Int
    ): PreparedCbzEntry? {
        val translation = translationStore.load(imageFile)
        val hasText = hasExportableTranslation(translation)
        val spec = storageBackend.resolveExportSpec(imageFile.name)
        val entryName = "$prefix/${spec.displayName}"
        val tempFile = File(tempDir, "entry_$index")
        if (!hasText && canPassthroughOriginal(imageFile, spec)) {
            val success = try {
                imageFile.copyTo(tempFile, overwrite = true)
                true
            } catch (e: Exception) {
                AppLogger.log("Library", "Copy CBZ entry failed: ${imageFile.name}", e)
                false
            }
            if (!success) return null
            return PreparedCbzEntry(index = index, entryName = entryName, tempFile = tempFile)
        }
        val renderer = BubbleRenderer(context)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        var output: Bitmap? = null
        try {
            output = if (hasText && translation != null) {
                renderer.render(bitmap, translation, verticalLayoutEnabled)
            } else {
                bitmap
            }
            val success = try {
                FileOutputStream(tempFile).use { outputStream ->
                    output.compress(spec.format, spec.quality, outputStream)
                }
            } catch (e: Exception) {
                AppLogger.log("Library", "Write CBZ entry failed: ${imageFile.name}", e)
                false
            }
            if (!success) return null
            return PreparedCbzEntry(index = index, entryName = entryName, tempFile = tempFile)
        } finally {
            if (output != null && output !== bitmap) {
                output.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun buildExportPathHint(treeUri: Uri, folderName: String): String {
        val base = storageBackend.buildExportRootPathHint(treeUri)
        return "$base/$folderName"
    }

    private fun isImageDocument(file: DocumentFile): Boolean {
        val name = file.name.orEmpty()
        return ImageFileSupport.isSupportedSourceImageFileName(name)
    }

    private suspend fun exportImageWithBubbles(
        context: Context,
        renderer: BubbleRenderer,
        imageFile: File,
        folderName: String,
        verticalLayoutEnabled: Boolean,
        exportDir: DocumentFile?
    ): Boolean {
        val translation = translationStore.load(imageFile)
        val hasText = hasExportableTranslation(translation)
        val spec = storageBackend.resolveExportSpec(imageFile.name)
        val success = if (!hasText && canPassthroughOriginal(imageFile, spec)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportDir != null) {
                storageBackend.copyFileToDocumentFile(imageFile, spec, exportDir)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                storageBackend.copyFileToMediaStore(imageFile, spec, folderName)
            } else {
                storageBackend.copyFileToLegacyStorage(imageFile, spec, folderName)
            }
        } else {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
            val output = if (hasText && translation != null) {
                renderer.render(bitmap, translation, verticalLayoutEnabled)
            } else {
                bitmap
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportDir != null) {
                    storageBackend.saveBitmapToDocumentFile(output, spec, exportDir)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    storageBackend.saveBitmapToMediaStore(output, spec, folderName)
                } else {
                    storageBackend.saveBitmapToLegacyStorage(output, spec, folderName)
                }
            } finally {
                if (output !== bitmap) {
                    output.recycle()
                }
                bitmap.recycle()
            }
        }
        if (!success) {
            AppLogger.log("Library", "Export failed for ${imageFile.name}")
        }
        return success
    }

    private fun hasExportableTranslation(translation: TranslationResult?): Boolean {
        return translation != null && translation.bubbles.any { it.text.isNotBlank() }
    }

    private fun canPassthroughOriginal(imageFile: File, spec: ExportSpec): Boolean {
        if (!imageFile.isFile || !imageFile.exists()) return false
        if (ImageFileSupport.isAvifFile(imageFile.name)) return false
        val sourceName = imageFile.name.lowercase()
        val targetName = spec.displayName.lowercase()
        if (sourceName != targetName) return false
        val ext = sourceName.substringAfterLast('.', "")
        return ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "webp"
    }

    private data class CbzExportResult(
        val success: Boolean,
        val pathHint: String?
    )

    private data class PreparedCbzEntry(
        val index: Int,
        val entryName: String,
        val tempFile: File
    )

    private suspend fun exportCbzWithBubbles(
        context: Context,
        folder: File,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        onProgress: suspend (Int) -> Unit
    ): CbzExportResult {
        val preparedEntries = prepareCbzEntries(
            context = context,
            folderName = folder.name,
            images = images,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            onProgress = onProgress
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportCbzToDocumentTree(
                context = context,
                folder = folder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportCbzToLegacyStorage(
                folder = folder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportPdfWithBubbles(
        context: Context,
        folder: File,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        exportTreeUri: Uri?,
        onProgress: suspend (Int) -> Unit
    ): CbzExportResult {
        val preparedEntries = prepareCbzEntries(
            context = context,
            folderName = folder.name,
            images = images,
            verticalLayoutEnabled = verticalLayoutEnabled,
            exportThreads = exportThreads,
            onProgress = onProgress
        ) ?: return CbzExportResult(success = false, pathHint = null)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && exportTreeUri != null) {
            exportPdfToDocumentTree(
                context = context,
                folder = folder,
                exportTreeUri = exportTreeUri,
                preparedEntries = preparedEntries
            )
        } else {
            exportPdfToLegacyStorage(
                folder = folder,
                preparedEntries = preparedEntries
            )
        }
    }

    private suspend fun exportCbzToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val cbzName = storageBackend.resolveUniqueCbzName(root, folder.name)
        val pathHint = "${storageBackend.buildExportRootPathHint(exportTreeUri)}/$cbzName"

        return try {
            val success = storageBackend.writeDocumentFileTransaction(
                exportDir = root,
                finalName = cbzName,
                mimeType = "application/vnd.comicbook+zip",
                replaceExisting = false
            ) { stream ->
                ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                    for (entry in preparedEntries.sortedBy { it.index }) {
                        zip.putNextEntry(ZipEntry(entry.entryName))
                        FileInputStream(entry.tempFile).use { input ->
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }
                }
                true
            }
            CbzExportResult(success = success, pathHint = pathHint.takeIf { success })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export CBZ failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportCbzToLegacyStorage(
        folder: File,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }

        return try {
            val target = storageBackend.writeLegacyExportTransaction(root, "${folder.name}.cbz") { stream ->
                ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                    for (entry in preparedEntries.sortedBy { it.index }) {
                        zip.putNextEntry(ZipEntry(entry.entryName))
                        FileInputStream(entry.tempFile).use { input ->
                            input.copyTo(zip)
                        }
                        zip.closeEntry()
                    }
                }
                true
            }
            CbzExportResult(
                success = target != null,
                pathHint = target?.let { "/Documents/manga-translate/${it.name}" }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export CBZ failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportPdfToDocumentTree(
        context: Context,
        folder: File,
        exportTreeUri: Uri,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = DocumentFile.fromTreeUri(context, exportTreeUri)
        if (root == null || !root.canWrite()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }
        val pdfName = storageBackend.resolveUniquePdfName(root, folder.name)
        val pathHint = "${storageBackend.buildExportRootPathHint(exportTreeUri)}/$pdfName"

        return try {
            val success = storageBackend.writeDocumentFileTransaction(
                exportDir = root,
                finalName = pdfName,
                mimeType = "application/pdf",
                replaceExisting = false
            ) { stream ->
                val orderedImages = preparedEntries.sortedBy { it.index }.map { it.tempFile }
                PdfImageCodec.writeImagesToPdf(orderedImages, stream)
            }
            CbzExportResult(success = success, pathHint = pathHint.takeIf { success })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun exportPdfToLegacyStorage(
        folder: File,
        preparedEntries: List<PreparedCbzEntry>
    ): CbzExportResult {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "manga-translate"
        )
        if (!root.exists() && !root.mkdirs()) {
            cleanupPreparedCbzEntries(preparedEntries)
            return CbzExportResult(success = false, pathHint = null)
        }

        return try {
            val target = storageBackend.writeLegacyExportTransaction(root, "${folder.name}.pdf") { stream ->
                val orderedImages = preparedEntries.sortedBy { it.index }.map { it.tempFile }
                PdfImageCodec.writeImagesToPdf(orderedImages, stream)
            }
            CbzExportResult(
                success = target != null,
                pathHint = target?.let { "/Documents/manga-translate/${it.name}" }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log("Library", "Export PDF failed: ${folder.name}", e)
            CbzExportResult(success = false, pathHint = null)
        } finally {
            cleanupPreparedCbzEntries(preparedEntries)
        }
    }

    private suspend fun prepareCbzEntries(
        context: Context,
        folderName: String,
        images: List<File>,
        verticalLayoutEnabled: Boolean,
        exportThreads: Int,
        onProgress: suspend (Int) -> Unit
    ): List<PreparedCbzEntry>? {
        val tempDir = File(context.cacheDir, "cbz_export_${System.currentTimeMillis()}_$folderName")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            AppLogger.log("Library", "Create CBZ temp directory failed: ${tempDir.absolutePath}")
            return null
        }

        val semaphore = Semaphore(normalizeExportThreads(exportThreads))
        val renderedCount = AtomicInteger(0)
        val hasFailures = AtomicBoolean(false)
        val entries = MutableList<PreparedCbzEntry?>(images.size) { null }

        return try {
            coroutineScope {
                val tasks = images.mapIndexed { index, image ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val prepared = renderImageToTempFile(
                                context = context,
                                imageFile = image,
                                verticalLayoutEnabled = verticalLayoutEnabled,
                                tempDir = tempDir,
                                index = index
                            )
                            if (prepared == null) {
                                hasFailures.set(true)
                            } else {
                                entries[index] = prepared
                            }
                            onProgress(renderedCount.incrementAndGet())
                        }
                    }
                }
                tasks.awaitAll()
            }
            if (hasFailures.get() || entries.any { it == null }) {
                null
            } else {
                entries.filterNotNull()
            }
        } finally {
            if (hasFailures.get() || entries.any { it == null }) {
                runCatching { tempDir.deleteRecursively() }
            }
        }
    }

    private suspend fun renderImageToTempFile(
        context: Context,
        imageFile: File,
        verticalLayoutEnabled: Boolean,
        tempDir: File,
        index: Int
    ): PreparedCbzEntry? {
        val translation = translationStore.load(imageFile)
        val hasText = hasExportableTranslation(translation)
        val spec = storageBackend.resolveExportSpec(imageFile.name)
        val tempFile = File(tempDir, "entry_$index")
        if (!hasText && canPassthroughOriginal(imageFile, spec)) {
            val success = try {
                imageFile.copyTo(tempFile, overwrite = true)
                true
            } catch (e: Exception) {
                AppLogger.log("Library", "Copy CBZ entry failed: ${imageFile.name}", e)
                false
            }
            if (!success) return null
            return PreparedCbzEntry(index = index, entryName = spec.displayName, tempFile = tempFile)
        }
        val renderer = BubbleRenderer(context)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        var output: Bitmap? = null
        try {
            output = if (hasText && translation != null) {
                renderer.render(bitmap, translation, verticalLayoutEnabled)
            } else {
                bitmap
            }
            val success = try {
                FileOutputStream(tempFile).use { outputStream ->
                    output.compress(spec.format, spec.quality, outputStream)
                }
            } catch (e: Exception) {
                AppLogger.log("Library", "Write CBZ entry failed: ${imageFile.name}", e)
                false
            }
            if (!success) return null
            return PreparedCbzEntry(index = index, entryName = spec.displayName, tempFile = tempFile)
        } finally {
            if (output != null && output !== bitmap) {
                output.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun cleanupPreparedCbzEntries(entries: List<PreparedCbzEntry>) {
        val tempDir = entries.firstOrNull()?.tempFile?.parentFile ?: return
        runCatching { tempDir.deleteRecursively() }
    }

    private fun loadExportThreads(): Int {
        val saved = preferencesGateway.getExportThreads(DEFAULT_EXPORT_THREADS)
        return normalizeExportThreads(saved)
    }

    private fun loadExportFormatDefault(): ExportFormat {
        val saved = preferencesGateway.getExportFormat(null)
        if (!saved.isNullOrBlank()) {
            return runCatching { ExportFormat.valueOf(saved) }.getOrDefault(ExportFormat.IMAGE_DIR)
        }
        return if (preferencesGateway.isLegacyCbzExportEnabled()) {
            ExportFormat.CBZ
        } else {
            ExportFormat.IMAGE_DIR
        }
    }

    private fun normalizeExportThreads(value: Int): Int {
        return value.coerceIn(MIN_EXPORT_THREADS, MAX_EXPORT_THREADS)
    }

    companion object {
        private const val DEFAULT_EXPORT_THREADS = 2
        private const val DEFAULT_EXPORT_WORKER_BYTES = 48L * 1024L * 1024L
        private const val MIN_EXPORT_THREADS = 1
        private const val MAX_EXPORT_THREADS = 16
    }
}
