package com.manga.translate.network

import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

/**
 * 重试判定与退避：重试次数来自 SettingsStore（loadApiRetryCount），
 * 可重试范围与原始实现一致——网络错误、超时、HTTP 408/429/5xx、明确的暂时不可用响应。
 * 是否继续尝试与退避等待分离；仅可重试错误等待带随机扰动的有上限指数退避。
 */
internal class RetryHandler(private val settingsStore: SettingsStore) {
    /** 门面传入默认 RETRY_COUNT 时改用 SettingsStore 的配置值，否则使用调用方显式值。 */
    fun buildRetryPolicy(retryCount: Int): RetryPolicy {
        val configuredRetryCount = settingsStore.loadApiRetryCount()
        return RetryPolicy(
            maxAttempts = if (retryCount == RETRY_COUNT) configuredRetryCount else retryCount.coerceAtLeast(1),
            mode = RetryMode.CONFIGURABLE
        )
    }

    fun canRetry(
        attempt: Int,
        retryPolicy: RetryPolicy,
        errorCode: String?,
        errorBody: String?
    ): Boolean = attempt < retryPolicy.maxAttempts && shouldRetry(errorCode, errorBody)

    suspend fun backoffBeforeRetry(
        attempt: Int, retryPolicy: RetryPolicy, errorCode: String?, retryAfter: String? = null,
        deadline: RequestTimeoutBudget
    ): Boolean {
        val jitter = retryDelayMs(attempt, retryPolicy.mode)
        val serverDelay = retryAfterMillis(retryAfter)
        val delayMs = maxOf(jitter, serverDelay ?: 0L)
        currentCoroutineContext().ensureActive()
        // An otherwise retryable request stopped by its deadline is a timeout,
        // not a terminal failure with the preceding HTTP status.
        if (deadline.remainingMs() <= delayMs) throw LlmRequestException(LlmErrorCode.Timeout)
        AppLogger.log(
            "LlmClient",
            "Retrying request after ${delayMs}ms delay (attempt ${attempt + 1}/${retryPolicy.maxAttempts}, error=$errorCode)"
        )
        delay(delayMs)
        deadline.requireRemainingMs()
        return true
    }

    internal fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.all { it in '0'..'9' }) {
            return (raw.toLongOrNull() ?: Long.MAX_VALUE).coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
        }
        return runCatching {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            format.isLenient = false
            val position = java.text.ParsePosition(0)
            val expires = format.parse(raw, position)?.time ?: return null
            if (position.index != raw.length) return null
            (expires - nowMillis).coerceAtLeast(0L)
        }.getOrNull()
    }

    internal fun retryDelayMs(attempt: Int, mode: RetryMode): Long {
        val base = if (mode == RetryMode.DEFAULT) RETRY_BASE_DELAY_MS else CONFIGURED_RETRY_DELAY_MS
        val ceiling = (base.toLong() shl (attempt - 1).coerceIn(0, 16))
            .coerceAtMost(RETRY_MAX_DELAY_MS.toLong())
        return Random.nextLong(ceiling / 2, ceiling + 1)
    }

    private fun shouldRetry(errorCode: String?, errorBody: String?): Boolean {
        if (errorCode == null) return false
        if (errorCode == "TIMEOUT" || errorCode == "NETWORK_ERROR") return true
        val status = errorCode.removePrefix("HTTP ").toIntOrNull()
        val body = errorBody.orEmpty().lowercase()
        if (permanentFailures.any(body::contains)) return false
        if (status == 408 || status == 429 || status != null && status in 500..599) return true
        return status == 400 && temporaryGatewayFailures.any(body::contains)
    }

    companion object {
        private val permanentFailures = listOf(
            "insufficient_quota", "insufficient quota", "billing_hard_limit", "billing_not_active",
            "billing hard limit", "payment required", "insufficient balance", "insufficient credits",
            "credit balance is too low", "out of credits", "quota exhausted", "exceeded your current quota",
            "invalid_api_key", "invalid api key", "unauthorized", "forbidden", "authentication_error",
            "invalid parameter", "invalid_parameter", "unknown parameter", "unsupported parameter",
            "content_filter", "content policy violation", "safety filter", "refusal"
        )
        private val temporaryGatewayFailures = listOf(
            "temporarily unavailable", "temporary unavailable", "service unavailable", "server busy",
            "server overloaded", "server is overloaded", "service overloaded", "model is overloaded"
        )
        const val RETRY_COUNT = 3
        const val RETRY_BASE_DELAY_MS = 750
        const val RETRY_MAX_DELAY_MS = 4_000
        const val CONFIGURED_RETRY_DELAY_MS = 3_000
    }
}

internal data class RetryPolicy(
    val maxAttempts: Int,
    val mode: RetryMode
)

internal enum class RetryMode {
    DEFAULT,
    CONFIGURABLE
}
