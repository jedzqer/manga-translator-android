package com.manga.translate.floating

import android.content.Context
import android.graphics.Bitmap
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.textOrThrow
import com.manga.translate.network.LlmGateway
import com.manga.translate.ocr.BubbleTextRecognizer
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.cropBitmap
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.FloatingTranslationCacheStore
import com.manga.translate.translation.FloatingBubbleTranslationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal class FloatingEmptyBubbleCoordinator(
    context: Context,
    private val llmClient: LlmGateway,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore,
    private val settingsStore: SettingsStore,
    private val bubbleTextRecognizer: BubbleTextRecognizer,
    private val floatingBubbleTranslationCoordinator: FloatingBubbleTranslationCoordinator
) {
    suspend fun process(
        bitmap: Bitmap,
        baseTranslation: TranslationResult,
        timeoutMs: Int,
        retryCount: Int,
        floatPromptAsset: String,
        floatVlPromptAsset: String,
        maxVlConcurrency: Int,
        language: TranslationLanguage
    ): FloatingEmptyBubbleOutcome = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.needsTranslationRetry() }
        if (targets.isEmpty()) {
            return@withContext FloatingEmptyBubbleOutcome(baseTranslation)
        }

        val client = llmClient
        val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
        val floatingApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings()
        val useVlDirectTranslate =
            floatingSettings.useVlDirectTranslate && client.isConfigured(floatingApiSettings)

        val updatedBubbles = if (useVlDirectTranslate) {
            val outcome = translateBubbleImages(
                bitmap = bitmap,
                bubbles = targets,
                timeoutMs = timeoutMs,
                retryCount = retryCount,
                apiSettings = floatingApiSettings,
                promptAsset = floatVlPromptAsset,
                language = language,
                concurrency = floatingSettings.aiApiConcurrencyLimit,
                maxVlConcurrency = maxVlConcurrency
            )
            if (outcome.requiresVlModel || outcome.timedOut) {
                return@withContext FloatingEmptyBubbleOutcome(
                    translation = baseTranslation,
                    timedOut = outcome.timedOut,
                    requiresVlModel = outcome.requiresVlModel
                )
            }
            com.manga.translate.translation.mergeVlTargetResults(baseTranslation.bubbles, targets, outcome.bubbles)
        } else {
            val recognized = recognizeEmptyBubbles(bitmap, targets, language)
            val translated = translateRecognizedBubbles(
                bubbles = recognized,
                timeoutMs = timeoutMs,
                retryCount = retryCount,
                promptAsset = floatPromptAsset,
                apiSettings = floatingApiSettings,
                language = language
            ) ?: return@withContext FloatingEmptyBubbleOutcome(
                translation = baseTranslation,
                timedOut = true
            )
            val translationMap = translated.associateBy { it.id }
            baseTranslation.bubbles.map { bubble ->
                translationMap[bubble.id]?.let { bubble.withContentFrom(it) } ?: bubble
            }
        }

        FloatingEmptyBubbleOutcome(baseTranslation.copy(bubbles = updatedBubbles))
    }

    private suspend fun recognizeEmptyBubbles(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>,
        language: TranslationLanguage
    ): List<BubbleTranslation> = withContext(Dispatchers.Default) {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        val floatingLanguage = TranslationLanguage.resolveForOcr(
            language,
            ocrSettings.useLocalOcr
        )
        val useLocalOcr = ocrSettings.useLocalOcr && floatingLanguage.supportsLocalOcr()
        bubbles.map { bubble ->
            val crop = cropBitmap(bitmap, bubble.rect)
            val text = try {
                if (crop == null) {
                    throw IllegalStateException("Unable to crop OCR region")
                } else {
                    recognizeBubble(crop, floatingLanguage, useLocalOcr, bubble.source)
                }
            } catch (e: Exception) {
                AppLogger.log("FloatingOCR", "Recognize empty bubble failed id=${bubble.id}", e)
                throw e
            } finally {
                crop?.recycle()
            }
            bubble.withRecognizedOriginalText(text)
        }
    }

    private suspend fun translateRecognizedBubbles(
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings,
        language: TranslationLanguage
    ): List<BubbleTranslation>? {
        return floatingBubbleTranslationCoordinator.translateTextBubbles(
            bubbles = bubbles,
            timeoutMs = timeoutMs,
            retryCount = retryCount,
            promptAsset = promptAsset,
            apiSettings = apiSettings,
            language = language,
            logTag = "FloatingOCR"
        )
    }

    private suspend fun recognizeBubble(
        crop: Bitmap,
        language: TranslationLanguage,
        useLocalOcr: Boolean,
        bubbleSource: BubbleSource
    ): String = withContext(Dispatchers.Default) {
        bubbleTextRecognizer.recognizeCrop(
            crop = crop,
            language = language,
            useLocalOcr = useLocalOcr,
            logTag = "FloatingOCR",
            bubbleSource = bubbleSource
        ).textOrThrow()
    }

    private suspend fun translateBubbleImages(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        apiSettings: ApiSettings,
        promptAsset: String,
        language: TranslationLanguage,
        concurrency: Int,
        maxVlConcurrency: Int
    ): FloatingVlBubbleTranslateOutcome = coroutineScope {
        val outcome = floatingBubbleTranslationCoordinator.translateImageBubbles(
            bitmap = bitmap,
            bubbles = bubbles,
            timeoutMs = timeoutMs,
            retryCount = retryCount,
            promptAsset = promptAsset,
            apiSettings = apiSettings,
            language = language,
            concurrency = concurrency,
            maxConcurrency = maxVlConcurrency,
            logTag = "FloatingOCR"
        )
        return@coroutineScope FloatingVlBubbleTranslateOutcome(
            bubbles = outcome.bubbles,
            timedOut = outcome.timedOut,
            requiresVlModel = outcome.requiresVlModel
        )
    }

}

data class FloatingEmptyBubbleOutcome(
    val translation: TranslationResult,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

private data class FloatingVlBubbleTranslateOutcome(
    val bubbles: List<BubbleTranslation> = emptyList(),
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)
