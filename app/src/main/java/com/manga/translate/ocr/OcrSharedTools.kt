package com.manga.translate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import com.manga.translate.detection.OnnxRuntimeSupport
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrRecognitionResult
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmGateway
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.cropBitmap
import com.manga.translate.platform.recycleSafely
import com.manga.translate.settings.SettingsStore
import java.text.Normalizer
import kotlinx.coroutines.CancellationException

class OcrEngineRegistry(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    internal fun loadOcrSettings() = settingsStore.loadOcrApiSettings()

    private val appContext = context.applicationContext
    private var ppOcrV6SmallRec: PPOcrV6SmallRec? = null
    private var koreanOcr: KoreanOcr? = null
    private var englishLineDetector: EnglishLineDetector? = null

    init {
        LEGACY_JAPANESE_MODEL_ASSETS.forEach { assetName ->
            OnnxRuntimeSupport.deleteCachedAsset(appContext.cacheDir, assetName)
        }
    }

    @Synchronized
    fun getPpOcrV6SmallRec(logTag: String): PPOcrV6SmallRec? {
        if (ppOcrV6SmallRec != null) return ppOcrV6SmallRec
        return try {
            PPOcrV6SmallRec(appContext, settingsStore = settingsStore).also { ppOcrV6SmallRec = it }
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init PP-OCRv6_small_rec", e)
            null
        }
    }

    @Synchronized
    fun getKoreanOcr(logTag: String): KoreanOcr? {
        if (koreanOcr != null) return koreanOcr
        return try {
            KoreanOcr(appContext, settingsStore = settingsStore).also { koreanOcr = it }
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init Korean OCR", e)
            null
        }
    }

    @Synchronized
    fun getEnglishLineDetector(logTag: String): EnglishLineDetector? {
        if (englishLineDetector != null) return englishLineDetector
        return try {
            EnglishLineDetector(appContext, settingsStore = settingsStore).also {
                englishLineDetector = it
            }
        } catch (e: Exception) {
            AppLogger.log(logTag, "Failed to init English line detector", e)
            null
        }
    }

    @Synchronized
    fun releaseLoadedEngines() {
        val hadLoadedEngines = ppOcrV6SmallRec != null ||
            koreanOcr != null ||
            englishLineDetector != null
        ppOcrV6SmallRec = null
        koreanOcr = null
        englishLineDetector = null
        if (hadLoadedEngines) {
            AppLogger.log("OcrEngineRegistry", "Released loaded OCR engine references")
        }
    }

    private companion object {
        val LEGACY_JAPANESE_MODEL_ASSETS = listOf(
            "models/ocr/manga_ocr_mobile/encoder.tflite",
            "models/ocr/manga_ocr_mobile/decoder.tflite"
        )
    }
}

