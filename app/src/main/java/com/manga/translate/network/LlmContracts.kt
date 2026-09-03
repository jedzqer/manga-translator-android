package com.manga.translate.network

import android.content.Context
import android.graphics.Bitmap
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.settings.ApiSettings

sealed class LlmErrorCode(val value: String) {
    data object Timeout : LlmErrorCode("TIMEOUT")
    data object NetworkError : LlmErrorCode("NETWORK_ERROR")
    data object InvalidResponse : LlmErrorCode("INVALID_RESPONSE")
    data object InvalidFormat : LlmErrorCode("INVALID_FORMAT")
    data object MissingTranslation : LlmErrorCode("MISSING_TRANSLATION")
    data object MissingTranslationItems : LlmErrorCode("MISSING_TRANSLATION_ITEMS")
    data object MissingApiSettings : LlmErrorCode("MISSING_API_SETTINGS")
    data object MissingTranslateApiSettings : LlmErrorCode("MISSING_TRANSLATE_API_SETTINGS")
    data object MissingUrl : LlmErrorCode("MISSING_URL")
    data object EmptyResponse : LlmErrorCode("EMPTY_RESPONSE")
    data object ResponseTruncated : LlmErrorCode("RESPONSE_TRUNCATED")
    data object CustomParamConflict : LlmErrorCode("CUSTOM_PARAM_CONFLICT")
    data object CustomParamInvalidValue : LlmErrorCode("CUSTOM_PARAM_INVALID_VALUE")
    data object ImageEncodeFailed : LlmErrorCode("IMAGE_ENCODE_FAILED")
    data object EmptyTranslationSegment : LlmErrorCode("EMPTY_TRANSLATION_SEGMENT")
    data object VlModelRequired : LlmErrorCode("VL_MODEL_REQUIRED")
    data class Http(val status: Int) : LlmErrorCode("HTTP $status")
    data class Custom(private val raw: String) : LlmErrorCode(raw)

    companion object {
        fun from(raw: String): LlmErrorCode {
            return when (raw) {
                Timeout.value -> Timeout
                NetworkError.value -> NetworkError
                InvalidResponse.value -> InvalidResponse
                InvalidFormat.value -> InvalidFormat
                MissingTranslation.value -> MissingTranslation
                MissingTranslationItems.value -> MissingTranslationItems
                MissingApiSettings.value -> MissingApiSettings
                MissingTranslateApiSettings.value -> MissingTranslateApiSettings
                MissingUrl.value -> MissingUrl
                EmptyResponse.value -> EmptyResponse
                ResponseTruncated.value -> ResponseTruncated
                CustomParamConflict.value -> CustomParamConflict
                CustomParamInvalidValue.value -> CustomParamInvalidValue
                ImageEncodeFailed.value -> ImageEncodeFailed
                EmptyTranslationSegment.value -> EmptyTranslationSegment
                VlModelRequired.value -> VlModelRequired
                else -> {
                    raw.removePrefix("HTTP ").toIntOrNull()?.let { Http(it) } ?: Custom(raw)
                }
            }
        }
    }
}

interface LlmGateway {
    fun isConfigured(apiSettings: ApiSettings? = null): Boolean
    fun isOcrConfigured(): Boolean
    suspend fun translateBubbleItems(
        items: List<LlmBubbleTranslationRequestItem>,
        glossary: Map<String, String>,
        promptAsset: String = "prompts/llm_prompts.json",
        requestTimeoutMs: Int? = null,
        retryCount: Int = 3,
        apiSettings: ApiSettings? = null
    ): LlmBubbleTranslationResult?
    suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String>?
    suspend fun recognizeImageText(image: Bitmap, language: TranslationLanguage): String?
    suspend fun translateImageBubble(
        imageBase64: String,
        promptAsset: String,
        requestTimeoutMs: Int? = null,
        retryCount: Int = 3,
        apiSettings: ApiSettings? = null
    ): String?
    fun resourceContext(): Context
}
