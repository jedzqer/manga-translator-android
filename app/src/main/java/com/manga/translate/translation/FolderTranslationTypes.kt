package com.manga.translate.translation

import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.TranslationResult
import java.io.File

internal const val STANDARD_PROMPT_ASSET = "prompts/llm_prompts.json"

internal data class PageExecutionSummary(
        val hasFailures: Boolean,
        val failedCount: Int
)

internal data class PageTranslationExecutionResult(
        val result: TranslationResult? = null,
        val glossaryUsed: Map<String, String> = emptyMap(),
        val recoveredFromModelError: Boolean = false
)

internal data class PreparedStandardPage(
        val image: File,
        val ocrResult: PageOcrResult?
)

internal data class PipelinedStandardPage(
        val image: File,
        val ocrResult: PageOcrResult?,
        val preparationFailed: Boolean = false
)