class BubbleTextRecognizer(
    private val llmClient: LlmGateway,
    private val engineRegistry: OcrEngineRegistry
) {
    fun getLocalOcrEngine(
        language: TranslationLanguage,
        logTag: String
    ): OcrEngine? {
        return when (language) {
            TranslationLanguage.JA_TO_ZH,
            TranslationLanguage.EN_TO_ZH,
            TranslationLanguage.ZH_HANS_TO_TARGET,
            TranslationLanguage.ZH_HANT_TO_TARGET,
            TranslationLanguage.CHN_ENG_TO_ZH -> engineRegistry.getPpOcrV6SmallRec(logTag)
            TranslationLanguage.KO_TO_ZH -> engineRegistry.getKoreanOcr(logTag)
            TranslationLanguage.FR_TO_ZH,
            TranslationLanguage.ES_TO_ZH,
            TranslationLanguage.PT_TO_ZH,
            TranslationLanguage.DE_TO_ZH,
            TranslationLanguage.IT_TO_ZH -> engineRegistry.getPpOcrV6SmallRec(logTag)
            TranslationLanguage.RU_TO_ZH -> null
        }
    }

    suspend fun recognizeRegion(
        source: Bitmap,
        rect: RectF,
        language: TranslationLanguage,
        useLocalOcr: Boolean,
        logTag: String,
        bubbleSource: BubbleSource = BubbleSource.UNKNOWN
    ): OcrRecognitionResult {
        return PipelineBitmapDecoder.openCropSource(source).use { cropSource ->
            recognizeRegion(cropSource, rect, language, useLocalOcr, logTag, bubbleSource)
        }
    }

    internal suspend fun recognizeRegion(
        cropSource: BitmapCropSource,
        rect: RectF,
        language: TranslationLanguage,
        useLocalOcr: Boolean,
        logTag: String,
        bubbleSource: BubbleSource = BubbleSource.UNKNOWN
    ): OcrRecognitionResult {
        val clamped = PipelineBitmapDecoder.clampRect(rect, cropSource.width, cropSource.height)
            ?: return OcrRecognitionResult.Failure(IllegalStateException("Unable to crop OCR region"))
        val crop = cropSource.decodeRegion(clamped) ?: return OcrRecognitionResult.Failure(IllegalStateException("Unable to crop OCR region"))
        return try {
            recognizeCrop(crop, language, useLocalOcr, logTag, bubbleSource)
        } finally {
            crop.recycleSafely()
        }
    }

    suspend fun recognizeCrop(
        crop: Bitmap,
        language: TranslationLanguage,
        useLocalOcr: Boolean,
        logTag: String,
        bubbleSource: BubbleSource = BubbleSource.UNKNOWN,
        detectedLineRects: List<RectF>? = null,
        deferSupplement: Boolean = false,
        onSupplementNeeded: (() -> Unit)? = null
    ): OcrRecognitionResult {
        return try {
            recognizeCropInternal(crop, language, useLocalOcr, logTag, bubbleSource, detectedLineRects,
                deferSupplement, onSupplementNeeded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "OCR recognition failed", e)
            OcrRecognitionResult.Failure(e)
        }
    }

    private suspend fun recognizeCropInternal(
        crop: Bitmap,
        language: TranslationLanguage,
        useLocalOcr: Boolean,
        logTag: String,
        bubbleSource: BubbleSource,
        detectedLineRects: List<RectF>?,
        deferSupplement: Boolean,
        onSupplementNeeded: (() -> Unit)?
    ): OcrRecognitionResult {
        val settings = engineRegistry.loadOcrSettings()
        val resolvedUseLocalOcr = useLocalOcr && language.supportsLocalOcr() &&
            !(bubbleSource == BubbleSource.MANUAL && settings.manualBubblesUseApi)
        val reusableLineRects = detectedLineRects?.takeIf {
            shouldReuseDetectedLineRectsForOcr(bubbleSource) && it.isNotEmpty()
        }
        val rawText = if (!resolvedUseLocalOcr) {
            try {
                llmClient.recognizeImageText(crop, language)?.trim()
                    ?: return OcrRecognitionResult.Failure(IllegalStateException("API OCR returned no result"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.log(logTag, "API OCR failed", e)
                return OcrRecognitionResult.Failure(e)
            }
        } else {
            val engine = getLocalOcrEngine(language, logTag)
                ?: return OcrRecognitionResult.Failure(
                    IllegalStateException("Local OCR engine unavailable for ${language.name}")
                )
            val scoredEngine = ConfidenceTrackingOcrEngine(engine, settings.apiSupplementLevel, language, bubbleSource)
            if (deferSupplement && settings.apiSupplementEnabled) {
                val localText = try {
                    recognizeLocalCrop(crop, language, bubbleSource, reusableLineRects, scoredEngine,
                        logTag, onIncompleteRecognition = { scoredEngine.markIncomplete() },
                        detectLines = {
                            val detector = engineRegistry.getEnglishLineDetector(logTag)
                            if (language == TranslationLanguage.JA_TO_ZH) {
                                detectJapaneseTextLines(crop) { detector?.detectLines(it) }
                            } else detector?.detectLines(crop)
                        })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    scoredEngine.markIncomplete()
                    ""
                }
                if (scoredEngine.needsSupplement || localText.isBlank()) onSupplementNeeded?.invoke()
                localText
            } else recognizeWithOcrSupplement(
                supplementEnabled = settings.apiSupplementEnabled,
                needsSupplement = { scoredEngine.needsSupplement },
                recognizeLocal = {
                    recognizeLocalCrop(
                        crop, language, bubbleSource, reusableLineRects,
                        if (settings.apiSupplementEnabled) scoredEngine else engine, logTag,
                        onIncompleteRecognition = { scoredEngine.markIncomplete() },
                        detectLines = {
                            val detector = engineRegistry.getEnglishLineDetector(logTag)
                            if (language == TranslationLanguage.JA_TO_ZH) {
                                detectJapaneseTextLines(crop) { detector?.detectLines(it) }
                            } else {
                                detector?.detectLines(crop)
                            }
                        }
                    )
                },
                recognizeApi = {
                    llmClient.recognizeImageText(crop, language)?.trim()
                }
            ) ?: return OcrRecognitionResult.Failure(IllegalStateException("Supplemental API OCR returned no result"))
        }
        return OcrRecognitionResult.Success(OcrTextSanitizer.sanitize(rawText, language, logTag))
    }
}

/** Check both individual lines and the final region; a local failure can have no score. */
internal suspend fun recognizeWithOcrSupplement(
    supplementEnabled: Boolean,
    needsSupplement: () -> Boolean,
    recognizeLocal: () -> String,
    recognizeApi: suspend () -> String?
): String? {
    val localText = try {
        recognizeLocal()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!supplementEnabled) throw e
        return recognizeApi()
    }
    return if (supplementEnabled && (needsSupplement() || localText.isBlank())) {
        recognizeApi()
    } else localText
}

/** Observe scores before line filtering, including rejected lines and whole-crop fallback. */
internal class ConfidenceTrackingOcrEngine(
    private val delegate: OcrEngine,
    private val level: Int = 2,
    private val language: TranslationLanguage = TranslationLanguage.EN_TO_ZH,
    private val source: BubbleSource = BubbleSource.BUBBLE_DETECTOR
) : OcrEngine {
    var needsSupplement: Boolean = false
        private set

    fun markIncomplete() {
        needsSupplement = true
    }

    override fun recognize(bitmap: Bitmap): String = recognizeWithScore(bitmap).text

    override fun recognizeWithScore(bitmap: Bitmap, rect: RectF?): OcrEngine.OcrEngineResult {
        return delegate.recognizeWithScore(bitmap, rect).also {
            val base = if (language == TranslationLanguage.KO_TO_ZH) DEFAULT_KO_MIN_LINE_SCORE else DEFAULT_EN_MIN_LINE_SCORE
            val minimum = requiredOcrLineScore(it.text.trim(), base, source)
            val threshold = when (level.coerceIn(1, 3)) {
                1 -> minimum
                2 -> maxOf(minimum, 0.8f)
                else -> maxOf(minimum, 0.95f)
            }
            if (it.text.isBlank() || !it.score.isFinite() || it.score < threshold) needsSupplement = true
        }
    }

}

/** Detect both writing directions before recognition; map columns back to crop coordinates. */
internal fun detectJapaneseTextLines(
    crop: Bitmap,
    detect: (Bitmap) -> List<RectF>?
): List<RectF>? {
    val original = detect(crop) ?: return null
    // Preserve already resolved columns, including mixed small annotations. Redetecting
    // them changes crop padding and can degrade otherwise good recognition.
    if (original.count(::isVerticalTextLine) * 2 > original.size) return original
    val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height,
        Matrix().apply { setRotate(-90f) }, false)
    try {
        val alternative = detect(rotated) ?: return original
        // Cross-column detections are usually short horizontal fragments. Prefer the
        // direction with more evidence of complete horizontal lines, not more boxes.
        fun horizontalExtent(rects: List<RectF>): Float = rects.sumOf {
            if (it.width() >= it.height() * VERTICAL_LINE_RATIO) it.width().toDouble() else 0.0
        }.toFloat()
        if (horizontalExtent(alternative) <= horizontalExtent(original)) return original
        return alternative.map { rect ->
            RectF(crop.width - rect.bottom, rect.left, crop.width - rect.top, rect.right)
        }
    } finally {
        if (rotated !== crop) rotated.recycleSafely()
    }
}

