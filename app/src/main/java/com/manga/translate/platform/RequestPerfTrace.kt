package com.manga.translate.platform

/**
 * Per-request performance summary for LLM/OCR HTTP calls.
 *
 * The retry loop already logs each individual failure. This aggregates one line per
 * logical request so latency can be read together with the attempt count and the
 * HTTP status of each attempt — the plan's batch-performance baseline needs to tell
 * server-side throttling (429/5xx with retries) apart from slow local queueing.
 *
 * Disabled traces do nothing and never sample the clock.
 */
internal class RequestPerfTrace(
    tag: String,
    operation: String,
    enabled: Boolean,
    clockNanos: () -> Long = System::nanoTime,
    log: (String, String) -> Unit = { traceTag, message ->
        AppLogger.log(traceTag, message)
    }
) : BasePerfTrace(tag, operation, enabled, clockNanos, log) {
    private val statuses = mutableListOf<String>()
    private var attempts = 0
    private var startedNanos = 0L
    private var attemptStartedNanos = 0L
    private var lastAttemptMs = 0L

    fun beginAttempt() {
        if (!enabled) return
        val now = clockNanos()
        if (attempts == 0) {
            startedNanos = now
        }
        attempts += 1
        attemptStartedNanos = now
    }

    /** Records the outcome of the current attempt: an HTTP code or an error code. */
    fun recordStatus(status: String) {
        if (!enabled) return
        statuses += status
        lastAttemptMs = elapsedMsSince(attemptStartedNanos)
    }

    fun logSummary(outcome: String) {
        if (!enabled || attempts == 0) return
        val totalMs = elapsedMsSince(startedNanos)
        val statusText = statuses.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "none"
        log(
            tag,
            "Perf $operation $outcome attempts=$attempts status=$statusText " +
                "lastAttemptMs=$lastAttemptMs totalMs=$totalMs"
        )
    }

    private fun elapsedMsSince(startNanos: Long): Long {
        return ((clockNanos() - startNanos) / 1_000_000L).coerceAtLeast(0L)
    }
}
