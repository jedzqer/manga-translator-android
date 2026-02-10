package com.manga.translate

import java.io.File

data class FolderItem(
    val folder: File,
    val imageCount: Int
)

data class ImageItem(
    val file: File,
    val translated: Boolean,
    val embedded: Boolean
)