/** A page detector produces blocks; obtain OCR lines from the crop for every local language. */
internal fun recognizeLocalCrop(
    crop: Bitmap,
    language: TranslationLanguage,
    bubbleSource: BubbleSource,
    reusableLineRects: List<RectF>?,
    engine: OcrEngine,
    logTag: String,
    onIncompleteRecognition: () -> Unit = {},
    detectLines: () -> List<RectF>?
): String {
    // null means unavailable, whereas an empty list means detection succeeded without lines.
    val detected = reusableLineRects ?: detectLines()
    val lineRects = detected.orEmpty()
    // No detected lines (including an unavailable detector) cannot establish completeness,
    // even if whole-crop fallback later returns a high-confidence fragment.
    if (lineRects.isEmpty()) onIncompleteRecognition()
    val chinese = language == TranslationLanguage.ZH_HANS_TO_TARGET ||
        language == TranslationLanguage.ZH_HANT_TO_TARGET ||
        language == TranslationLanguage.CHN_ENG_TO_ZH
    if (!chinese && shouldRejectFreeTextWithoutLines(bubbleSource, detected != null, lineRects.size)) {
        AppLogger.log(logTag, "Rejected free-text region without detected OCR lines")
        return ""
    }
    val recognized = when {
        language == TranslationLanguage.JA_TO_ZH || chinese -> recognizeJapaneseLines(crop, lineRects, engine, bubbleSource = bubbleSource)
        language == TranslationLanguage.KO_TO_ZH -> recognizeKoreanLines(crop, lineRects, engine, bubbleSource = bubbleSource)
        else -> recognizeEnglishLines(crop, lineRects, engine, bubbleSource = bubbleSource)
    }
    if (recognized.size < lineRects.size) onIncompleteRecognition()
    return resolveCropOcrText(recognized, lineRects.size, logTag) {
        if (language == TranslationLanguage.KO_TO_ZH) {
            val decoded = engine.recognizeWithScore(crop)
            decoded.text.trim().takeIf {
                passesOcrLineScore(it, decoded.score, DEFAULT_KO_MIN_LINE_SCORE, bubbleSource)
            }.orEmpty()
        } else {
            engine.recognize(crop).trim()
        }
    }
}

