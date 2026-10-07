package com.manga.translate.network

import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import kotlinx.coroutines.delay
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
    ): Boolean = attempt < retryPolicy.maxAttempts &&
        shouldRetry(errorCode, errorBody, retryPolicy.mode)

    suspend fun backoffBeforeRetry(attempt: Int, retryPolicy: RetryPolicy, errorCode: String?) {
        val delayMs = retryDelayMs(attempt, retryPolicy.mode)
        AppLogger.log(
            "LlmClient",
            "Retrying request after ${delayMs}ms delay (attempt ${attempt + 1}/${retryPolicy.maxAttempts}, error=$errorCode)"
        )
        delay(delayMs)
    }

    internal fun retryDelayMs(attempt: Int, mode: RetryMode): Long {
        val base = if (mode == RetryMode.DEFAULT) RETRY_BASE_DELAY_MS else CONFIGURED_RETRY_DELAY_MS
        val ceiling = (base.toLong() shl (attempt - 1).coerceIn(0, 16))
            .coerceAtMost(RETRY_MAX_DELAY_MS.toLong())
        return Random.nextLong(ceiling / 2, ceiling + 1)
    }

    private fun shouldRetry(
        errorCode: String?,
        errorBody: String?,
        mode: RetryMode
    ): Boolean {
        return when (mode) {
            RetryMode.DEFAULT -> shouldRetryWithBackoff(errorCode)
            RetryMode.CONFIGURABLE -> shouldRetryWithConfiguredMode(errorCode, errorBody)
        }
    }

    private fun shouldRetryWithBackoff(errorCode: String?): Boolean {
        if (errorCode == null) return false
        if (errorCode == "TIMEOUT" || errorCode == "NETWORK_ERROR" || errorCode == "HTTP 408" || errorCode == "HTTP 429") {
            return true
        }
        if (!errorCode.startsWith("HTTP ")) {
            return false
        }
        val status = errorCode.removePrefix("HTTP ").toIntOrNull() ?: return false
        return status in 500..599
    }

    private fun shouldRetryWithConfiguredMode(errorCode: String?, errorBody: String?): Boolean {
        if (errorCode == null) return false
        if (errorCode == "TIMEOUT" || errorCode == "NETWORK_ERROR" || errorCode == "HTTP 408" || errorCode == "HTTP 429") {
            return true
        }
        val status = errorCode.removePrefix("HTTP ").toIntOrNull()
        if (status != null && status in 500..599) {
            return true
        }
        if (errorBody != null) {
            val normalizedBody = errorBody.lowercase()
            if (
                normalizedBody.contains("temporarily unavailable") ||
                normalizedBody.contains("temporary unavailable") ||
                normalizedBody.contains("service unavailable") ||
                normalizedBody.contains("try again later") ||
                normalizedBody.contains("server busy") ||
                normalizedBody.contains("overloaded")
            ) {
                return true
            }
        }
        return false
    }

    companion object {
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
