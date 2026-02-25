package com.manga.translate

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ReadingEmptyBubbleCoordinator(
    context: Context,
    private val translationStore: TranslationStore,
    private val glossaryStore: GlossaryStore,
    private val llmClient: LlmClient,
    private val libraryPrefs: SharedPreferences,
    private val languageKeyPrefix: String = "translation_language_"
) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private var mangaOcr: MangaOcr? = null
    private var englishOcr: EnglishOcr? = null
    private var englishLineDetector: EnglishLineDetector? = null

    suspend fun process(
        imageFile: File,
        folder: File,
        baseTranslation: TranslationResult
    ): EmptyBubbleProcessOutcome? = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.text.isBlank() }
        if (targets.isEmpty()) return@withContext null

        val ocrSettings = settingsStore.loadOcrApiSettings()
        val useLocalOcr = ocrSettings.useLocalOcr
        val language = if (useLocalOcr) {
            getTranslationLanguage(folder)
        } else {
            TranslationLanguage.JA_TO_ZH
        }
        val glossary = glossaryStore.load(folder)
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null

        val candidates = ArrayList<OcrBubble>(targets.size)
        val removedIds = HashSet<Int>()
        if (!ocrSettings.useLocalOcr && !llmClient.isOcrConfigured()) {
            AppLogger.log("Reading", "Missing OCR API settings")
            return@withContext null
        }
        for (bubble in targets) {
            val text = ocrBubble(bitmap, bubble.rect, language, ocrSettings.useLocalOcr).trim()
            if (text.length <= 2) {
                removedIds.add(bubble.id)
            } else {
                candidates.add(OcrBubble(bubble.id, bubble.rect, text, bubble.source))
            }
        }

        val remainingBubbles = baseTranslation.bubbles.filterNot { removedIds.contains(it.id) }
        if (candidates.isEmpty()) {
            val updated = baseTranslation.copy(bubbles = remainingBubbles)
            withContext(Dispatchers.IO) {
                translationStore.save(imageFile, updated)
            }
            return@withContext EmptyBubbleProcessOutcome(updated, translatedByLlm = false)
        }

        val translated = translateOcrBubbles(imageFile, candidates, glossary, language)
        if (translated != null) {
            if (translated.glossaryUsed.isNotEmpty()) {
                glossary.putAll(translated.glossaryUsed)
                withContext(Dispatchers.IO) {
                    glossaryStore.save(folder, glossary)
                }
            }
            val translatedSegments = extractTaggedSegments(
                translated.translation,
                candidates.map { it.text },
                onMissingTags = {
                    AppLogger.log("Reading", "Missing <b> tags in OCR translation")
                },
                onCountMismatch = { expected, actual ->
                    AppLogger.log(
                        "Reading",
                        "OCR translation count mismatch: expected $expected, got $actual"
                    )
                }
            )
            val translationMap = HashMap<Int, String>(candidates.size)
            for (i in candidates.indices) {
                translationMap[candidates[i].id] = translatedSegments[i]
            }
            val merged = remainingBubbles.map { bubble ->
                translationMap[bubble.id]?.let { bubble.copy(text = it) } ?: bubble
            }
            val updated = baseTranslation.copy(bubbles = merged)
            withContext(Dispatchers.IO) {
                translationStore.save(imageFile, updated)
            }
            return@withContext EmptyBubbleProcessOutcome(updated, translatedByLlm = true)
        }

        val fallbackMap = candidates.associate { it.id to it.text }
        val merged = remainingBubbles.map { bubble ->
            fallbackMap[bubble.id]?.let { bubble.copy(text = it) } ?: bubble
        }
        val updated = baseTranslation.copy(bubbles = merged)
        withContext(Dispatchers.IO) {
            translationStore.save(imageFile, updated)
        }
        EmptyBubbleProcessOutcome(updated, translatedByLlm = false)
    }

    private fun getTranslationLanguage(folder: File): TranslationLanguage {
        val value = libraryPrefs.getString(languageKeyPrefix + folder.absolutePath, null)
        return TranslationLanguage.fromString(value)
    }

    private suspend fun translateOcrBubbles(
        imageFile: File,
        bubbles: List<OcrBubble>,
        glossary: Map<String, String>,
        language: TranslationLanguage
    ): LlmTranslationResult? = withContext(Dispatchers.IO) {
        if (!llmClient.isConfigured()) {
            AppLogger.log("Reading", "Missing API settings for OCR translation")
            return@withContext null
        }
        val pageText = bubbles.joinToString("\n") { bubble ->
            val text = normalizeOcrText(bubble.text, language)
            "<b>$text</b>"
        }
        val promptAsset = "llm_prompts.json"
        try {
            llmClient.translate(pageText, glossary, promptAsset)
        } catch (e: Exception) {
            AppLogger.log("Reading", "Translate OCR bubbles failed for ${imageFile.name}", e)
            null
        }
    }

    private suspend fun ocrBubble(
        bitmap: android.graphics.Bitmap,
        rect: RectF,
        language: TranslationLanguage,
        useLocalOcr: Boolean
    ): String {
        val crop = cropBitmap(bitmap, rect) ?: return ""
        if (!useLocalOcr) {
            return llmClient.recognizeImageText(crop)?.trim().orEmpty()
        }
        return when (language) {
            TranslationLanguage.JA_TO_ZH -> {
                val engine = getMangaOcr() ?: return ""
                engine.recognize(crop).trim()
            }
            TranslationLanguage.EN_TO_ZH -> {
                val engine = getEnglishOcr() ?: return ""
                val lineDetector = getEnglishLineDetector()
                val lineRects = lineDetector?.detectLines(crop).orEmpty()
                val lines = recognizeEnglishLines(crop, lineRects, engine)
                if (lines.isEmpty()) {
                    engine.recognize(crop).trim()
                } else {
                    lines.joinToString("\n") { it.text }
                }
            }
        }
    }

    private fun getMangaOcr(): MangaOcr? {
        if (mangaOcr != null) return mangaOcr
        return try {
            mangaOcr = MangaOcr(appContext)
            mangaOcr
        } catch (e: Exception) {
            AppLogger.log("Reading", "Failed to init OCR", e)
            null
        }
    }

    private fun getEnglishOcr(): EnglishOcr? {
        if (englishOcr != null) return englishOcr
        return try {
            englishOcr = EnglishOcr(appContext)
            englishOcr
        } catch (e: Exception) {
            AppLogger.log("Reading", "Failed to init English OCR", e)
            null
        }
    }

    private fun getEnglishLineDetector(): EnglishLineDetector? {
        if (englishLineDetector != null) return englishLineDetector
        return try {
            englishLineDetector = EnglishLineDetector(appContext)
            englishLineDetector
        } catch (e: Exception) {
            AppLogger.log("Reading", "Failed to init English line detector", e)
            null
        }
    }
}

data class EmptyBubbleProcessOutcome(
    val updatedTranslation: TranslationResult,
    val translatedByLlm: Boolean
)