internal fun shouldReuseDetectedLineRectsForOcr(source: BubbleSource): Boolean {
    // Only explicit OCR line boxes may be reused; dual-model text blocks never supply them.
    // Re-detect normal bubble lines from their own crop.
    return source == BubbleSource.TEXT_DETECTOR
}

/**
 * Combines per-line recognition with the whole-crop fallback.
 *
 * The fallback exists for small single-line regions the line detector splits badly, but
 * running a single-line rec model over a multi-line paragraph returns a fragment of one
 * line at best. Preferring that fragment used to discard every correctly recognized line,
 * and a bubble left with garbage (or blank) text is dropped downstream by
 * `withRecognizedTextBubblesOnly` or by the translator returning an empty string.
 *
 * So the fallback only applies when per-line recognition produced nothing at all, and only
 * for a region that is a single line to begin with.
 */
internal inline fun resolveCropOcrText(
    recognizedLines: List<EnglishLine>,
    lineRectCount: Int,
    logTag: String? = null,
    recognizeWholeCrop: () -> String
): String {
    val lineText = recognizedLines.joinToString("\n") { it.text }
    if (recognizedLines.isNotEmpty()) {
        if (recognizedLines.size < lineRectCount) {
            logTag?.let {
                AppLogger.log(
                    it,
                    "Keeping ${recognizedLines.size}/$lineRectCount recognized OCR line(s); " +
                        "skipping whole-crop fallback"
                )
            }
        }
        return lineText
    }
    if (lineRectCount > 1) {
        logTag?.let {
            AppLogger.log(
                it,
                "No OCR line passed scoring in a $lineRectCount-line region; " +
                    "skipping whole-crop fallback"
            )
        }
        return ""
    }
    return recognizeWholeCrop().ifBlank { lineText }
}

