package com.manga.translate.platform

/**
 * Optional per-operation performance trace. The caller controls [enabled] so normal
 * translation runs do not pay for timing or emit additional log lines.
 *
 * Stage timings are logged as they complete and aggregated into a single summary line
 * by [logSummary]. Callers can attach page context (image size, tile count, bubble
 * count, retry count, HTTP status) via [attribute] so a stage timing can be read
 * against the work it actually did instead of being averaged into a single speed.
 */
internal class PerformanceTrace(
    tag: String,
    operation: String,
    enabled: Boolean,
    clockNanos: () -> Long = System::nanoTime,
    log: (String, String) -> Unit = { traceTag, message ->
        AppLogger.log(traceTag, message)
    }
) : BasePerfTrace(tag, operation, enabled, clockNanos, log) {
    private val durationsMs = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Int>()
    private val attributes = linkedMapOf<String, String>()

    suspend fun <T> measure(stage: String, block: suspend () -> T): T {
        if (!enabled) return block()
        val started = clockNanos()
        return try {
            block()
        } finally {
            recordStage(stage, started)
        }
    }

    fun <T> measureBlocking(stage: String, block: () -> T): T {
        if (!enabled) return block()
        val started = clockNanos()
        return try {
            block()
        } finally {
            recordStage(stage, started)
        }
    }

    /**
     * Records page context for the summary line. Values are only retained when the
     * trace is enabled, so callers may pass computed values without a guard.
     */
    fun attribute(name: String, value: Any?) {
        if (!enabled) return
        val rendered = value?.toString()?.takeIf { it.isNotBlank() } ?: return
        attributes[name] = rendered
    }

    fun logSummary() {
        if (!enabled) return
        if (durationsMs.isEmpty() && attributes.isEmpty()) return
        val stages = durationsMs.entries.joinToString(",") { (stage, elapsedMs) ->
            val runs = counts[stage] ?: 1
            if (runs > 1) "$stage=${elapsedMs}ms/${runs}x" else "$stage=${elapsedMs}ms"
        }
        val totalMs = durationsMs.values.sum()
        val context = attributes.entries.joinToString(" ") { (name, value) -> "$name=$value" }
        val message = buildString {
            append("Perf ")
            append(operation)
            append(" summary")
            if (stages.isNotEmpty()) {
                append(' ')
                append(stages)
                append(" total=${totalMs}ms")
            }
            if (context.isNotEmpty()) {
                append(' ')
                append(context)
            }
        }
        log(tag, message)
    }

    private fun recordStage(stage: String, startedNanos: Long) {
        val elapsedMs = ((clockNanos() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
        durationsMs[stage] = (durationsMs[stage] ?: 0L) + elapsedMs
        counts[stage] = (counts[stage] ?: 0) + 1
        log(tag, "Perf $operation stage=$stage elapsedMs=$elapsedMs")
    }
}
