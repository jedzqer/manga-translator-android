package com.manga.translate.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.manga.translate.R
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.AvifBitmapDecoder
import com.manga.translate.platform.DeviceResourcePolicy
import com.manga.translate.platform.DeviceResourceSnapshot
import com.manga.translate.platform.ImageFileSupport
import com.manga.translate.platform.ImportFileException
import com.manga.translate.platform.PdfImageCodec
import com.manga.translate.platform.ResourceAssessment
import com.manga.translate.platform.StorageSpaceChecker
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import kotlinx.coroutines.CancellationException

class LibraryRepository(private val context: Context) {
    private val rootDir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "manga_library"
    )

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        rootDir.listFiles { file ->
            file.isDirectory && file.name.startsWith(STAGING_DIRECTORY_PREFIX)
        }?.forEach(File::deleteRecursively)
    }

    fun listFolders(
        sortField: LibrarySortField = LibrarySortField.TIME,
        ascending: Boolean = false
    ): List<File> {
        val folders = rootDir.listFiles { file ->
            file.isDirectory && !file.name.startsWith(STAGING_DIRECTORY_PREFIX)
        }?.toList().orEmpty()
        return sortFolders(folders, sortField, ascending)
    }

    fun sortFolders(
        folders: List<File>,
        sortField: LibrarySortField,
        ascending: Boolean
    ): List<File> {
        val comparator = when (sortField) {
            LibrarySortField.NAME -> compareBy<File> { it.name.lowercase(Locale.getDefault()) }
            LibrarySortField.TIME -> compareBy<File> { it.lastModified() }
        }
        return if (ascending) {
            folders.sortedWith(comparator)
        } else {
            folders.sortedWith(comparator.reversed())
        }
    }

    fun listChildFolders(folder: File): List<File> {
        val folders = folder.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.toList()
            .orEmpty()
        return folders.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    fun isCollectionFolder(folder: File): Boolean {
        return collectionMarkerFile(folder).exists()
    }

    fun createFolder(name: String): File? {
        val trimmed = sanitizeFolderName(name) ?: return null
        val folder = File(rootDir, trimmed)
        if (folder.exists()) return null
        return if (folder.mkdirs()) folder else null
    }

    fun createCollection(name: String): File? {
        val folder = createFolder(name) ?: return null
        return if (runCatching { collectionMarkerFile(folder).writeText("1") }.isSuccess) {
            folder
        } else {
            folder.deleteRecursively()
            null
        }
    }

    fun createChildFolder(parent: File, name: String): File? {
        val parentFolder = canonicalTopLevelCollection(parent) ?: return null
        val trimmed = sanitizeFolderName(name) ?: return null
        val folder = canonicalDirectChild(File(parentFolder, trimmed), parentFolder) ?: return null
        if (folder.exists()) return null
        return if (folder.mkdirs()) folder else null
    }

    fun beginFolderImport(
        name: String,
        collection: Boolean = false,
        uniqueTarget: Boolean = false
    ): StagedImport? {
        val targetName = sanitizeFolderName(name) ?: return null
        val target = if (uniqueTarget) {
            resolveUniqueFolder(rootDir, targetName)
        } else {
            File(rootDir, targetName).takeUnless(File::exists)
        } ?: return null
        val staging = createStagingDirectory(rootDir) ?: return null
        if (collection && runCatching { collectionMarkerFile(staging).writeText("1") }.isFailure) {
            staging.deleteRecursively()
            return null
        }
        return StagedImport(staging, target)
    }

    fun beginChildChapterImport(parent: File): StagedChildChapterImport? {
        val destinationCollection = canonicalTopLevelCollection(parent) ?: return null
        val staging = createStagingDirectory(rootDir) ?: return null
        if (runCatching { collectionMarkerFile(staging).writeText("1") }.isFailure) {
            staging.deleteRecursively()
            return null
        }
        return StagedChildChapterImport(staging, destinationCollection)
    }

    fun canCreateChildFolder(parent: File, name: String): Boolean {
        val collection = canonicalTopLevelCollection(parent) ?: return false
        val trimmed = sanitizeFolderName(name) ?: return false
        return !File(collection, trimmed).exists()
    }

    fun listImages(folder: File): List<File> {
        val images = folder.listFiles { file ->
            file.isFile && isImageFile(file.name)
        }?.toList().orEmpty()
        return images.sortedWith { first, second ->
            compareFileNamesNaturally(first.name, second.name)
        }
    }

    fun countImages(folder: File): Int = folder.listFiles { file ->
        file.isFile && isImageFile(file.name)
    }?.size ?: 0

    suspend fun addImages(
        folder: File,
        uris: List<Uri>,
        onAvifConversionStarted: suspend () -> Unit = {}
    ): List<File> {
        val destinationFolder = canonicalLibraryFolder(folder) ?: return emptyList()
        if (isCollectionFolder(destinationFolder)) {
            AppLogger.log("LibraryRepo", "Reject adding images into collection ${folder.name}")
            return emptyList()
        }
        val added = ArrayList<File>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val displayName = queryDisplayName(uri)
            val fileName = when {
                displayName == null -> "image_${System.currentTimeMillis()}.jpg"
                else -> sanitizeImportedImageFileName(displayName)
            }
            if (fileName == null) {
                AppLogger.log("LibraryRepo", "Reject unsafe imported image name: $displayName")
                continue
            }
            try {
                val isAvif = ImageFileSupport.isAvifFile(fileName) ||
                    ImageFileSupport.isAvifMimeType(context.contentResolver.getType(uri))
                val imported = importImage(
                    destinationFolder = destinationFolder,
                    sourceName = fileName,
                    isAvif = isAvif,
                    openInput = { context.contentResolver.openInputStream(uri) },
                    onAvifConversionStarted = onAvifConversionStarted
                )
                if (imported != null) added.add(imported)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.log("LibraryRepo", "Failed to copy $fileName", e)
            }
        }
        return added
    }

    internal suspend fun importCbz(
        uri: Uri,
        onAvifConversionStarted: suspend () -> Unit = {},
        riskAlreadyAccepted: Boolean = false,
        confirmMemoryRisk: suspend (ResourceAssessment) -> Boolean = { false }
    ): CbzImportResult? {
        val archiveName = queryDisplayName(uri) ?: "cbz_import_${System.currentTimeMillis()}.cbz"
        val folderName = archiveName.substringBeforeLast('.', archiveName).trim().ifEmpty { "cbz_import" }
        val stagedImport = beginFolderImport(folderName, uniqueTarget = true) ?: return null
        val folder = stagedImport.folder

        val archiveExt = archiveName.substringAfterLast('.', "").lowercase(Locale.US)
        val tempSuffix = if (archiveExt == "zip") ".zip" else ".cbz"
        val tempFile = File(context.cacheDir, "temp_cbz_${System.currentTimeMillis()}$tempSuffix")
        var importedCount = 0
        var committed = false
        val riskGate = ImportRiskGate(
            accepted = riskAlreadyAccepted,
            snapshot = DeviceResourcePolicy.readSnapshot(context),
            confirm = confirmMemoryRisk
        )

        try {
            AppLogger.log("LibraryRepo", "Archive import started: $archiveName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyInput(
                        input = input,
                        output = output,
                        maxBytes = Long.MAX_VALUE,
                        spaceDirectory = context.cacheDir
                    )
                }
            } ?: run {
                AppLogger.log("LibraryRepo", "Archive import failed: cannot open input stream")
                return CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = R.string.cbz_import_cannot_read
                )
            }
            
            AppLogger.log("LibraryRepo", "Archive copied to temp file: ${tempFile.length()} bytes")

            ZipFile(tempFile).use { zipFile ->
                val headers = zipFile.fileHeaders.orEmpty()
                AppLogger.log("LibraryRepo", "Archive total entries: ${headers.size}")
                val archiveStats = validateArchive(headers)
                if (!riskGate.allow(archiveStats.totalUncompressedBytes)) {
                    throw ImportCancelledByUserException()
                }

                for (header in headers) {
                    currentCoroutineContext().ensureActive()
                    if (header.isDirectory) continue

                    val entryName = extractImportImageName(header.fileName)
                    if (entryName == null) continue

                    try {
                        importImage(
                            destinationFolder = folder,
                            sourceName = entryName,
                            isAvif = ImageFileSupport.isAvifFile(entryName),
                            openInput = { zipFile.getInputStream(header) },
                            maxInputBytes = header.uncompressedSize,
                            onAvifConversionStarted = onAvifConversionStarted,
                            riskGate = riskGate
                        ) ?: continue
                        importedCount += 1
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is ImportFileException) throw e
                        logArchiveEntryImportFailure(header, e)
                    }
                }
            }
            AppLogger.log("LibraryRepo", "Archive import completed: $importedCount images")
            if (importedCount == 0) {
                return CbzImportResult(folder = null, importedCount = 0)
            }
            val committedFolder = stagedImport.commit() ?: return CbzImportResult(
                folder = null,
                importedCount = 0,
                errorMessageRes = R.string.cbz_import_failed_unknown
            )
            committed = true
            return CbzImportResult(folder = committedFolder, importedCount = importedCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            AppLogger.log("LibraryRepo", "Archive import ran out of memory: $archiveName", e)
            return CbzImportResult(folder = null, importedCount = 0, outOfMemory = true)
        } catch (e: Exception) {
            AppLogger.log("LibraryRepo", "Archive import failed: $archiveName", e)
            return when (e) {
                is ImportFileException -> CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = e.messageRes
                )
                is ZipException -> CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = R.string.cbz_import_invalid_archive
                )
                is IOException -> CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = R.string.cbz_import_cannot_read
                )
                else -> CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = R.string.cbz_import_failed_unknown
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            if (!committed) {
                stagedImport.discard()
            }
        }
    }

    internal suspend fun importPdf(
        uri: Uri,
        importPlan: PdfImageCodec.PdfImportPlan? = null
    ): CbzImportResult? {
        val pdfName = queryDisplayName(uri) ?: "pdf_import_${System.currentTimeMillis()}.pdf"
        val folderName = pdfName.substringBeforeLast('.', pdfName).trim().ifEmpty { "pdf_import" }
        val stagedImport = beginFolderImport(folderName, uniqueTarget = true) ?: return null
        var committed = false
        return try {
            val importedCount = PdfImageCodec.renderPdfToImages(
                context = context,
                contentResolver = context.contentResolver,
                uri = uri,
                outputDir = stagedImport.folder,
                importPlan = importPlan
            )
            if (importedCount <= 0) {
                CbzImportResult(folder = null, importedCount = 0)
            } else {
                val committedFolder = stagedImport.commit() ?: return CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = R.string.pdf_import_failed_unknown
                )
                committed = true
                CbzImportResult(folder = committedFolder, importedCount = importedCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            AppLogger.log("LibraryRepo", "PDF import ran out of memory: $pdfName", e)
            CbzImportResult(folder = null, importedCount = 0, outOfMemory = true)
        } catch (e: Exception) {
            AppLogger.log("LibraryRepo", "PDF import failed: $pdfName", e)
            when {
                e is ImportFileException -> CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = e.messageRes
                )
                e is IOException || e is SecurityException -> CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = R.string.pdf_import_cannot_open
                )
                else -> CbzImportResult(
                    folder = null,
                    importedCount = 0,
                    errorMessageRes = R.string.pdf_import_failed_unknown
                )
            }
        } finally {
            if (!committed) {
                stagedImport.discard()
            }
        }
    }

    fun deleteFolder(folder: File): Boolean {
        val libraryFolder = canonicalLibraryFolder(folder) ?: return false
        return libraryFolder.deleteRecursively()
    }

    fun renameFolder(folder: File, newName: String): File? {
        val libraryFolder = canonicalLibraryFolder(folder) ?: return null
        val trimmed = sanitizeFolderName(newName) ?: return null
        if (trimmed == libraryFolder.name) return libraryFolder
        val parent = libraryFolder.parentFile ?: return null
        val target = File(parent, trimmed)
        if (target.exists()) return null
        val canonicalTarget = canonicalDirectChild(target, parent) ?: return null
        return if (libraryFolder.renameTo(canonicalTarget)) canonicalTarget else null
    }

    fun moveFolderToCollection(
        folder: File,
        collection: File,
        uniqueTarget: Boolean = false
    ): File? {
        val sourceFolder = canonicalTopLevelFolder(folder) ?: return null
        val collectionFolder = canonicalTopLevelCollection(collection) ?: return null
        if (isCollectionFolder(sourceFolder)) return null
        if (sourceFolder == collectionFolder) return null
        val target = if (uniqueTarget) {
            resolveUniqueFolder(collectionFolder, sourceFolder.name)
        } else {
            File(collectionFolder, sourceFolder.name).takeUnless(File::exists)
        } ?: return null
        val canonicalTarget = canonicalDirectChild(target, collectionFolder) ?: return null
        return if (sourceFolder.renameTo(canonicalTarget)) canonicalTarget else null
    }

    fun resolveSettingsFolder(folder: File): File {
        val parent = folder.parentFile
        return if (
            parent != null &&
            parent.exists() &&
            parent.isDirectory &&
            parent.absolutePath != rootDir.absolutePath &&
            isCollectionFolder(parent)
        ) {
            parent
        } else {
            folder
        }
    }

    private fun isImageFile(name: String): Boolean {
        return ImageFileSupport.isSupportedSourceImageFileName(name)
    }

    private suspend fun importImage(
        destinationFolder: File,
        sourceName: String,
        isAvif: Boolean,
        openInput: () -> InputStream?,
        maxInputBytes: Long = Long.MAX_VALUE,
        onAvifConversionStarted: suspend () -> Unit,
        riskGate: ImportRiskGate? = null
    ): File? {
        val outputName = if (isAvif) {
            ImageFileSupport.resolveImportedAvifOutputName(sourceName)
        } else {
            sourceName
        }
        val destination = resolveUniqueFile(destinationFolder, outputName)
        if (destination == null) {
            AppLogger.log("LibraryRepo", "Reject image target outside library: $outputName")
            return null
        }
        if (!isAvif) {
            val input = openInput() ?: return null
            return try {
                input.use { source ->
                    FileOutputStream(destination).use { output ->
                        copyInput(source, output, maxInputBytes, destinationFolder)
                    }
                }
                currentCoroutineContext().ensureActive()
                destination
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        }

        val sourceTemp = File(context.cacheDir, "avif_import_${UUID.randomUUID()}.avif")
        val outputTemp = File(destinationFolder, ".avif_import_${UUID.randomUUID()}.tmp")
        return try {
            val input = openInput() ?: return null
            input.use { source ->
                FileOutputStream(sourceTemp).use { output ->
                    copyInput(source, output, maxInputBytes, context.cacheDir)
                }
            }
            currentCoroutineContext().ensureActive()
            if (!AvifBitmapDecoder.convertToPng(
                    source = sourceTemp,
                    destination = outputTemp,
                    propagateOutOfMemory = riskGate != null
                ) { size ->
                    val bitmapBytes = DeviceResourcePolicy.estimateBitmapBytes(size.width, size.height)
                    val estimatedPeakBytes = DeviceResourcePolicy.saturatingAdd(
                        bitmapBytes,
                        sourceTemp.length()
                    )
                    if (riskGate != null && !riskGate.allow(estimatedPeakBytes)) {
                        throw ImportCancelledByUserException()
                    }
                    onAvifConversionStarted()
                    currentCoroutineContext().ensureActive()
                }
            ) {
                AppLogger.log("LibraryRepo", "Failed to convert AVIF image: $sourceName")
                null
            } else if (!outputTemp.renameTo(destination)) {
                AppLogger.log("LibraryRepo", "Failed to commit converted AVIF image: $outputName")
                null
            } else {
                currentCoroutineContext().ensureActive()
                destination
            }
        } finally {
            sourceTemp.delete()
            outputTemp.delete()
        }
    }

    private fun resolveUniqueFile(folder: File, fileName: String): File? {
        val destinationFolder = canonicalImageFolder(folder) ?: return null
        if (sanitizeImportedImageFileName(fileName) == null) return null
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var candidate = canonicalDirectChild(File(destinationFolder, fileName), destinationFolder)
            ?: return null
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = canonicalDirectChild(
                File(destinationFolder, "${base}_$index$suffix"),
                destinationFolder
            ) ?: return null
            index += 1
        }
        return candidate
    }

    private fun canonicalImageFolder(folder: File): File? {
        val libraryFolder = canonicalLibraryFolder(folder) ?: return null
        return libraryFolder.takeUnless(::isCollectionFolder)
    }

    private fun canonicalTopLevelCollection(folder: File): File? {
        val libraryFolder = canonicalTopLevelFolder(folder) ?: return null
        return libraryFolder.takeIf(::isCollectionFolder)
    }

    private fun canonicalTopLevelFolder(folder: File): File? {
        val canonicalFolder = canonicalLibraryFolder(folder) ?: return null
        return canonicalFolder.takeIf { canonicalParent(it) == canonicalRootDir() }
    }

    private fun canonicalLibraryFolder(folder: File): File? {
        val canonicalFolder = canonicalFile(folder) ?: return null
        if (!canonicalFolder.exists() || !canonicalFolder.isDirectory) return null
        if (!isInsideRoot(canonicalFolder)) return null

        val parent = canonicalParent(canonicalFolder) ?: return null
        return when {
            parent == canonicalRootDir() -> canonicalFolder
            canonicalParent(parent) == canonicalRootDir() && isCollectionFolder(parent) -> canonicalFolder
            else -> null
        }
    }

    private fun canonicalDirectChild(file: File, parent: File): File? {
        val canonicalCandidate = canonicalFile(file) ?: return null
        val canonicalParent = canonicalFile(parent) ?: return null
        return canonicalCandidate.takeIf {
            canonicalParent(it) == canonicalParent && isInsideRoot(it)
        }
    }

    private fun canonicalRootDir(): File = rootDir.canonicalFile

    private fun canonicalParent(file: File): File? = file.parentFile?.let(::canonicalFile)

    private fun canonicalFile(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    private fun isInsideRoot(file: File): Boolean {
        val rootPath = canonicalRootDir().path
        return file.path.startsWith("$rootPath${File.separator}")
    }

    private suspend fun copyInput(
        input: InputStream,
        output: FileOutputStream,
        maxBytes: Long,
        spaceDirectory: File
    ) {
        var copied = 0L
        var nextSpaceCheckAt = 0L
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return
            if (copied > maxBytes - read) {
                throw ImportFileException(R.string.cbz_import_entry_too_large)
            }
            if (copied >= nextSpaceCheckAt) {
                ensureRemainingSpace(spaceDirectory, SPACE_CHECK_INTERVAL_BYTES)
                nextSpaceCheckAt = copied + SPACE_CHECK_INTERVAL_BYTES
            }
            output.write(buffer, 0, read)
            copied += read
        }
    }

    private fun validateArchive(headers: List<FileHeader>): ArchiveStats {
        if (headers.size > MAX_ARCHIVE_ENTRY_COUNT) {
            throw ImportFileException(R.string.cbz_import_too_many_entries)
        }

        var totalUncompressed = 0L
        var totalCompressed = 0L
        for (header in headers) {
            if (header.isDirectory) continue
            val uncompressed = header.uncompressedSize
            val compressed = header.compressedSize
            if (uncompressed < 0L || compressed < 0L) {
                throw ImportFileException(R.string.cbz_import_invalid_archive)
            }
            if (uncompressed > 0L && compressed == 0L) {
                throw ImportFileException(R.string.cbz_import_invalid_archive)
            }
            val wholeRatio = if (compressed == 0L) 0L else uncompressed / compressed
            if (
                compressed > 0L && (
                    wholeRatio > MAX_ARCHIVE_COMPRESSION_RATIO ||
                        (wholeRatio == MAX_ARCHIVE_COMPRESSION_RATIO && uncompressed % compressed != 0L)
                    )
            ) {
                throw ImportFileException(R.string.cbz_import_invalid_archive)
            }
            if (totalUncompressed > Long.MAX_VALUE - uncompressed) {
                throw ImportFileException(R.string.cbz_import_invalid_archive)
            }
            totalUncompressed += uncompressed
            if (totalCompressed > Long.MAX_VALUE - compressed) {
                throw ImportFileException(R.string.cbz_import_invalid_archive)
            }
            totalCompressed += compressed
        }
        ensureRemainingSpace(rootDir, totalUncompressed)
        AppLogger.log(
            "LibraryRepo",
            "Archive validated: $totalUncompressed uncompressed bytes, $totalCompressed compressed bytes"
        )
        return ArchiveStats(totalUncompressedBytes = totalUncompressed)
    }

    private fun ensureRemainingSpace(directory: File, requiredBytes: Long = 0L) {
        if (!StorageSpaceChecker.hasSpaceFor(
                context = context,
                directory = directory,
                requiredBytes = requiredBytes,
                reserveBytes = MINIMUM_FREE_SPACE_BYTES
            )
        ) {
            throw ImportFileException(R.string.import_storage_insufficient)
        }
    }

    private fun createStagingDirectory(parent: File): File? {
        repeat(MAX_STAGING_NAME_ATTEMPTS) {
            val folder = File(parent, "$STAGING_DIRECTORY_PREFIX${UUID.randomUUID()}")
            if (folder.mkdirs()) return folder
        }
        return null
    }

    private fun resolveUniqueFolder(parent: File, baseName: String): File? {
        var index = 0
        while (index < Int.MAX_VALUE) {
            val name = if (index == 0) baseName else "${baseName}_$index"
            val candidate = File(parent, name)
            if (!candidate.exists()) return candidate
            index += 1
        }
        return null
    }

    inner class StagedImport internal constructor(
        val folder: File,
        private val target: File
    ) {
        fun commit(): File? {
            if (!folder.exists() || target.exists()) return null
            return target.takeIf { folder.renameTo(it) }
        }

        fun discard() {
            folder.deleteRecursively()
        }
    }

    inner class StagedChildChapterImport internal constructor(
        val folder: File,
        private val destinationCollection: File
    ) {
        fun commit(): List<File>? {
            val chapters = listChildFolders(folder)
            if (chapters.any { File(destinationCollection, it.name).exists() }) return null
            val committed = ArrayList<Pair<File, File>>()
            for (chapter in chapters) {
                val destination = File(destinationCollection, chapter.name)
                if (!chapter.renameTo(destination)) {
                    committed.asReversed().forEach { (source, target) -> target.renameTo(source) }
                    return null
                }
                committed += chapter to destination
            }
            folder.deleteRecursively()
            return committed.map { it.second }
        }

        fun discard() {
            folder.deleteRecursively()
        }
    }

    private fun collectionMarkerFile(folder: File): File {
        return File(folder, COLLECTION_MARKER_FILE_NAME)
    }

    private fun sanitizeFolderName(name: String): String? {
        val trimmed = name.trim().replace("/", "_").replace("\\", "_")
        return trimmed.takeIf { it.isNotEmpty() && !it.contains("..") }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    data class CbzImportResult(
        val folder: File?,
        val importedCount: Int,
        val outOfMemory: Boolean = false,
        @param:androidx.annotation.StringRes val errorMessageRes: Int? = null
    )

    private data class ArchiveStats(val totalUncompressedBytes: Long)

    private class ImportRiskGate(
        accepted: Boolean,
        private val snapshot: DeviceResourceSnapshot,
        private val confirm: suspend (ResourceAssessment) -> Boolean
    ) {
        private var accepted = accepted

        suspend fun allow(estimatedBytes: Long): Boolean {
            if (accepted) return true
            val assessment = DeviceResourcePolicy.assessImport(snapshot, estimatedBytes)
            if (!assessment.shouldWarn) return true
            accepted = confirm(assessment)
            return accepted
        }
    }

    companion object {
        private const val COLLECTION_MARKER_FILE_NAME = ".folder-collection"
        private const val STAGING_DIRECTORY_PREFIX = ".import-staging-"
        private const val MAX_STAGING_NAME_ATTEMPTS = 10
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val SPACE_CHECK_INTERVAL_BYTES = 8L * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRY_COUNT = 30_000
        private const val MAX_ARCHIVE_COMPRESSION_RATIO = 150L
        private const val MINIMUM_FREE_SPACE_BYTES = 100L * 1024 * 1024
        private val CONTROL_CHARS_REGEX = Regex("[\\u0000-\\u001F]")

        internal fun sanitizeImportedImageFileName(displayName: String): String? {
            return displayName.takeIf { name ->
                name.isNotBlank() &&
                    name != "." &&
                    name != ".." &&
                    '/' !in name &&
                    '\\' !in name &&
                    !CONTROL_CHARS_REGEX.containsMatchIn(name)
            }
        }

        internal fun extractImportImageName(entryName: String?): String? {
            val normalized = entryName
                ?.replace('\\', '/')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val fileName = normalized.substringAfterLast('/').trim()
            if (fileName.isEmpty() || fileName == "." || fileName == "..") {
                return null
            }
            val sanitized = fileName.replace(CONTROL_CHARS_REGEX, "_")
            return if (ImageFileSupport.isSupportedImportImageFileName(sanitized)) {
                sanitized
            } else {
                null
            }
        }

        internal fun compareFileNamesNaturally(first: String, second: String): Int {
            var firstIndex = 0
            var secondIndex = 0

            while (firstIndex < first.length && secondIndex < second.length) {
                val firstChar = first[firstIndex]
                val secondChar = second[secondIndex]
                val firstIsDigit = firstChar.isDigit()
                val secondIsDigit = secondChar.isDigit()

                if (firstIsDigit && secondIsDigit) {
                    val firstEnd = first.consumeDigits(firstIndex)
                    val secondEnd = second.consumeDigits(secondIndex)
                    val numberComparison = compareNumericChunks(
                        first.substring(firstIndex, firstEnd),
                        second.substring(secondIndex, secondEnd)
                    )
                    if (numberComparison != 0) return numberComparison
                    firstIndex = firstEnd
                    secondIndex = secondEnd
                    continue
                }

                val charComparison = firstChar.lowercaseChar().compareTo(secondChar.lowercaseChar())
                if (charComparison != 0) return charComparison

                firstIndex += 1
                secondIndex += 1
            }

            return first.length.compareTo(second.length)
        }

        private fun String.consumeDigits(startIndex: Int): Int {
            var index = startIndex
            while (index < length && this[index].isDigit()) {
                index += 1
            }
            return index
        }

        private fun compareNumericChunks(first: String, second: String): Int {
            val normalizedFirst = first.trimStart('0').ifEmpty { "0" }
            val normalizedSecond = second.trimStart('0').ifEmpty { "0" }

            val lengthComparison = normalizedFirst.length.compareTo(normalizedSecond.length)
            if (lengthComparison != 0) return lengthComparison

            val valueComparison = normalizedFirst.compareTo(normalizedSecond)
            if (valueComparison != 0) return valueComparison

            return first.length.compareTo(second.length)
        }
    }

    private fun logArchiveEntryImportFailure(header: FileHeader, error: Exception) {
        AppLogger.log("LibraryRepo", "Archive entry import failed: ${header.fileName}", error)
    }

    private class ImportCancelledByUserException : CancellationException("Import cancelled by user")
}
