package com.manga.translate.translation

import android.content.Context
import android.graphics.Bitmap
import com.manga.translate.R
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.BubbleTranslationState
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmGateway
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.ImageEncodingUtils
import com.manga.translate.platform.cropBitmap
import com.manga.translate.platform.recycleSafely
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.FloatingCacheScope
import com.manga.translate.storage.FloatingTranslationCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class FloatingBubbleTranslationCoordinator(
    private val llmClient: LlmGateway,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore,
    private val settingsStore: SettingsStore
) {
    private val appContext = llmClient.resourceContext()
    private val textBubbleTranslationCoordinator = TextBubbleTranslationCoordinator(
        llmClient = llmClient
    )

    suspend fun translateTextBubbles(
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings(),
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        logTag: String = "FloatingOCR"
    ): List<BubbleTranslation>? {
        if (bubbles.isEmpty()) return bubbles
        val translatable = bubbles.filter { it.sourceText.isNotBlank() }
        if (translatable.isEmpty()) {
            AppLogger.log(logTag, "Skip translate: no translatable text")
            return bubbles
        }
        if (!llmClient.isConfigured(apiSettings)) {
            AppLogger.log(logTag, "Missing translate API settings")
            throw LlmRequestException(
                LlmErrorCode.MissingTranslateApiSettings
            )
        }

        val translatedMap = HashMap<Int, String>(translatable.size)
        val removedBubbleIds = LinkedHashSet<Int>()
        val cacheMisses = ArrayList<BubbleTranslation>(translatable.size)
        var exactCacheHits = 0
        var similarityCacheHits = 0
        val cacheScope = buildCacheScope(apiSettings, language, promptAsset)
        for (bubble in translatable) {
            val cached = floatingTranslationCacheStore.findTextTranslation(
                text = bubble.sourceText,
                scope = cacheScope
            )
            if (cached == null) {
                cacheMisses.add(bubble)
                continue
            }
            translatedMap[bubble.id] = cached.translation
            if (cached.matchedBySimilarity) {
                similarityCacheHits++
            } else {
                exactCacheHits++
            }
        }
        AppLogger.log(
            "FloatingCache",
            "Text cache exactHits=$exactCacheHits similarityHits=$similarityCacheHits misses=${cacheMisses.size}"
        )

        if (cacheMisses.isEmpty()) {
            return mergeBubbleTranslations(bubbles, translatedMap, removedBubbleIds)
        }

        return try {
            val result = textBubbleTranslationCoordinator.translateBubbles(
                bubbles = cacheMisses,
                glossary = emptyMap(),
                promptAsset = promptAsset,
                requestTimeoutMs = timeoutMs,
                retryCount = retryCount,
                apiSettings = apiSettings,
                language = language,
                logTag = logTag,
                translationMode = "floating_text"
            ) ?: return null
            removedBubbleIds.addAll(result.removedBubbleIds)
            for (bubble in result.bubbles) {
                if (bubble.translationState == BubbleTranslationState.TRANSLATED) {
                    translatedMap[bubble.id] = bubble.translatedText
                    val source = cacheMisses.firstOrNull { it.id == bubble.id } ?: continue
                    floatingTranslationCacheStore.putTextTranslation(
                        text = source.sourceText,
                        translation = bubble.translatedText,
                        scope = cacheScope
                    )
                }
            }
            val merged = mergeBubbleTranslations(bubbles, translatedMap, removedBubbleIds)
            AppLogger.log(logTag, "Translate success segments=${translatedMap.size}")
            merged
        } catch (e: LlmRequestException) {
            if (e.errorCode == LlmErrorCode.Timeout) {
                AppLogger.log(logTag, "LLM translate timeout")
                null
            } else {
                throw e
            }
        } catch (e: LlmResponseException) {
            throw e
        } catch (e: Exception) {
            AppLogger.log(logTag, "LLM translate failed", e)
            throw e
        }
    }

    suspend fun translateImageBubbles(
        bitmap: Bitmap,
        bubbles: List<BubbleTranslation>,
        timeoutMs: Int,
        retryCount: Int,
        promptAsset: String,
        apiSettings: ApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings(),
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        concurrency: Int,
        maxConcurrency: Int,
        useCache: Boolean = true,
        logTag: String = "FloatingOCR"
    ): FloatingBubbleImageTranslateOutcome = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, maxConcurrency))
        val cacheScope = buildCacheScope(apiSettings, language, promptAsset)
        val tasks = bubbles.map { bubble ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val crop = cropBitmap(bitmap, bubble.rect)
                    if (crop == null) {
                        return@withPermit FloatingBubbleImageTranslateTaskResult(bubble = bubble)
                    }
                    val imageCacheKey = if (useCache) {
                        compressBitmapToJpeg(crop, 80)?.let { jpegBytes ->
                            floatingTranslationCacheStore.createImageKey(jpegBytes)
                        }
                    } else {
                        null
                    }
                    val cachedTranslation = imageCacheKey?.let {
                        floatingTranslationCacheStore.findImageTranslation(it, cacheScope)
                    }
                    if (useCache && !cachedTranslation.isNullOrBlank()) {
                        AppLogger.log("FloatingCache", "VL cache hit bubble=${bubble.id}")
                        crop.recycleSafely()
                        return@withPermit FloatingBubbleImageTranslateTaskResult(
                            bubble = bubble.withTranslationResult(cachedTranslation)
                        )
                    }
                    val requestImageBase64 = ImageEncodingUtils.encodeBitmapToBase64(crop) ?: run {
                        crop.recycleSafely()
                        return@withPermit FloatingBubbleImageTranslateTaskResult(
                            responseException = LlmResponseException(
                                errorCode = LlmErrorCode.ImageEncodeFailed,
                                responseContent = "Failed to encode bubble crop as JPEG"
                            )
                        )
                    }
                    val translatedText = try {
                        llmClient.translateImageBubble(
                            imageBase64 = requestImageBase64,
                            promptAsset = promptAsset,
                            requestTimeoutMs = timeoutMs,
                            retryCount = retryCount,
                            apiSettings = apiSettings
                        ).orEmpty()
                    } catch (e: LlmRequestException) {
                        if (e.errorCode == LlmErrorCode.Timeout) {
                            AppLogger.log(logTag, "VL direct translate timeout")
                            return@withPermit FloatingBubbleImageTranslateTaskResult(timedOut = true)
                        }
                        AppLogger.log(logTag, "VL direct translate request failed", e)
                        if (looksLikeVisionModelError(e)) {
                            return@withPermit FloatingBubbleImageTranslateTaskResult(requiresVlModel = true)
                        }
                        ""
                    } catch (e: Exception) {
                        AppLogger.log(logTag, "VL direct translate failed", e)
                        ""
                    } finally {
                        crop.recycleSafely()
                    }
                    if (useCache && translatedText.isNotBlank() && imageCacheKey != null) {
                        floatingTranslationCacheStore.putImageTranslation(
                            imageKey = imageCacheKey,
                            translation = translatedText,
                            scope = cacheScope
                        )
                    }
                    if (translatedText.isBlank()) {
                        return@withPermit FloatingBubbleImageTranslateTaskResult(
                            responseException = LlmResponseException(
                                errorCode = LlmErrorCode.EmptyTranslationSegment,
                                responseContent = buildBlankModelResponseMessage(
                                    context = appContext,
                                    bubbleCount = 1,
                                    mode = "image"
                                )
                            )
                        )
                    }
                    FloatingBubbleImageTranslateTaskResult(
                        bubble = bubble.withTranslationResult(translatedText)
                    )
                }
            }
        }
        val results = tasks.awaitAll()
        if (results.any { it.requiresVlModel }) {
            return@coroutineScope FloatingBubbleImageTranslateOutcome(requiresVlModel = true)
        }
        if (results.any { it.timedOut }) {
            return@coroutineScope FloatingBubbleImageTranslateOutcome(timedOut = true)
        }
        results.firstNotNullOfOrNull { it.responseException }?.let { throw it }
        val translated = results.mapNotNull { it.bubble }
        AppLogger.log(logTag, "VL direct translate success segments=${translated.size}")
        return@coroutineScope FloatingBubbleImageTranslateOutcome(bubbles = translated)
    }

    /**
     * Builds the cache scope for the configuration actually used by this request, so a
     * cached translation is never reused after the provider, model or prompt changed.
     */
    private fun buildCacheScope(
        apiSettings: ApiSettings,
        language: TranslationLanguage,
        promptAsset: String
    ): FloatingCacheScope {
        return FloatingCacheScope(
            language = language,
            providerId = apiSettings.providerId,
            modelName = apiSettings.modelName,
            promptAsset = promptAsset
        )
    }

    fun looksLikeVisionModelError(error: LlmRequestException): Boolean {
        val body = error.responseBody.orEmpty().lowercase()
        val hints = listOf(
            "image",
            "vision",
            "multimodal",
            "multi-modal",
            "image_url",
            "input_image",
            "does not support image",
            "unsupported content type"
        )
        return error.errorCode is LlmErrorCode.Http && hints.any { it in body }
    }
}

private fun buildBlankModelResponseMessage(
    context: Context,
    bubbleCount: Int,
    mode: String
): String {
    return context.getString(R.string.model_response_blank_bubbles, mode, bubbleCount)
}

internal data class FloatingBubbleImageTranslateOutcome(
    val bubbles: List<BubbleTranslation> = emptyList(),
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

private data class FloatingBubbleImageTranslateTaskResult(
    val bubble: BubbleTranslation? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false,
    val responseException: LlmResponseException? = null
)

private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
    return ImageEncodingUtils.compressBitmapToJpeg(bitmap, quality)
}
