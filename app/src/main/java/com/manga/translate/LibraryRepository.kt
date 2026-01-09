package com.manga.translate

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

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
}
