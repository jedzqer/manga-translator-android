package com.manga.translate

import org.json.JSONObject
import java.io.File

class EmbeddedStateStore {
    fun embeddedDir(folder: File): File {
        return File(folder, EMBEDDED_DIR_NAME)
    }

    fun markerFile(folder: File): File {
        return File(folder, MARKER_FILE_NAME)
    }

    fun listEmbeddedImages(folder: File): List<File> {
        val dir = embeddedDir(folder)
        val images = dir.listFiles { file ->
            file.isFile && isImageFile(file.name)
        }?.toList().orEmpty()
        return images.sortedBy { it.name.lowercase() }
    }

    fun isEmbedded(folder: File): Boolean {
        val marker = markerFile(folder)
        val images = listEmbeddedImages(folder)
        if (!marker.exists()) return false
        if (images.isNotEmpty()) return true

        // Auto-fix inconsistent marker state.
        marker.delete()
        return false
    }

    fun writeEmbeddedState(folder: File, imageCount: Int) {
        val marker = markerFile(folder)
        val json = JSONObject()
            .put("version", STATE_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("imageCount", imageCount)
        marker.writeText(json.toString())
    }

    fun clearEmbeddedState(folder: File) {
        runCatching { markerFile(folder).delete() }
        runCatching { embeddedDir(folder).deleteRecursively() }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp")
    }

    companion object {
        private const val EMBEDDED_DIR_NAME = ".embedded"
        private const val MARKER_FILE_NAME = ".embedded-state.json"
        private const val STATE_VERSION = 1
    }
}
