package com.manga.translate.translation


import com.manga.translate.model.OcrBubble
import com.manga.translate.di.appContainer
import android.content.Context
import com.manga.translate.R
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.textOrThrow
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.ocr.BubbleTextRecognizer
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.recycleSafely
import com.manga.translate.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PendingBubbleRetranslator(
    context: Context,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val bubbleTextRecognizer: BubbleTextRecognizer,
    private val textBubbleTranslationCoordinator: TextBubbleTranslationCoordinator
) {
    private val appContext = context.applicationContext

    private fun translationSettings(imageFile: File) = settingsStore.load().copy(
        translationStyle = appContext.appContainer.libraryPreferencesGateway.resolveTranslationStyle(
            requireNotNull(imageFile.absoluteFile.parentFile), settingsStore.loadTranslationStyle()
        )
    )


    suspend fun refill(
        imageFile: File,
        baseTranslation: TranslationResult,
        glossary: Map<String, String>,
        language: TranslationLanguage,
        promptAsset: String,
        translationMode: String,
        logTag: String,
        discardShortOcr: Boolean,
        glossaryProcessingEnabled: Boolean = true
    ): RefillOutcome? = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.needsTranslationRetry() }
        if (targets.isEmpty()) {
            return@withContext RefillOutcome(translation = baseTranslation, translatedByLlm = false)
        }

        val ocrSettings = settingsStore.loadResolvedOcrApiSettings()
        val useLocalOcr = ocrSettings.useLocalOcr
        if (!useLocalOcr && !ocrSettings.isValid()) {
            AppLogger.log(logTag, "Refill skipped: missing OCR API settings")
            return@withContext null
        }
        if (!settingsStore.load().isValid()) {
            AppLogger.log(logTag, "Refill skipped: missing translate API settings")
            throw LlmRequestException(
                LlmErrorCode.MissingTranslateApiSettings,
                appContext.getString(R.string.missing_translate_api_settings)
            )
        }

        val cropSource = PipelineBitmapDecoder.openCropSource(imageFile) ?: run {
            AppLogger.log(logTag, "Refill skipped: failed to open crop source for ${imageFile.name}")
            return@withContext null
        }

        cropSource.use {
            val candidates = ArrayList<OcrBubble>(targets.size)
            val removedIds = HashSet<Int>()
            for (bubble in targets) {
                val clamped = PipelineBitmapDecoder.clampRect(bubble.rect, cropSource.width, cropSource.height)
                    ?: return@withContext null
                val crop = cropSource.decodeRegion(clamped) ?: return@withContext null
                val text = try {
                        bubbleTextRecognizer.recognizeCrop(
                            crop = crop,
                            language = language,
                            useLocalOcr = useLocalOcr,
                            logTag = logTag,
                            bubbleSource = bubble.source
                        ).textOrThrow().trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.log(logTag, "Refill OCR failed id=${bubble.id}; preserving existing translation", e)
                    return@withContext null
                } finally {
                    crop.recycleSafely()
                }
                if (discardShortOcr && text.length <= 2) {
                    removedIds.add(bubble.id)
                } else if (text.isNotBlank()) {
                    candidates.add(OcrBubble(bubble.id, bubble.rect, text, bubble.source, bubble.maskContour))
                }
            }

            val remainingBubbles = baseTranslation.bubbles.filterNot { removedIds.contains(it.id) }
            if (candidates.isEmpty()) {
                val updated = baseTranslation.copy(
                    bubbles = remainingBubbles,
                    metadata = baseTranslation.metadata.copy(status = PageTranslationStatus.UNKNOWN)
                )
                return@withContext RefillOutcome(translation = updated, translatedByLlm = false)
            }

            val translated = try {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = candidates.map { ocr ->
                        BubbleTranslation.pending(
                            id = ocr.id,
                            rect = ocr.rect,
                            originalText = ocr.text,
                            source = ocr.source,
                            maskContour = ocr.maskContour
                        )
                    },
                    glossary = glossary,
                    promptAsset = promptAsset,
                    apiSettings = translationSettings(imageFile),
                    language = language,
                    logTag = logTag,
                    translationMode = translationMode,
                    glossaryProcessingEnabled = glossaryProcessingEnabled
                )
            } catch (e: LlmResponseException) {
                throw e
            } ?: run {
                AppLogger.log(logTag, "Refill returned null translation result")
                return@withContext null
            }

            val translationMap = translated.bubbles.associateBy { it.id }
            val merged = remainingBubbles
                .filterNot { it.id in translated.removedBubbleIds }
                .map { bubble ->
                translationMap[bubble.id]?.let { bubble.withContentFrom(it) } ?: bubble
            }
            val updated = baseTranslation.copy(
                bubbles = merged,
                metadata = baseTranslation.metadata.copy(status = PageTranslationStatus.UNKNOWN)
            )
            RefillOutcome(translation = updated, translatedByLlm = true, glossaryUsed = translated.glossaryUsed)
        }
    }

    data class RefillOutcome(
        val translation: TranslationResult,
        val translatedByLlm: Boolean,
        val glossaryUsed: Map<String, String> = emptyMap()
    )
}