internal fun shouldRejectFreeTextWithoutLines(
    source: BubbleSource,
    lineDetectorAvailable: Boolean,
    detectedLineCount: Int
): Boolean {
    // This is intentionally a fail-open check: callers should not lose OCR
    // merely because the optional line model could not be loaded.
    return source == BubbleSource.TEXT_DETECTOR &&
        lineDetectorAvailable &&
        detectedLineCount <= 0
}

const val DEFAULT_EN_MIN_LINE_SCORE = 0.5f
const val DEFAULT_FREE_TEXT_MIN_LINE_SCORE = 0.55f
const val DEFAULT_SHORT_TEXT_MIN_LINE_SCORE = 0.65f

private fun passesOcrLineScore(text: String, score: Float, base: Float, source: BubbleSource): Boolean {
    if (text.isBlank()) return false
    // A manual selection is explicit user intent. Keep every recognized line so
    // confidence filtering cannot erase its text before translation.
    if (source == BubbleSource.MANUAL) return true
    return score >= requiredOcrLineScore(text, base, source)
}

private fun requiredOcrLineScore(text: String, base: Float, source: BubbleSource): Float =
    if (source == BubbleSource.TEXT_DETECTOR) {
        if (text.codePointCount(0, text.length) <= 2) DEFAULT_SHORT_TEXT_MIN_LINE_SCORE
        else maxOf(base, DEFAULT_FREE_TEXT_MIN_LINE_SCORE)
    } else base

data class EnglishLine(
    val rect: RectF,
    val text: String
)

fun normalizeOcrText(text: String, language: TranslationLanguage): String {
    val sanitized = OcrTextSanitizer.sanitize(text, language)
    if (!language.usesLatinOcr() && language != TranslationLanguage.KO_TO_ZH) {
        return sanitized
    }
    return sanitized.replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}

object OcrTextSanitizer {
    fun sanitize(
        text: String,
        language: TranslationLanguage,
        logTag: String? = null
    ): String {
        if (text.isBlank()) return ""
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val cleaned = removeInvisibleNoise(normalized)
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .replace(Regex(" *\\n+ *"), "\n")
            .trim()
        if (cleaned.isBlank()) return ""
        if (!containsText(cleaned)) {
            logTag?.let {
                AppLogger.log(it, "Drop OCR non-text bubble language=${language.name}, text=${cleaned.take(80)}")
            }
            return ""
        }
        return cleaned
    }

    private fun removeInvisibleNoise(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == '\r'.code || codePoint == '\n'.code -> builder.append('\n')
                codePoint == '\t'.code -> builder.append(' ')
                shouldDropCodePoint(codePoint) -> Unit
                else -> builder.appendCodePoint(codePoint)
            }
        }
        return builder.toString()
    }

    private fun shouldDropCodePoint(codePoint: Int): Boolean {
        return when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(),
            Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(),
            Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt() -> true
            else -> false
        }
    }

    private fun containsText(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isTextCodePoint(codePoint)) return true
        }
        return false
    }

    private fun isTextCodePoint(codePoint: Int): Boolean {
        return when (Character.getType(codePoint)) {
            Character.UPPERCASE_LETTER.toInt(),
            Character.LOWERCASE_LETTER.toInt(),
            Character.TITLECASE_LETTER.toInt(),
            Character.MODIFIER_LETTER.toInt(),
            Character.OTHER_LETTER.toInt() -> true
            else -> false
        }
    }
}

