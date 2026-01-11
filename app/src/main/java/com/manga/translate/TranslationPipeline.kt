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
        glossary: MutableMap<String, String>,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        if (!llmClient.isConfigured()) {
            onProgress(appContext.getString(R.string.missing_api_settings))
            AppLogger.log("Pipeline", "Missing API settings")
            return@withContext null
        }
        val detector = getDetector() ?: return@withContext null
        val ocr = getOcr() ?: return@withContext null
        AppLogger.log("Pipeline", "Translate image ${imageFile.name}")
        val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: run {
                AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
                return@withContext null
            }
        onProgress(appContext.getString(R.string.detecting_bubbles))
        val detections = detector.detect(bitmap)
        AppLogger.log("Pipeline", "Detected ${detections.size} bubbles in ${imageFile.name}")
        if (detections.isEmpty()) {
            return@withContext TranslationResult(imageFile.name, bitmap.width, bitmap.height, emptyList())
        }
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
            val translated = llmClient.translate(text, glossary)
            if (translated != null) {
                if (translated.glossaryUsed.isNotEmpty()) {
                    glossary.putAll(translated.glossaryUsed)
                }
                bubbles.add(BubbleTranslation(bubbleId, det.rect, translated.translation))
            } else {
                bubbles.add(BubbleTranslation(bubbleId, det.rect, text))
            }
        }
        AppLogger.log("Pipeline", "Translation finished for ${imageFile.name}")
        TranslationResult(imageFile.name, bitmap.width, bitmap.height, bubbles)
    }

    suspend fun ocrImage(
        imageFile: File,
        onProgress: (String) -> Unit
    ): PageOcrResult? = withContext(Dispatchers.Default) {
        val detector = getDetector() ?: return@withContext null
        val ocr = getOcr() ?: return@withContext null
        val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: run {
                AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
                return@withContext null
            }
        onProgress(appContext.getString(R.string.detecting_bubbles))
        val detections = detector.detect(bitmap)
        AppLogger.log("Pipeline", "Detected ${detections.size} bubbles in ${imageFile.name}")
        if (detections.isEmpty()) {
            return@withContext PageOcrResult(
                imageFile,
                bitmap.width,
                bitmap.height,
                emptyList()
            )
        }
        val bubbles = ArrayList<OcrBubble>(detections.size)
        for ((bubbleId, det) in detections.withIndex()) {
            val crop = cropBitmap(bitmap, det.rect) ?: continue
            val text = ocr.recognize(crop).trim()
            bubbles.add(OcrBubble(bubbleId, det.rect, text))
        }
        PageOcrResult(imageFile, bitmap.width, bitmap.height, bubbles)
    }

    suspend fun translateFullPage(
        page: PageOcrResult,
        glossary: Map<String, String>,
        promptAsset: String,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val translatable = page.bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            val emptyTranslations = page.bubbles.map {
                BubbleTranslation(it.id, it.rect, "")
            }
            return@withContext TranslationResult(
                page.imageFile.name,
                page.width,
                page.height,
                emptyTranslations
            )
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val pageText = translatable.joinToString("\n") { "<b>${it.text}</b>" }
        val translated = llmClient.translate(pageText, glossary, promptAsset)
        if (translated == null) {
            val fallback = page.bubbles.map { bubble ->
                BubbleTranslation(bubble.id, bubble.rect, bubble.text)
            }
            return@withContext TranslationResult(
                page.imageFile.name,
                page.width,
                page.height,
                fallback
            )
        }
        val translatedSegments = extractTaggedSegments(
            translated.translation,
            translatable.map { it.text }
        )
        val translationMap = HashMap<Int, String>(translatable.size)
        for (i in translatable.indices) {
            translationMap[translatable[i].id] = translatedSegments[i]
        }
        val bubbles = page.bubbles.map { bubble ->
            val text = translationMap[bubble.id] ?: ""
            BubbleTranslation(bubble.id, bubble.rect, text)
        }
        TranslationResult(page.imageFile.name, page.width, page.height, bubbles)
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

    private fun extractTaggedSegments(text: String, fallback: List<String>): List<String> {
        val expected = fallback.size
        val regex = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
        val matches = regex.findAll(text).map { it.groupValues[1].trim() }.toList()
        if (matches.isEmpty()) {
            if (expected == 1) return listOf(text.trim())
            AppLogger.log("Pipeline", "Missing <b> tags in full page translation")
            return List(expected) { "" }
        }
        if (matches.size != expected) {
            AppLogger.log(
                "Pipeline",
                "Translation count mismatch: expected $expected, got ${matches.size}"
            )
        }
        val result = MutableList(expected) { "" }
        val limit = minOf(expected, matches.size)
        for (i in 0 until limit) {
            result[i] = matches[i]
        }
        return result
    }
}

data class OcrBubble(
    val id: Int,
    val rect: RectF,
    val text: String
)

data class PageOcrResult(
    val imageFile: File,
    val width: Int,
    val height: Int,
    val bubbles: List<OcrBubble>
)
