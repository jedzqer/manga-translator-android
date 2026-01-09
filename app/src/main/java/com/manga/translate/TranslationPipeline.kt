package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranslationPipeline(context: Context) {
    private val appContext = context.applicationContext
    private val llmClient = LlmClient(appContext)
    private val store = TranslationStore()
    private var detector: BubbleDetector? = null
    private var ocr: MangaOcr? = null

    suspend fun translateImage(
        imageFile: File,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        if (!llmClient.isConfigured()) {
            onProgress(appContext.getString(R.string.missing_api_settings))
            AppLogger.log("Pipeline", "Missing API settings")
            return@withContext null
        }
        val detector = getDetector() ?: return@withContext null
        val ocr = getOcr() ?: return@withContext null
        val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: run {
                AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
                return@withContext null
            }
        val detections = detector.detect(bitmap)
        if (detections.isEmpty()) {
            return@withContext TranslationResult(imageFile.name, bitmap.width, bitmap.height, emptyList())
        }
        onProgress(appContext.getString(R.string.ocr_in_progress))
        val bubbles = ArrayList<BubbleTranslation>(detections.size)
        val total = detections.size.coerceAtLeast(1)
        var index = 0
        for ((bubbleId, det) in detections.withIndex()) {
            val crop = cropBitmap(bitmap, det.rect) ?: continue
            val text = ocr.recognize(crop).trim()
            if (text.isBlank()) {
                bubbles.add(BubbleTranslation(bubbleId, det.rect, ""))
                continue
            }
            index += 1
            onProgress(appContext.getString(R.string.translating_progress, index, total))
            val translated = llmClient.translate(text) ?: text
            bubbles.add(BubbleTranslation(bubbleId, det.rect, translated))
        }
        TranslationResult(imageFile.name, bitmap.width, bitmap.height, bubbles)
    }

    fun saveResult(imageFile: File, result: TranslationResult): File {
        return store.save(imageFile, result)
    }

    fun translationFileFor(imageFile: File): File {
        return store.translationFileFor(imageFile)
    }

    private fun getDetector(): BubbleDetector? {
        if (detector != null) return detector
        return try {
            detector = BubbleDetector(appContext)
            detector
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "Failed to init bubble detector", e)
            null
        }
    }

    private fun getOcr(): MangaOcr? {
        if (ocr != null) return ocr
        return try {
            ocr = MangaOcr(appContext)
            ocr
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "Failed to init OCR", e)
            null
        }
    }

    private fun cropBitmap(source: Bitmap, rect: RectF): Bitmap? {
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val right = rect.right.toInt().coerceIn(1, source.width)
        val bottom = rect.bottom.toInt().coerceIn(1, source.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(source, left, top, width, height)
    }
}