inline fun <T> withBitmapCrop(
    source: Bitmap,
    rect: RectF,
    block: (Bitmap) -> T
): T? {
    val crop = cropBitmap(source, rect) ?: return null
    return try {
        block(crop)
    } finally {
        // A full-bounds crop of an immutable bitmap may alias the caller's source.
        if (crop !== source) crop.recycleSafely()
    }
}

fun recognizeEnglishLines(
    source: Bitmap,
    lineRects: List<RectF>,
    ocrEngine: OcrEngine,
    minLineScore: Float = DEFAULT_EN_MIN_LINE_SCORE,
    bubbleSource: BubbleSource = BubbleSource.BUBBLE_DETECTOR
): List<EnglishLine> {
    if (lineRects.isEmpty()) return emptyList()
    val results = ArrayList<EnglishLine>(lineRects.size)
    for (rect in lineRects) {
        val decoded = ocrEngine.recognizeWithScore(source, rect)
        val text = decoded.text.trim()
        if (passesOcrLineScore(text, decoded.score, minLineScore, bubbleSource)) {
            results.add(EnglishLine(rect, text))
        }
    }
    return results
}

fun recognizeJapaneseLines(
    source: Bitmap,
    lineRects: List<RectF>,
    ocrEngine: OcrEngine,
    minLineScore: Float = DEFAULT_EN_MIN_LINE_SCORE,
    bubbleSource: BubbleSource = BubbleSource.BUBBLE_DETECTOR
): List<EnglishLine> {
    if (lineRects.isEmpty()) return emptyList()
    val verticalCount = lineRects.count(::isVerticalTextLine)
    val orderedRects = if (verticalCount * 2 > lineRects.size) {
        lineRects.sortedWith(compareByDescending<RectF> { it.right }.thenBy { it.top })
    } else {
        lineRects
    }
    val results = ArrayList<EnglishLine>(orderedRects.size)
    for (rect in orderedRects) {
        val decoded = if (isVerticalTextLine(rect)) {
            withBitmapCrop(source, rect) { crop ->
                val rotated = Bitmap.createBitmap(
                    crop,
                    0,
                    0,
                    crop.width,
                    crop.height,
                    Matrix().apply { setRotate(-90f) },
                    false
                )
                try {
                    ocrEngine.recognizeWithScore(rotated)
                } finally {
                    if (rotated !== crop) {
                        rotated.recycleSafely()
                    }
                }
            } ?: continue
        } else {
            ocrEngine.recognizeWithScore(source, rect)
        }
        val text = decoded.text.trim()
        if (passesOcrLineScore(text, decoded.score, minLineScore, bubbleSource)) {
            results.add(EnglishLine(rect, text))
        }
    }
    return results
}

fun recognizeKoreanLines(
    source: Bitmap,
    lineRects: List<RectF>,
    ocrEngine: OcrEngine,
    minLineScore: Float = DEFAULT_KO_MIN_LINE_SCORE,
    bubbleSource: BubbleSource = BubbleSource.BUBBLE_DETECTOR
): List<EnglishLine> {
    return recognizeEnglishLines(source, lineRects, ocrEngine, minLineScore, bubbleSource)
}

const val DEFAULT_KO_MIN_LINE_SCORE = 0.65f
private const val VERTICAL_LINE_RATIO = 1.5f

private fun isVerticalTextLine(rect: RectF): Boolean {
    return rect.height() >= rect.width() * VERTICAL_LINE_RATIO
}
