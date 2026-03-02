package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class TranslationPipeline(context: Context) {
    private val appContext = context.applicationContext
    private val llmClient = LlmClient(appContext)
    private val settingsStore = SettingsStore(appContext)
    private val store = TranslationStore()
    private val ocrStore = OcrStore()
    private var detector: BubbleDetector? = null
    private var ocr: MangaOcr? = null
    private var englishOcr: EnglishOcr? = null
    private var textDetector: TextDetector? = null
    private var englishLineDetector: EnglishLineDetector? = null

    suspend fun translateImage(
        imageFile: File,
        glossary: MutableMap<String, String>,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        if (!llmClient.isConfigured()) {
            onProgress(appContext.getString(R.string.missing_api_settings))
            AppLogger.log("Pipeline", "Missing API settings")
            return@withContext null
        }
        val page = ocrImage(imageFile, forceOcr, language, onProgress) ?: return@withContext null
        AppLogger.log("Pipeline", "Translate image ${imageFile.name}")
        val translatable = page.bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            val emptyTranslations = page.bubbles.map {
                BubbleTranslation(it.id, it.rect, "", it.source)
            }
            return@withContext TranslationResult(
                imageFile.name,
                page.width,
                page.height,
                emptyTranslations
            )
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val pageText = translatable.joinToString("\n") { bubble ->
            val text = normalizeOcrText(bubble.text, language)
            "<b>$text</b>"
        }
        val promptAsset = "llm_prompts.json"
        val translated = llmClient.translate(pageText, glossary, promptAsset)
        if (translated == null) {
            val fallback = page.bubbles.map { bubble ->
                val text = bubble.text.trim()
                BubbleTranslation(bubble.id, bubble.rect, if (text.isBlank()) "" else text, bubble.source)
            }
            return@withContext TranslationResult(
                imageFile.name,
                page.width,
                page.height,
                fallback
            )
        }
        if (translated.glossaryUsed.isNotEmpty()) {
            glossary.putAll(translated.glossaryUsed)
        }
        val translatedSegments = extractTaggedSegments(
            translated.translation,
            translatable.map { it.text },
            onMissingTags = {
                AppLogger.log("Pipeline", "Missing <b> tags in full page translation")
            },
            onCountMismatch = { expected, actual ->
                AppLogger.log(
                    "Pipeline",
                    "Translation count mismatch: expected $expected, got $actual"
                )
            }
        )
        val translationMap = HashMap<Int, String>(translatable.size)
        for (i in translatable.indices) {
            translationMap[translatable[i].id] = translatedSegments[i]
        }
        val bubbles = page.bubbles.map { bubble ->
            val text = translationMap[bubble.id] ?: ""
            BubbleTranslation(bubble.id, bubble.rect, text, bubble.source)
        }
        AppLogger.log("Pipeline", "Translation finished for ${imageFile.name}")
        TranslationResult(imageFile.name, page.width, page.height, bubbles)
    }

    suspend fun ocrImage(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): PageOcrResult? = withContext(Dispatchers.Default) {
        if (!forceOcr) {
            val cached = ocrStore.load(imageFile)
            if (cached != null) {
                AppLogger.log("Pipeline", "Reuse OCR for ${imageFile.name}")
                return@withContext cached
            }
        }
        val detector = getDetector() ?: return@withContext null
        val ocrSettings = settingsStore.loadOcrApiSettings()
        val useLocalOcr = ocrSettings.useLocalOcr
        val ocrEngine: OcrEngine? = if (useLocalOcr) {
            when (language) {
                TranslationLanguage.EN_TO_ZH -> getEnglishOcr()
                TranslationLanguage.JA_TO_ZH -> getOcr()
            }
        } else {
            null
        }
        if (!useLocalOcr && !llmClient.isOcrConfigured()) {
            onProgress(appContext.getString(R.string.missing_ocr_api_settings))
            AppLogger.log("Pipeline", "Missing OCR API settings")
            return@withContext null
        }
        if (useLocalOcr && ocrEngine == null) {
            return@withContext null
        }
        val textDetector = getTextDetector()
        val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: run {
                AppLogger.log("Pipeline", "Failed to decode ${imageFile.name}")
                return@withContext null
            }
        onProgress(appContext.getString(R.string.detecting_bubbles))
        val detections = detector.detect(bitmap)
        AppLogger.log("Pipeline", "Detected ${detections.size} bubbles in ${imageFile.name}")
        val bubbleRects = detections.map { it.rect }
        if (useLocalOcr && language == TranslationLanguage.EN_TO_ZH && ocrEngine is EnglishOcr) {
            val lineDetector = getEnglishLineDetector()
            val bubbles = ArrayList<OcrBubble>(bubbleRects.size)
            if (bubbleRects.isEmpty()) {
                val lineRects = lineDetector?.detectLines(bitmap).orEmpty()
                val lines = recognizeEnglishLines(bitmap, lineRects, ocrEngine)
                for ((index, line) in lines.withIndex()) {
                    bubbles.add(OcrBubble(index, line.rect, line.text, BubbleSource.TEXT_DETECTOR))
                }
            } else {
                for ((bubbleId, rect) in bubbleRects.withIndex()) {
                    val crop = cropBitmap(bitmap, rect) ?: continue
                    val lineRects = lineDetector?.detectLines(crop).orEmpty()
                    val lines = recognizeEnglishLines(crop, lineRects, ocrEngine)
                    val text = if (lines.isEmpty()) {
                        ocrEngine.recognize(crop).trim()
                    } else {
                        lines.joinToString("\n") { it.text }
                    }
                    bubbles.add(OcrBubble(bubbleId, rect, text, BubbleSource.BUBBLE_DETECTOR))
                }
            }
            val result = PageOcrResult(imageFile, bitmap.width, bitmap.height, bubbles)
            ocrStore.save(imageFile, result)
            return@withContext result
        }
        val textRects = textDetector?.let { detectorInstance ->
            val masked = maskDetections(bitmap, bubbleRects)
            val rawTextRects = detectorInstance.detect(masked)
            val filtered = filterOverlapping(rawTextRects, bubbleRects, TEXT_IOU_THRESHOLD)
            RectGeometryDeduplicator.mergeSupplementRects(filtered, bitmap.width, bitmap.height)
        } ?: emptyList()
        if (bubbleRects.isEmpty() && textRects.isEmpty()) {
            val emptyResult = PageOcrResult(imageFile, bitmap.width, bitmap.height, emptyList())
            ocrStore.save(imageFile, emptyResult)
            return@withContext emptyResult
        }
        if (textRects.isNotEmpty()) {
            AppLogger.log(
                "Pipeline",
                "Supplemented ${textRects.size} text boxes in ${imageFile.name}"
            )
        }
        val allRects = ArrayList<RectF>(bubbleRects.size + textRects.size)
        allRects.addAll(bubbleRects)
        allRects.addAll(textRects)
        val bubbles = ArrayList<OcrBubble>(allRects.size)
        val bubbleDetectorCount = bubbleRects.size
        for ((bubbleId, rect) in allRects.withIndex()) {
            val crop = cropBitmap(bitmap, rect) ?: continue
            val text = if (useLocalOcr) {
                ocrEngine?.recognize(crop)?.trim().orEmpty()
            } else {
                recognizeTextWithApiOcr(crop)
            }
            val source = if (bubbleId < bubbleDetectorCount) {
                BubbleSource.BUBBLE_DETECTOR
            } else {
                BubbleSource.TEXT_DETECTOR
            }
            bubbles.add(OcrBubble(bubbleId, rect, text, source))
        }
        val result = PageOcrResult(imageFile, bitmap.width, bitmap.height, bubbles)
        ocrStore.save(imageFile, result)
        result
    }

    suspend fun translateFullPage(
        page: PageOcrResult,
        glossary: Map<String, String>,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val translatable = page.bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) {
            val emptyTranslations = page.bubbles.map {
                BubbleTranslation(it.id, it.rect, "", it.source)
            }
            return@withContext TranslationResult(
                page.imageFile.name,
                page.width,
                page.height,
                emptyTranslations
            )
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val pageText = translatable.joinToString("\n") { bubble ->
            val text = normalizeOcrText(bubble.text, language)
            "<b>$text</b>"
        }
        val translated = llmClient.translate(pageText, glossary, promptAsset)
        if (translated == null) {
            val fallback = page.bubbles.map { bubble ->
                BubbleTranslation(bubble.id, bubble.rect, bubble.text, bubble.source)
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
            translatable.map { it.text },
            onMissingTags = {
                AppLogger.log("Pipeline", "Missing <b> tags in full page translation")
            },
            onCountMismatch = { expected, actual ->
                AppLogger.log(
                    "Pipeline",
                    "Translation count mismatch: expected $expected, got $actual"
                )
            }
        )
        val translationMap = HashMap<Int, String>(translatable.size)
        for (i in translatable.indices) {
            translationMap[translatable[i].id] = translatedSegments[i]
        }
        val bubbles = page.bubbles.map { bubble ->
            val text = translationMap[bubble.id] ?: ""
            BubbleTranslation(bubble.id, bubble.rect, text, bubble.source)
        }
        TranslationResult(page.imageFile.name, page.width, page.height, bubbles)
    }

    fun saveResult(imageFile: File, result: TranslationResult): File {
        val saved = store.save(imageFile, result)
        val ocrFile = ocrStore.ocrFileFor(imageFile)
        if (ocrFile.exists()) {
            ocrFile.delete()
        }
        return saved
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

    private suspend fun recognizeTextWithApiOcr(crop: Bitmap): String {
        return try {
            llmClient.recognizeImageText(crop)?.trim().orEmpty()
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "API OCR failed", e)
            ""
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

    private fun getEnglishOcr(): EnglishOcr? {
        if (englishOcr != null) return englishOcr
        return try {
            englishOcr = EnglishOcr(appContext)
            englishOcr
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "Failed to init English OCR", e)
            null
        }
    }

    private fun getTextDetector(): TextDetector? {
        if (textDetector != null) return textDetector
        return try {
            textDetector = TextDetector(appContext)
            textDetector
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "Failed to init text detector", e)
            null
        }
    }

    private fun getEnglishLineDetector(): EnglishLineDetector? {
        if (englishLineDetector != null) return englishLineDetector
        return try {
            englishLineDetector = EnglishLineDetector(appContext)
            englishLineDetector
        } catch (e: Exception) {
            AppLogger.log("Pipeline", "Failed to init English line detector", e)
            null
        }
    }

    private fun maskDetections(source: Bitmap, rects: List<RectF>): Bitmap {
        if (rects.isEmpty()) return source
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        for (rect in rects) {
            val padded = padRect(rect, source.width, source.height, MASK_EXPAND_RATIO, MASK_EXPAND_MIN)
            canvas.drawRect(padded, paint)
        }
        return copy
    }

    private fun padRect(rect: RectF, width: Int, height: Int, ratio: Float, minPad: Float): RectF {
        val h = max(1f, rect.height())
        val pad = max(minPad, ratio * h)
        val left = (rect.left - pad).coerceIn(0f, width.toFloat())
        val top = (rect.top - pad).coerceIn(0f, height.toFloat())
        val right = (rect.right + pad).coerceIn(0f, width.toFloat())
        val bottom = (rect.bottom + pad).coerceIn(0f, height.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun filterOverlapping(
        textRects: List<RectF>,
        bubbleRects: List<RectF>,
        threshold: Float
    ): List<RectF> {
        if (bubbleRects.isEmpty()) return textRects
        val filtered = ArrayList<RectF>(textRects.size)
        for (rect in textRects) {
            var overlapped = false
            for (bubble in bubbleRects) {
                if (iou(rect, bubble) >= threshold || contains(bubble, rect)) {
                    overlapped = true
                    break
                }
            }
            if (!overlapped) {
                filtered.add(rect)
            }
        }
        return filtered
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun contains(outer: RectF, inner: RectF): Boolean {
        return outer.left <= inner.left &&
            outer.top <= inner.top &&
            outer.right >= inner.right &&
            outer.bottom >= inner.bottom
    }

    companion object {
        private const val TEXT_IOU_THRESHOLD = 0.2f
        private const val MASK_EXPAND_RATIO = 0.1f
        private const val MASK_EXPAND_MIN = 4f
    }
}

data class OcrBubble(
    val id: Int,
    val rect: RectF,
    val text: String,
    val source: BubbleSource = BubbleSource.UNKNOWN
)

data class PageOcrResult(
    val imageFile: File,
    val width: Int,
    val height: Int,
    val bubbles: List<OcrBubble>
)
