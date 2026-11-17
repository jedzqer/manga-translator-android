package com.manga.translate.model

import com.manga.translate.R
import java.io.File

data class FolderItem(
    val folder: File,
    val imageCount: Int,
    val chapterCount: Int = 0,
    val isCollection: Boolean = false,
    val status: FolderStatus = FolderStatus.UNTRANSLATED,
    val customTags: List<String> = emptyList(),
    val statsLoaded: Boolean = true,
    val statusLoaded: Boolean = true
)

data class ImageItem(
    val file: File,
    val translated: Boolean,
    val statusLoaded: Boolean = true
)

enum class FolderStatus(val labelRes: Int) {
    TRANSLATED(R.string.image_translated),
    UNTRANSLATED(R.string.image_not_translated)
}
