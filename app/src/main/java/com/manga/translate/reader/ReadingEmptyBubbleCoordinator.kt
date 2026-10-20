package com.manga.translate.reader

import com.manga.translate.library.LibraryPreferencesGateway

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import com.manga.translate.R
import com.manga.translate.detection.LocalModelMemoryManager
import com.manga.translate.library.LibraryRepository
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.textOrThrow
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.ocr.BubbleTextRecognizer
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.PromptAssetResolver
import com.manga.translate.platform.recycleSafely
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.GlossaryStore
import com.manga.translate.storage.TranslationStore
import com.manga.translate.model.OcrBubble
import com.manga.translate.translation.TextBubbleTranslationBatchResult
import com.manga.translate.translation.TextBubbleTranslationCoordinator
import com.manga.translate.translation.TranslationPipeline
import com.manga.translate.translation.TranslationStrategyResolver
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ReadingEmptyBubbleCoordinator(
    context: Context,
    private val translationStore: TranslationStore,
    private val glossaryStore: GlossaryStore,
    private val repository: LibraryRepository,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val bubbleTextRecognizer: BubbleTextRecognizer,
    private val textBubbleTranslationCoordinator: TextBubbleTranslationCoordinator,
    private val localModelMemoryManager: LocalModelMemoryManager,
    private val translationStrategyResolver: TranslationStrategyResolver,
    private val translationPipelineFactory: () -> TranslationPipeline
) {
    private val appContext = context.applicationContext

    private fun translationSettings(imageFile: File) = settingsStore.load().copy(
        translationStyle = translationStrategyResolver.resolveTranslationStyle(
            requireNotNull(imageFile.absoluteFile.parentFile), settingsStore.loadTranslationStyle()
        )
    )

    private val translationTargetKey: String
        get() = PromptAssetResolver.translationTargetKey(appContext)

    suspend fun process(
        imageFile: File,
        folder: File,
        baseTranslation: TranslationResult
    ): EmptyBubbleProcessOutcome? = withContext(Dispatchers.Default) {
        val targets = baseTranslation.bubbles.filter { it.needsTranslationRetry() }
        if (targets.isEmpty()) return@withContext null
        if (!settingsStore.load().isValid()) {
            AppLogger.log("Reading", "Missing translate API settings for empty bubble translation")
            throw LlmRequestException(
                LlmErrorCode.MissingTranslateApiSettings,
                appContext.getString(R.string.missing_translate_api_settings)
            )
        }

        val preferences = preferencesGateway
        if (preferences.isVlDirectTranslateEnabled(folder) && !preferences.isFullTranslateEnabled(folder)) {
            val settingsFolder = repository.resolveSettingsFolder(folder)
            val glossary = glossaryStore.load(settingsFolder, translationTargetKey)
            val processing = preferences.isGlossaryProcessingEnabled(folder)
            val pipeline = translationPipelineFactory()
            val outcome = try {
                pipeline.translateImageWithVl(imageFile, getTranslationLanguage(folder), glossary, processing,
                    baseTranslation, fullVisual = preferences.isVlFullVisualEnabled(folder))
            } finally { pipeline.releaseLoadedModels() }
            val result = outcome.result ?: return@withContext null
            if (processing && outcome.glossaryUsed.isNotEmpty()) {
                glossary.putAll(outcome.glossaryUsed)
                glossaryStore.save(settingsFolder, glossary, translationTargetKey)
            }
            translationStore.save(imageFile, result)
            return@withContext EmptyBubbleProcessOutcome(result, true, result.bubbles.count { it.needsTranslationRetry() })
        }

        val ocrSettings = settingsStore.loadResolvedOcrApiSettings()
        val language = TranslationLanguage.resolveForOcr(
            getTranslationLanguage(folder),
            ocrSettings.useLocalOcr
        )
        val useLocalOcr = ocrSettings.useLocalOcr && language.supportsLocalOcr()
        val glossary = glossaryStore.load(folder, translationTargetKey)
        val cropSource = PipelineBitmapDecoder.openCropSource(imageFile) ?: return@withContext null
        val localModelLease = localModelMemoryManager.acquire("ReadingEmptyBubble")

        try {
            cropSource.use {
                val candidates = ArrayList<OcrBubble>(targets.size)
                if (!useLocalOcr && !ocrSettings.isValid()) {
                    AppLogger.log("Reading", "Missing OCR API settings")
                    return@withContext null
                }
                for (bubble in targets) {
                    val text = ocrBubble(
                        imageFile = imageFile,
                        folder = folder,
                        cropSource = cropSource,
                        bubble = bubble,
                        language = language,
                        useLocalOcr = useLocalOcr
                    ).trim()
                    // Keep short/empty OCR bubbles (especially user-created MANUAL ones).
                    // Never hard-delete here; failed recognition leaves the frame for retry/edit.
                    if (text.isNotBlank()) {
                        candidates.add(
                            OcrBubble(
                                bubble.id,
                                bubble.rect,
                                text,
                                bubble.source,
                                bubble.maskContour
                            )
                        )
                    }
                }

                if (candidates.isEmpty()) {
                    AppLogger.log(
                        "Reading",
                        "Empty bubble OCR produced no usable text for ${targets.size} pending bubble(s), keep frames"
                    )
                    return@withContext EmptyBubbleProcessOutcome(
                        updatedTranslation = baseTranslation,
                        translatedByLlm = false,
                        ocrFailedCount = targets.size
                    )
                }

                val processing = preferences.isGlossaryProcessingEnabled(folder)
                val translated = translateOcrBubbles(imageFile, candidates, glossary, language, processing)
                if (translated == null) {
                    AppLogger.log("Reading", "Empty bubble translation returned null, keep bubble empty")
                    return@withContext null
                }
                if (processing && translated.glossaryUsed.isNotEmpty()) {
                    glossary.putAll(translated.glossaryUsed)
                    withContext(Dispatchers.IO) {
                        glossaryStore.save(folder, glossary, translationTargetKey)
                    }
                }
                val translationMap = translated.bubbles.associateBy { it.id }
                val additions = translationMap.filterKeys { id ->
                    id !in translated.removedBubbleIds
                }
                val updated = withContext(Dispatchers.IO) {
                    translationStore.mergePending(imageFile, additions, translated.removedBubbleIds)
                } ?: return@withContext null
                val remainingEmpty = updated.bubbles.count { it.needsTranslationRetry() }
                EmptyBubbleProcessOutcome(
                    updatedTranslation = updated,
                    translatedByLlm = true,
                    ocrFailedCount = remainingEmpty
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.manga.translate.model.OcrRecognitionException) {
            AppLogger.log("Reading", "Empty bubble OCR failed; preserving existing translation", e)
            null
        } finally {
            localModelLease.close()
        }
    }

    private fun getTranslationLanguage(folder: File): TranslationLanguage {
        return preferencesGateway.getTranslationLanguage(folder)
    }

    private suspend fun translateOcrBubbles(
        imageFile: File,
        bubbles: List<OcrBubble>,
        glossary: Map<String, String>,
        language: TranslationLanguage,
        glossaryProcessingEnabled: Boolean
    ): TextBubbleTranslationBatchResult? = withContext(Dispatchers.IO) {
        val promptAsset = "prompts/llm_prompts.json"
        try {
            textBubbleTranslationCoordinator.translateBubbles(
                bubbles = bubbles.map { bubble ->
                    BubbleTranslation.pending(
                        id = bubble.id,
                        rect = bubble.rect,
                        originalText = bubble.text,
                        source = bubble.source
                    )
                },
                glossary = glossary,
                promptAsset = promptAsset,
                apiSettings = translationSettings(imageFile),
                language = language,
                logTag = "Reading",
                translationMode = "reading_empty_bubble",
                glossaryProcessingEnabled = glossaryProcessingEnabled
            )
        } catch (e: LlmResponseException) {
            throw e.withPageName(appContext, imageFile.name)
        }
    }

    private suspend fun ocrBubble(
        imageFile: File,
        folder: File,
        cropSource: BitmapCropSource,
        bubble: BubbleTranslation,
        language: TranslationLanguage,
        useLocalOcr: Boolean
    ): String {
        val rect = bubble.rect
        if (bubble.source == BubbleSource.MANUAL && rect.bottom > cropSource.height) {
            val stitched = decodeCrossPageManualBubbleCrop(imageFile, folder, cropSource, rect)
            if (stitched != null) {
                return try {
                    bubbleTextRecognizer.recognizeCrop(
                        crop = stitched,
                        language = language,
                        useLocalOcr = useLocalOcr,
                        logTag = "Reading",
                        bubbleSource = bubble.source
                    ).textOrThrow()
                } finally {
                    stitched.recycleSafely()
                }
            }
        }
        return bubbleTextRecognizer.recognizeRegion(
            cropSource = cropSource,
            rect = rect,
            language = language,
            useLocalOcr = useLocalOcr,
            logTag = "Reading",
            bubbleSource = bubble.source
        ).textOrThrow()
    }

    private suspend fun decodeCrossPageManualBubbleCrop(
        imageFile: File,
        folder: File,
        currentCropSource: BitmapCropSource,
        rect: RectF
    ): Bitmap? {
        val images = repository.listImages(folder)
        val currentIndex = images.indexOfFirst { it.absolutePath == imageFile.absolutePath }
        if (currentIndex < 0) return null
        val nextFile = images.getOrNull(currentIndex + 1) ?: return null
        val overflowHeight = rect.bottom - currentCropSource.height.toFloat()
        if (overflowHeight <= 0f) return null
        val nextCropSource = PipelineBitmapDecoder.openCropSource(nextFile) ?: return null
        nextCropSource.use { nextSource ->
            val fitWidthScale = nextSource.width.toFloat() / currentCropSource.width.coerceAtLeast(1)
            val targetWidth = rect.width().toInt().coerceAtLeast(1)
            val targetHeight = rect.height().toInt().coerceAtLeast(1)
            val stitched = createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stitched)

            val currentRect = PipelineBitmapDecoder.clampRect(
                RectF(rect.left, rect.top.coerceAtLeast(0f), rect.right, currentCropSource.height.toFloat()),
                currentCropSource.width,
                currentCropSource.height
            ) ?: run {
                stitched.recycleSafely()
                return null
            }
            val currentBitmap = currentCropSource.decodeRegion(currentRect, maxEdge = 4096) ?: run {
                stitched.recycleSafely()
                return null
            }
            try {
                canvas.drawBitmap(
                    currentBitmap,
                    null,
                    RectF(0f, (currentRect.top - rect.top).coerceAtLeast(0f), currentRect.width(),
                        (currentRect.bottom - rect.top).coerceAtLeast(0f)),
                    null
                )
            } finally {
                currentBitmap.recycleSafely()
            }

            val nextRect = PipelineBitmapDecoder.clampRect(
                RectF(
                    rect.left * fitWidthScale,
                    0f,
                    rect.right * fitWidthScale,
                    overflowHeight * fitWidthScale
                ),
                nextSource.width,
                nextSource.height
            ) ?: run {
                stitched.recycleSafely()
                return null
            }
            val nextBitmap = nextSource.decodeRegion(nextRect, maxEdge = 4096) ?: run {
                stitched.recycleSafely()
                return null
            }
            try {
                val destinationTop = (currentCropSource.height.toFloat() - rect.top).coerceAtLeast(0f)
                canvas.drawBitmap(
                    nextBitmap,
                    null,
                    RectF(0f, destinationTop, targetWidth.toFloat(),
                        (destinationTop + overflowHeight).coerceAtMost(targetHeight.toFloat())),
                    null
                )
            } finally {
                nextBitmap.recycleSafely()
            }
            return stitched
        }
    }
}

private fun LlmResponseException.withPageName(context: Context, pageName: String): LlmResponseException {
    val pagePrefix = context.getString(R.string.error_page_prefix)
    if (responseContent.startsWith(pagePrefix)) return this
    return LlmResponseException(
        errorCode = errorCode,
        responseContent = "$pagePrefix$pageName\n$responseContent",
        cause = this
    )
}

data class EmptyBubbleProcessOutcome(
    val updatedTranslation: TranslationResult,
    val translatedByLlm: Boolean,
    val ocrFailedCount: Int = 0
)
