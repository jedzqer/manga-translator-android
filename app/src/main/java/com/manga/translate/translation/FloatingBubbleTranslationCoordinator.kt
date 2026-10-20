package com.manga.translate.translation

import android.graphics.Bitmap
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.BubbleTranslationState
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmGateway
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.FloatingCacheScope
import com.manga.translate.storage.FloatingTranslationCacheStore
import kotlinx.coroutines.coroutineScope

internal class FloatingBubbleTranslationCoordinator(
    private val llmClient: LlmGateway,
    private val floatingTranslationCacheStore: FloatingTranslationCacheStore,
    private val settingsStore: SettingsStore,
    private val vlPageCoordinator: VlPageTranslationCoordinator = VlPageTranslationCoordinator(llmClient, floatingTranslationCacheStore)
) {
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
        glossary: Map<String, String> = emptyMap(),
        apiSettings: ApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings(),
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        concurrency: Int,
        maxConcurrency: Int,
        useCache: Boolean = true,
        logTag: String = "FloatingOCR"
    ): FloatingBubbleImageTranslateOutcome = coroutineScope {
        com.manga.translate.platform.PipelineBitmapDecoder.openCropSource(bitmap).use { source ->
            vlPageCoordinator.translate(source,
                VlPageLayout(bitmap.width, bitmap.height, bubbles), apiSettings, language,
                timeoutMs, retryCount, glossary, useCache = useCache)
        }
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
        return floatingTranslationCacheStore.withCurrentPrompt(FloatingCacheScope(
            language = language,
            providerId = apiSettings.providerId,
            modelName = apiSettings.modelName,
            promptAsset = promptAsset
        ))
    }

}

internal data class FloatingBubbleImageTranslateOutcome(
    val glossaryUsed: Map<String, String> = emptyMap(),
    val bubbles: List<BubbleTranslation> = emptyList(),
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)
