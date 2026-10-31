package com.manga.translate.network

/**
 * LlmClient 对外暴露的异常与结果类型。
 *
 * 这些类型原本声明在 LlmClient.kt 中，拆分时原样迁移到本文件（包路径不变，
 * 所有外部 import com.manga.translate.network.Llm* 的引用保持不变）。
 */

class LlmRequestException(
    val errorCode: LlmErrorCode,
    val responseBody: String? = null
) : Exception("LLM request failed: ${errorCode.value}") {
    constructor(errorCode: String, responseBody: String? = null) : this(
        LlmErrorCode.from(errorCode),
        responseBody
    )
}

class LlmResponseException(
    val errorCode: LlmErrorCode,
    val responseContent: String,
    cause: Throwable? = null,
    val retryable: Boolean = true
) : Exception("LLM response invalid: ${errorCode.value}", cause) {
    constructor(errorCode: String, responseContent: String, cause: Throwable? = null) : this(
        LlmErrorCode.from(errorCode),
        responseContent,
        cause
    )
}

data class LlmTranslationResult(
    val translation: String,
    val glossaryUsed: Map<String, String>
)

data class LlmBubbleTranslationRequestItem(
    val id: Int,
    val text: String,
    /** All items sharing a source are indivisible; absent means the whole request is one page. */
    val sourceId: String = "page"
)

data class LlmBubbleTranslationResult(
    val items: List<LlmBubbleTranslationItem>,
    val glossaryUsed: Map<String, String>
)

data class LlmBubbleTranslationItem(
    val id: Int,
    val translation: String
)
