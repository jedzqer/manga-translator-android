package com.manga.translate.library

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.FolderStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.platform.AppLogger
import java.io.File

internal class LibraryPreferencesGateway(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val repository: LibraryRepository
) {
    /** Null follows the global setting; an empty string is an explicit local style. */
    fun getTranslationStyle(folder: File): String? =
        prefs.getString(translationStyleKeyPrefix + settingsFolder(folder).absolutePath, null)

    fun setTranslationStyle(folder: File, style: String?) {
        prefs.edit {
            val key = translationStyleKeyPrefix + settingsFolder(folder).absolutePath
            if (style == null) remove(key) else putString(key, style.trim())
        }
    }

    fun resolveTranslationStyle(folder: File, globalStyle: String): String =
        getTranslationStyle(folder) ?: globalStyle

    fun isFullTranslateEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            fullTranslateKeyPrefix + settingsFolder(folder).absolutePath,
            true
        )
    }

    fun isGlossaryProcessingEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            glossaryProcessingKeyPrefix + settingsFolder(folder).absolutePath,
            true
        )
    }

    fun setFullTranslateEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {
            putBoolean(fullTranslateKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun setGlossaryProcessingEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {
            putBoolean(glossaryProcessingKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun getTranslationLanguage(folder: File): TranslationLanguage {
        val value = prefs.getString(languageKeyPrefix + settingsFolder(folder).absolutePath, null)
        return TranslationLanguage.fromPref(value)
    }

    fun getExportThreads(default: Int = 2): Int =
        prefs.getInt("export_threads", default)

    fun getExportFormat(default: String? = null): String? =
        prefs.getString("export_format", default)

    fun isLegacyCbzExportEnabled(): Boolean = prefs.getBoolean("export_as_cbz", false)

    fun setExportPreferences(threads: Int, format: String) {
        prefs.edit { putInt("export_threads", threads); putString("export_format", format) }
    }

    fun isVlDirectTranslateEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            vlDirectTranslateKeyPrefix + settingsFolder(folder).absolutePath,
            false
        )
    }

    fun getReadingMode(folder: File): FolderReadingMode {
        val value = prefs.getString(
            readingModeKeyPrefix + settingsFolder(folder).absolutePath,
            null
        )
        return FolderReadingMode.fromPref(value)
    }

    fun hasStoredReadingMode(folder: File): Boolean {
        return prefs.contains(readingModeKeyPrefix + settingsFolder(folder).absolutePath)
    }

    fun setTranslationLanguage(folder: File, language: TranslationLanguage) {
        prefs.edit() {
            putString(languageKeyPrefix + settingsFolder(folder).absolutePath, language.prefValue)
        }
    }

    fun setVlDirectTranslateEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {
            putBoolean(vlDirectTranslateKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun isVlFullVisualEnabled(folder: File): Boolean = prefs.getBoolean(
        vlFullVisualKeyPrefix + settingsFolder(folder).absolutePath,
        false
    )

    fun setVlFullVisualEnabled(folder: File, enabled: Boolean) {
        prefs.edit { putBoolean(vlFullVisualKeyPrefix + settingsFolder(folder).absolutePath, enabled) }
    }

    fun isOnlyTranslateBubblesEnabled(folder: File): Boolean {
        return prefs.getBoolean(
            onlyTranslateBubblesKeyPrefix + settingsFolder(folder).absolutePath,
            false
        )
    }

    fun setOnlyTranslateBubblesEnabled(folder: File, enabled: Boolean) {
        prefs.edit {
            putBoolean(onlyTranslateBubblesKeyPrefix + settingsFolder(folder).absolutePath, enabled)
        }
    }

    fun setReadingMode(folder: File, mode: FolderReadingMode) {
        prefs.edit() {
            putString(readingModeKeyPrefix + settingsFolder(folder).absolutePath, mode.prefValue)
        }
    }

    fun getFolderTags(folder: File): Set<String> {
        return prefs.getStringSet(folderTagsKeyPrefix + folder.absolutePath, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun setFolderTags(folder: File, tags: Set<String>) {
        prefs.edit {
            val key = folderTagsKeyPrefix + folder.absolutePath
            if (tags.isEmpty()) {
                remove(key)
            } else {
                putStringSet(key, tags.toSet())
            }
        }
    }

    fun getCachedFolderStatus(folder: File): FolderStatus? {
        return prefs.getString(folderStatusKeyPrefix + folder.absolutePath, null)
            ?.let { value -> FolderStatus.entries.firstOrNull { it.name == value } }
    }

    fun setCachedFolderStatus(folder: File, status: FolderStatus) {
        prefs.edit { putString(folderStatusKeyPrefix + folder.absolutePath, status.name) }
    }

    fun getCachedFolderStats(folder: File): CachedFolderStats? {
        val imageCountKey = folderImageCountKeyPrefix + folder.absolutePath
        val chapterCountKey = folderChapterCountKeyPrefix + folder.absolutePath
        if (!prefs.contains(imageCountKey) || !prefs.contains(chapterCountKey)) return null
        return CachedFolderStats(
            imageCount = prefs.getInt(imageCountKey, 0).coerceAtLeast(0),
            chapterCount = prefs.getInt(chapterCountKey, 0).coerceAtLeast(0)
        )
    }

    fun setCachedFolderStats(folder: File, imageCount: Int, chapterCount: Int = 0) {
        prefs.edit {
            putInt(folderImageCountKeyPrefix + folder.absolutePath, imageCount.coerceAtLeast(0))
            putInt(folderChapterCountKeyPrefix + folder.absolutePath, chapterCount.coerceAtLeast(0))
        }
    }

    fun invalidateCachedFolderStats(folder: File) {
        prefs.edit {
            remove(folderImageCountKeyPrefix + folder.absolutePath)
            remove(folderChapterCountKeyPrefix + folder.absolutePath)
        }
    }

    fun autoDetectAndSetReadingMode(folder: File, importedImages: List<File>): FolderReadingMode? {
        if (importedImages.isEmpty()) return null
        if (hasStoredReadingMode(folder)) return null
        val detectedMode = detectReadingMode(importedImages)
        setReadingMode(folder, detectedMode)
        AppLogger.log(
            "Library",
            "Auto-detected reading mode for ${settingsFolder(folder).name}: ${detectedMode.prefValue}"
        )
        return detectedMode
    }

    fun migrateFolderSettings(from: File, to: File) {
        val oldPath = settingsFolder(from).absolutePath
        val newPath = settingsFolder(to).absolutePath

        prefs.edit {
            if (oldPath != newPath) {
                settingsKeyPrefixes.forEach { prefix ->
                    val oldKey = prefix + oldPath
                    if (!prefs.contains(oldKey)) return@forEach
                    val newKey = prefix + newPath
                    when (val value = prefs.all[oldKey]) {
                        is Boolean -> putBoolean(newKey, value)
                        is String -> putString(newKey, value)
                        is Int -> putInt(newKey, value)
                        is Long -> putLong(newKey, value)
                        is Float -> putFloat(newKey, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            putStringSet(newKey, value as Set<String>)
                        }
                    }
                    remove(oldKey)
                }
            }
            migrateFolderTags(from, to)
            migrateFolderStatuses(from, to)
            migrateFolderStats(from, to)
        }
    }

    fun clearFolderTreeSettings(folder: File) {
        val resolved = settingsFolder(folder)
        val folderPath = folder.absolutePath
        prefs.edit {
            if (resolved.absolutePath == folderPath) {
                settingsKeyPrefixes.forEach { prefix ->
                    remove(prefix + resolved.absolutePath)
                }
            }
            prefs.all.keys
                .filter { key ->
                    isFolderTagKeyInTree(key, folderPath) ||
                        isFolderStatusKeyInTree(key, folderPath) ||
                        isFolderStatsKeyInTree(key, folderPath)
                }
                .forEach(::remove)
        }
    }

    fun clearFolderSettings(folder: File) {
        clearFolderTreeSettings(folder)
    }

    fun getLibrarySortField(): LibrarySortField {
        return LibrarySortField.fromPref(prefs.getString(librarySortFieldKey, null))
    }

    fun setLibrarySortField(field: LibrarySortField) {
        prefs.edit { putString(librarySortFieldKey, field.prefValue) }
    }

    fun isLibrarySortAscending(): Boolean {
        return prefs.getBoolean(librarySortAscendingKey, false)
    }

    fun setLibrarySortAscending(ascending: Boolean) {
        prefs.edit { putBoolean(librarySortAscendingKey, ascending) }
    }

    fun getImportTreeUri(): Uri? {
        return prefs.getString(importTreeKey, null)?.let(Uri::parse)
    }

    fun setImportTreeUri(uri: Uri) {
        prefs.edit() {putString(importTreeKey, uri.toString())}
    }

    fun getExportTreeUri(): Uri? {
        return prefs.getString(exportTreeKey, null)?.let(Uri::parse)
    }

    fun setExportTreeUri(uri: Uri) {
        prefs.edit() {putString(exportTreeKey, uri.toString())}
    }

    fun hasImportPermission(uri: Uri): Boolean {
        val persisted = context
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        return persisted && root?.canRead() == true
    }

    fun hasExportPermission(uri: Uri): Boolean {
        val persisted = context
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        return persisted && root?.canWrite() == true
    }

    fun buildImportInitialUri(): Uri? {
        return getImportTreeUri()?.takeIf(::hasImportPermission)
    }

    fun buildExportInitialUri(): Uri? {
        return try {
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents/manga-translator"
            )
        } catch (_: Exception) {
            try {
                DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Documents"
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun settingsFolder(folder: File): File = repository.resolveSettingsFolder(folder)

    private fun SharedPreferences.Editor.migrateFolderTags(from: File, to: File) {
        val fromPath = from.absolutePath
        val toPath = to.absolutePath
        prefs.all
            .filterKeys { key -> isFolderTagKeyInTree(key, fromPath) }
            .forEach { (key, value) ->
                val suffix = key.removePrefix(folderTagsKeyPrefix + fromPath)
                if (value is Set<*>) {
                    @Suppress("UNCHECKED_CAST")
                    putStringSet(folderTagsKeyPrefix + toPath + suffix, value as Set<String>)
                }
                remove(key)
            }
    }

    private fun SharedPreferences.Editor.migrateFolderStatuses(from: File, to: File) {
        val fromPath = from.absolutePath
        val toPath = to.absolutePath
        prefs.all.keys
            .filter { key -> isFolderStatusKeyInTree(key, fromPath) }
            .forEach { key ->
                val suffix = key.removePrefix(folderStatusKeyPrefix + fromPath)
                val value = prefs.getString(key, null)
                if (value != null) {
                    putString(folderStatusKeyPrefix + toPath + suffix, value)
                }
                remove(key)
            }
    }

    private fun SharedPreferences.Editor.migrateFolderStats(from: File, to: File) {
        val fromPath = from.absolutePath
        val toPath = to.absolutePath
        listOf(folderImageCountKeyPrefix, folderChapterCountKeyPrefix).forEach { keyPrefix ->
            prefs.all.keys
                .filter { key -> isKeyInFolderTree(key, keyPrefix, fromPath) }
                .forEach { key ->
                    val suffix = key.removePrefix(keyPrefix + fromPath)
                    putInt(keyPrefix + toPath + suffix, prefs.getInt(key, 0))
                    remove(key)
                }
        }
    }

    private fun isFolderTagKeyInTree(key: String, folderPath: String): Boolean {
        val prefix = folderTagsKeyPrefix + folderPath
        return key == prefix || key.startsWith("$prefix${File.separator}")
    }

    private fun isFolderStatusKeyInTree(key: String, folderPath: String): Boolean {
        val prefix = folderStatusKeyPrefix + folderPath
        return key == prefix || key.startsWith("$prefix${File.separator}")
    }

    private fun isFolderStatsKeyInTree(key: String, folderPath: String): Boolean {
        return isKeyInFolderTree(key, folderImageCountKeyPrefix, folderPath) ||
            isKeyInFolderTree(key, folderChapterCountKeyPrefix, folderPath)
    }

    private fun isKeyInFolderTree(key: String, keyPrefix: String, folderPath: String): Boolean {
        val prefix = keyPrefix + folderPath
        return key == prefix || key.startsWith("$prefix${File.separator}")
    }

    private fun detectReadingMode(images: List<File>): FolderReadingMode {
        val sampledImages = images.asSequence()
            .filter { it.exists() && it.isFile }
            .take(READING_MODE_SAMPLE_COUNT)
            .toList()
        if (sampledImages.isEmpty()) return FolderReadingMode.STANDARD

        val webtoonLikeCount = sampledImages.count(::isWebtoonLikeImage)
        val requiredMatches = if (sampledImages.size == 1) 1 else (sampledImages.size + 1) / 2
        return if (webtoonLikeCount >= requiredMatches) {
            FolderReadingMode.WEBTOON_SCROLL
        } else {
            FolderReadingMode.STANDARD
        }
    }

    private fun isWebtoonLikeImage(imageFile: File): Boolean {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false
        return height > width && height.toFloat() / width.toFloat() >= WEBTOON_ASPECT_RATIO_THRESHOLD
    }

    private companion object {
        private const val importTreeKey = "ehviewer_tree_uri"
        private const val exportTreeKey = "export_tree_uri"
        private const val librarySortFieldKey = "library_sort_field"
        private const val librarySortAscendingKey = "library_sort_ascending"
        private const val fullTranslateKeyPrefix = "full_translate_enabled_"
        private const val glossaryProcessingKeyPrefix = "glossary_processing_enabled_"
        private const val translationStyleKeyPrefix = "translation_style_"
        private const val languageKeyPrefix = "translation_language_"
        private const val vlDirectTranslateKeyPrefix = "vl_direct_translate_enabled_"
        private const val vlFullVisualKeyPrefix = "vl_full_visual_enabled_"
        private const val onlyTranslateBubblesKeyPrefix = "only_translate_bubbles_enabled_"
        private const val readingModeKeyPrefix = "reading_mode_"
        private const val folderTagsKeyPrefix = "folder_tags_"
        private const val folderStatusKeyPrefix = "folder_status_"
        private const val folderImageCountKeyPrefix = "folder_image_count_"
        private const val folderChapterCountKeyPrefix = "folder_chapter_count_"
        private val settingsKeyPrefixes = listOf(
            fullTranslateKeyPrefix,
            glossaryProcessingKeyPrefix,
            languageKeyPrefix,
            translationStyleKeyPrefix,
            vlDirectTranslateKeyPrefix,
            vlFullVisualKeyPrefix,
            onlyTranslateBubblesKeyPrefix,
            readingModeKeyPrefix,
            folderStatusKeyPrefix,
            folderImageCountKeyPrefix,
            folderChapterCountKeyPrefix
        )
        private const val READING_MODE_SAMPLE_COUNT = 6
        private const val WEBTOON_ASPECT_RATIO_THRESHOLD = 2.4f
    }
}

internal data class CachedFolderStats(
    val imageCount: Int,
    val chapterCount: Int
)

enum class LibrarySortField(val prefValue: String) {
    NAME("name"),
    TIME("time");

    companion object {
        fun fromPref(value: String?): LibrarySortField {
            return entries.firstOrNull { it.prefValue == value } ?: TIME
        }
    }
}
