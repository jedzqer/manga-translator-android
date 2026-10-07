package com.manga.translate.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Network policy; page counts extend a call, never connection establishment. */
internal object RequestTimeoutPolicy {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val TEXT_WRITE_TIMEOUT_MS = 60_000
    const val MAX_CALL_TIMEOUT_MS = 900_000
    const val MAX_BATCH_TIMEOUT_MS = 1_200_000L

    fun callTimeoutMs(baseTimeoutMs: Int, textPages: Int = 1): Int =
        (baseTimeoutMs.toLong().coerceAtLeast(1_000) * textPages.coerceAtLeast(1))
            .coerceAtMost(MAX_CALL_TIMEOUT_MS.toLong()).toInt()

    fun totalTimeoutMs(callTimeoutMs: Int, maxAttempts: Int): Long =
        (callTimeoutMs.toLong() * maxAttempts.coerceAtLeast(1)).coerceAtMost(MAX_BATCH_TIMEOUT_MS)
}

/** Monotonic deadline shared by network retries, backoff and model-response retries. */
internal class RequestTimeoutBudget(
    totalTimeoutMs: Long,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val startedAt = nanoTime()
    private val durationMs = totalTimeoutMs.coerceAtLeast(1)

    fun remainingMs(): Long =
        (durationMs - (nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)

    fun requireRemainingMs(): Long = remainingMs().takeIf { it > 0 }
        ?: throw LlmRequestException(LlmErrorCode.Timeout)

    fun attemptTimeoutMs(callTimeoutMs: Int): Int =
        minOf(callTimeoutMs.toLong(), requireRemainingMs()).toInt()

    suspend fun <T> run(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        // Box nullable successes so only expiration of our own timer becomes a timeout error.
        val result = withTimeoutOrNull(requireRemainingMs()) { Value(block()) }
            ?: throw LlmRequestException(LlmErrorCode.Timeout)
        requireRemainingMs()
        return result.value
    }

    private data class Value<T>(val value: T)
}

/** Installed around response retries, initialized at the first actual model request. */
internal class RequestBudgetContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestBudgetContext>

    private var budget: RequestTimeoutBudget? = null

    @Synchronized
    fun getOrStart(callTimeoutMs: Int, maxAttempts: Int): RequestTimeoutBudget =
        budget ?: RequestTimeoutBudget(RequestTimeoutPolicy.totalTimeoutMs(callTimeoutMs, maxAttempts))
            .also { budget = it }

    @Synchronized
    fun checkRemaining() {
        budget?.requireRemainingMs()
    }
}
