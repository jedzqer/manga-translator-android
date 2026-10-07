package com.manga.translate.translation

import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.*
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.settings.ApiSettings
import com.manga.translate.storage.FloatingCacheScope
import com.manga.translate.storage.FloatingTranslationCacheStore
import org.json.JSONArray
import org.json.JSONObject

internal class VlPageTranslationCoordinator(
    private val client: LlmGateway,
    private val cache: FloatingTranslationCacheStore
) {
    suspend fun translate(
        source: BitmapCropSource,
        layout: VlPageLayout,
        apiSettings: ApiSettings,
        language: TranslationLanguage,
        timeoutMs: Int,
        retryCount: Int,
        glossary: Map<String, String> = emptyMap(),
        glossaryProcessingEnabled: Boolean = false,
        useCache: Boolean = false
    ): FloatingBubbleImageTranslateOutcome {
        val translated = linkedMapOf<Int, String>()
        val names = linkedMapOf<String, String>()
        val scope = FloatingCacheScope(language, apiSettings.providerId, apiSettings.modelName, PROMPT_ASSET)
        for (chunk in layout.chunks()) {
            val encoded = encodePageAnnotation(source, layout, chunk)
            val ids = chunk.targets.map { it.id }
            val key = if (useCache && glossary.isEmpty() && !glossaryProcessingEnabled) {
                cache.createImageKey((encoded + "|" + ids.joinToString(",")).toByteArray(Charsets.UTF_8))
            } else null
            val cached = key?.let { cache.findImageTranslation(it, scope) }
            val result = if (cached != null) ResponseParser().parseImageItemsContent(cached, ids) else {
                try {
                    executeWithModelResponseRetries("VLPage") {
                        val response = client.translateImageItems(encoded, ids, PROMPT_ASSET, glossary, glossaryProcessingEnabled,
                            timeoutMs, retryCount, apiSettings)
                            ?: throw LlmResponseException(LlmErrorCode.EmptyResponse, "Empty VL page response")
                        validateIds(response, ids)
                    }
                } catch (error: LlmRequestException) {
                    if (error.errorCode == LlmErrorCode.Timeout) return FloatingBubbleImageTranslateOutcome(timedOut = true)
                    val body = error.responseBody.orEmpty().lowercase()
                    if (error.errorCode is LlmErrorCode.Http && listOf("image", "vision", "multimodal", "multi-modal").any { it in body }) {
                        return FloatingBubbleImageTranslateOutcome(requiresVlModel = true)
                    }
                    throw error
                }
            }
            validateIds(result, ids)
            result.items.forEach { translated[it.id] = it.translation.trim() }
            if (glossaryProcessingEnabled) names.putAll(result.glossaryUsed)
            if (cached == null && key != null) {
                val items = JSONArray()
                result.items.forEach { items.put(JSONObject().put("id", it.id).put("translation", it.translation)) }
                cache.putImageTranslation(key, JSONObject().put("items", items).toString(), scope)
            }
        }
        return FloatingBubbleImageTranslateOutcome(
            bubbles = layout.renderTranslations(translated), glossaryUsed = names
        )
    }

    // Retain encoded images only; each decoded page and annotation is released before the next page.
    data class PreparedPage(
        val layout: VlPageLayout,
        val images: List<String>,
        val localIds: Map<Int, Int>
    )

    suspend fun preparePage(source: BitmapCropSource, layout: VlPageLayout, firstId: Int): PreparedPage {
        val chunks = layout.chunks()
        val localIds = chunks.flatMap { it.targets }.mapIndexed { index, target ->
            firstId + index to target.id
        }.toMap()
        val requestIds = localIds.entries.associate { (request, local) -> local to request }
        val requestLayout = layout.copy(
            targets = layout.targets.map { it.copy(id = requestIds.getValue(it.id)) },
            targetParents = layout.targetParents.mapKeys { (local, _) -> requestIds.getValue(local) }
        )
        val images = chunks.map { chunk ->
            val targets = chunk.targets.map { it.copy(id = requestIds.getValue(it.id)) }
            encodePageAnnotation(source, requestLayout, chunk.copy(targets = targets))
        }
        return PreparedPage(layout, images, localIds)
    }

    suspend fun translatePreparedPages(
        pages: List<PreparedPage>,
        apiSettings: ApiSettings,
        timeoutMs: Int,
        retryCount: Int,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean
    ): List<FloatingBubbleImageTranslateOutcome> {
        val ids = pages.flatMap { it.localIds.keys }
        val images = pages.flatMap { it.images }
        val result = if (ids.isEmpty()) null else executeWithModelResponseRetries("VLPage") {
            val response = client.translateMultiImageItems(
                images, ids, PROMPT_ASSET, glossary, glossaryProcessingEnabled,
                timeoutMs, retryCount, apiSettings
            ) ?: throw LlmResponseException(LlmErrorCode.EmptyResponse, "Empty VL pages response")
            validateIds(response, ids)
        }
        val translations = result?.items.orEmpty().associate { it.id to it.translation.trim() }
        return pages.map { page ->
            FloatingBubbleImageTranslateOutcome(
                bubbles = page.layout.renderTranslations(page.localIds.entries.associate { (request, local) ->
                    local to translations.getValue(request)
                }),
                glossaryUsed = if (glossaryProcessingEnabled) result?.glossaryUsed.orEmpty() else emptyMap()
            )
        }
    }

    private fun validateIds(result: LlmBubbleTranslationResult, ids: List<Int>): LlmBubbleTranslationResult {
        val returnedIds = result.items.map { it.id }
        if (returnedIds.size != returnedIds.toSet().size || returnedIds.toSet() != ids.toSet()) {
            throw LlmResponseException(LlmErrorCode.MissingTranslationItems, "VL page ID mismatch")
        }
        return result
    }

    companion object { const val PROMPT_ASSET = "prompts/vl_page_prompts.json" }
}
