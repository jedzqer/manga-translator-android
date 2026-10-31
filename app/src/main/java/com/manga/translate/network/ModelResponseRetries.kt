package com.manga.translate.network

import com.manga.translate.platform.AppLogger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Retries invalid model responses; null results and other failures retain their original behavior. */
internal suspend fun <T> executeWithModelResponseRetries(
    logTag: String,
    block: suspend () -> T
): T {
    val budgetContext = currentCoroutineContext()[RequestBudgetContext] ?: RequestBudgetContext()
    return withContext(budgetContext) { retryResponses(logTag, budgetContext, block) }
}

private suspend fun <T> retryResponses(
    logTag: String,
    budgetContext: RequestBudgetContext,
    block: suspend () -> T
): T {
    var lastError: LlmResponseException? = null
    repeat(MODEL_RESPONSE_SILENT_RETRY_COUNT) { attempt ->
        currentCoroutineContext().ensureActive()
        budgetContext.checkRemaining()
        try {
            return block().also { budgetContext.checkRemaining() }
        } catch (e: LlmResponseException) {
            budgetContext.checkRemaining()
            if (!e.retryable) throw e
            lastError = e
            AppLogger.log(
                logTag,
                "Model response invalid (attempt ${attempt + 1}/$MODEL_RESPONSE_SILENT_RETRY_COUNT, error=${e.errorCode.value})",
                e
            )
            if (attempt + 1 < MODEL_RESPONSE_SILENT_RETRY_COUNT) {
                AppLogger.log(logTag, "Retrying model response (attempt ${attempt + 2}/$MODEL_RESPONSE_SILENT_RETRY_COUNT)")
            }
        }
    }
    throw requireNotNull(lastError)
}

private const val MODEL_RESPONSE_SILENT_RETRY_COUNT = 3
