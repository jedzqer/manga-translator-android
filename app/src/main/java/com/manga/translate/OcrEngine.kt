package com.manga.translate

import android.graphics.Bitmap

/**
 * OCR引擎接口，统一不同语言的OCR实现
 */
interface OcrEngine {
    /**
     * 识别图像中的文字
     * @param bitmap 待识别的图像
     * @return 识别出的文字
     */
    fun recognize(bitmap: Bitmap): String
}
