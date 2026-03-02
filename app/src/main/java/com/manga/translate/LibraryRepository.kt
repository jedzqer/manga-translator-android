package com.manga.translate

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipFile

class LibraryRepository(private val context: Context) {
    private val rootDir: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "manga_library"
    )
    private val legacyDir: File = File(context.filesDir, "manga_library")

    init {
        migrateIfNeeded()
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
    }

    fun listFolders(): List<File> {
        val folders = rootDir.listFiles { file -> file.isDirectory }?.toList().orEmpty()
        return folders.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    fun createFolder(name: String): File? {
        val trimmed = name.trim().replace("/", "_").replace("\\", "_")
        if (trimmed.isEmpty() || trimmed.contains("..")) return null
        val folder = File(rootDir, trimmed)
        if (folder.exists()) return null
        return if (folder.mkdirs()) folder else null
    }

    fun listImages(folder: File): List<File> {
        val images = folder.listFiles { file ->
            file.isFile && isImageFile(file.name)
        }?.toList().orEmpty()
        return images.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    fun addImages(folder: File, uris: List<Uri>): List<File> {
        val added = ArrayList<File>()
        for (uri in uris) {
            val fileName = queryDisplayName(uri) ?: "image_${System.currentTimeMillis()}.jpg"
            val dest = resolveUniqueFile(folder, fileName)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                added.add(dest)
            } catch (e: Exception) {
                AppLogger.log("LibraryRepo", "Failed to copy $fileName", e)
            }
        }
        return added
    }

    fun importCbz(uri: Uri): CbzImportResult? {
        val archiveName = queryDisplayName(uri) ?: "cbz_import_${System.currentTimeMillis()}.cbz"
        val folderName = archiveName.substringBeforeLast('.', archiveName).trim().ifEmpty { "cbz_import" }
        val folder = createUniqueFolder(folderName) ?: return null

        val archiveExt = archiveName.substringAfterLast('.', "").lowercase(Locale.US)
        val tempSuffix = if (archiveExt == "zip") ".zip" else ".cbz"
        val tempFile = File(context.cacheDir, "temp_cbz_${System.currentTimeMillis()}$tempSuffix")
        var importedCount = 0
        
        try {
            AppLogger.log("LibraryRepo", "Archive import started: $archiveName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                AppLogger.log("LibraryRepo", "Archive import failed: cannot open input stream")
                folder.deleteRecursively()
                return null
            }
            
            AppLogger.log("LibraryRepo", "Archive copied to temp file: ${tempFile.length()} bytes")

            ZipFile(tempFile).use { zipFile ->
                val entries = zipFile.entries()
                AppLogger.log("LibraryRepo", "Archive total entries: ${zipFile.size()}")
                
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    
                    if (!entry.isDirectory) {
                        val entryName = entry.name
                            .replace('\\', '/')
                            .substringAfterLast('/')
                        
                        AppLogger.log("LibraryRepo", "Archive entry: ${entry.name} -> $entryName")
                        
                        if (entryName.isNotBlank() && isImageFile(entryName)) {
                            val dest = resolveUniqueFile(folder, entryName)
                            zipFile.getInputStream(entry).use { input ->
                                FileOutputStream(dest).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            importedCount += 1
                            AppLogger.log("LibraryRepo", "Archive imported: $entryName (${dest.length()} bytes)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.log("LibraryRepo", "Archive import failed: $archiveName", e)
            folder.deleteRecursively()
            return null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }

        AppLogger.log("LibraryRepo", "Archive import completed: $importedCount images")
        if (importedCount == 0) {
            folder.deleteRecursively()
            return CbzImportResult(folder = null, importedCount = 0)
        }
        return CbzImportResult(folder = folder, importedCount = importedCount)
    }

    fun deleteFolder(folder: File): Boolean {
        if (!folder.exists()) return false
        return folder.deleteRecursively()
    }

    fun renameFolder(folder: File, newName: String): File? {
        if (!folder.exists() || !folder.isDirectory) return null
        val trimmed = newName.trim().replace("/", "_").replace("\\", "_")
        if (trimmed.isEmpty() || trimmed.contains("..")) return null
        if (trimmed == folder.name) return folder
        val target = File(rootDir, trimmed)
        if (target.exists()) return null
        return if (folder.renameTo(target)) target else null
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase(Locale.getDefault())
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp")
    }

    private fun resolveUniqueFile(folder: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var candidate = File(folder, fileName)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(folder, "${base}_$index$suffix")
            index += 1
        }
        return candidate
    }

    private fun createUniqueFolder(baseName: String): File? {
        val sanitized = baseName.trim().replace("/", "_").replace("\\", "_")
        if (sanitized.isEmpty() || sanitized.contains("..")) return null
        var index = 0
        while (true) {
            val candidateName = if (index == 0) sanitized else "${sanitized}_$index"
            val folder = File(rootDir, candidateName)
            if (!folder.exists()) {
                return if (folder.mkdirs()) folder else null
            }
            index += 1
        }
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

    private fun migrateIfNeeded() {
        if (!legacyDir.exists() || legacyDir == rootDir) return
        if (rootDir.exists() && rootDir.listFiles()?.isNotEmpty() == true) return
        try {
            legacyDir.copyRecursively(rootDir, overwrite = false)
        } catch (e: Exception) {
            AppLogger.log("LibraryRepo", "Migration failed", e)
        }
    }

    data class CbzImportResult(
        val folder: File?,
        val importedCount: Int
    )
}
