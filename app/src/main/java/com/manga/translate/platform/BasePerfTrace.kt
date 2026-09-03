package com.manga.translate.platform

/**
 * Base infrastructure for optional performance tracing. Subclasses decide what to collect
 * (stage durations vs. HTTP attempt metrics) but share the enabled-gate, clock source, and
 * log sink.
 *
 * The [enabled] flag gates all sampling and logging — disabled traces do nothing and never
 * touch the clock. This keeps normal translation runs free of timing overhead.
 */
internal abstract class BasePerfTrace(
    protected val tag: String,
    protected val operation: String,
    protected val enabled: Boolean,
    protected val clockNanos: () -> Long = System::nanoTime,
    protected val log: (String, String) -> Unit = { traceTag, message ->
        AppLogger.log(traceTag, message)
    }
)
