package com.manga.translate.translation

import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmBubbleTranslationRequestItem
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmGateway
import com.manga.translate.network.LlmResponseException
import com.manga.translate.ocr.normalizeOcrText
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.ApiSettings

internal class TextBubbleTranslationCoordinator(
    private val llmClient: LlmGateway
) {

    suspend fun translateBubbles(
        bubbles: List<BubbleTranslation>,
        glossary: Map<String, String>,
        promptAsset: String,
        requestTimeoutMs: Int? = null,
        retryCount: Int = 3,
        apiSettings: ApiSettings? = null,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        logTag: String,
        translationMode: String
    ): TextBubbleTranslationBatchResult? {
        if (bubbles.isEmpty()) {
            return TextBubbleTranslationBatchResult(bubbles = bubbles, glossaryUsed = emptyMap())
        }
        val translatable = bubbles.filter { it.sourceText.isNotBlank() }
        if (translatable.isEmpty()) {
            AppLogger.log(logTag, "Skip translate: no translatable text")
            return TextBubbleTranslationBatchResult(bubbles = bubbles, glossaryUsed = emptyMap())
        }

        val resolvedApiSettings = apiSettings
        if (!llmClient.isConfigured(resolvedApiSettings)) {
            AppLogger.log(logTag, "Skip translate: LLM client not configured")
            return null
        }

        val translatedMap = HashMap<Int, String>(translatable.size)
        val removedBubbleIds = LinkedHashSet<Int>()

        AppLogger.log(logTag, "Translate request segments=${translatable.size}")
        val requestItems = translatable
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }, { it.id }))
            .map {
                LlmBubbleTranslationRequestItem(
                    id = it.id,
                    text = normalizeOcrText(it.sourceText, language)
                )
            }
        val translated = llmClient.translateBubbleItems(
            items = requestItems,
            glossary = glossary,
            promptAsset = promptAsset,
            requestTimeoutMs = requestTimeoutMs,
            retryCount = retryCount,
            apiSettings = resolvedApiSettings
        ) ?: return null

        val translationById = LinkedHashMap<Int, String>(translated.items.size)
        val duplicateIds = LinkedHashSet<Int>()
        for (item in translated.items) {
            val normalizedTranslation = item.translation.trim()
            if (translationById.putIfAbsent(item.id, normalizedTranslation) != null) {
                duplicateIds.add(item.id)
            }
        }
        val requestedIds = requestItems.map { it.id }
        val requestedIdSet = requestedIds.toSet()
        val unexpectedIds = translationById.keys.filter { it !in requestedIdSet }
        val missingIds = requestedIds.filterNot { translationById.containsKey(it) }
        if (
            duplicateIds.isNotEmpty() ||
            unexpectedIds.isNotEmpty() ||
            missingIds.isNotEmpty()
        ) {
            val error = buildStructuredTranslationErrorLog(
                mode = translationMode,
                requestedIds = requestedIds,
                duplicateIds = duplicateIds.toList(),
                unexpectedIds = unexpectedIds,
                missingIds = missingIds
            )
            AppLogger.log(logTag, error)
            throw LlmResponseException(LlmErrorCode.MissingTranslationItems, error)
        }

        for (source in translatable) {
            val translatedText = translationById[source.id].orEmpty()
            if (translatedText.isBlank()) {
                removedBubbleIds.add(source.id)
            } else {
                translatedMap[source.id] = translatedText
            }
        }

        return TextBubbleTranslationBatchResult(
            bubbles = mergeBubbleTranslations(bubbles, translatedMap, removedBubbleIds),
            glossaryUsed = translated.glossaryUsed,
            removedBubbleIds = removedBubbleIds
        )
    }
}

internal data class TextBubbleTranslationBatchResult(
    val bubbles: List<BubbleTranslation>,
    val glossaryUsed: Map<String, String>,
    val removedBubbleIds: Set<Int> = emptySet()
)

private fun buildStructuredTranslationErrorLog(
    mode: String,
    requestedIds: List<Int>,
    duplicateIds: List<Int>,
    unexpectedIds: List<Int>,
    missingIds: List<Int>
): String {
    return buildString {
        append("Structured translation partial in ")
        append(mode)
        append(": requested=")
        append(summarizeIdsForLog(requestedIds))
        if (duplicateIds.isNotEmpty()) {
            append(", duplicate=")
            append(summarizeIdsForLog(duplicateIds))
        }
        if (unexpectedIds.isNotEmpty()) {
            append(", unexpected=")
            append(summarizeIdsForLog(unexpectedIds))
        }
        if (missingIds.isNotEmpty()) {
            append(", missing=")
            append(summarizeIdsForLog(missingIds))
        }
    }
}

private fun summarizeIdsForLog(ids: List<Int>, limit: Int = 12): String {
    if (ids.isEmpty()) return "[]"
    val normalized = ids.distinct()
    val shown = normalized.take(limit).joinToString(prefix = "[", postfix = "]")
    return if (normalized.size <= limit) shown else "$shown...(${normalized.size} total)"
}
