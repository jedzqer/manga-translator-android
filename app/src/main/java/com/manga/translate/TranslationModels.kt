package com.manga.translate

import android.graphics.RectF

data class BubbleTranslation(
    val id: Int,
    val rect: RectF,
    val text: String
)

data class TranslationResult(
    val imageName: String,
    val width: Int,
    val height: Int,
    val bubbles: List<BubbleTranslation>
)
