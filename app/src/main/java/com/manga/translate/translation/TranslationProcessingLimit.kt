package com.manga.translate.translation

import com.manga.translate.settings.SettingsStore

/**
 * Heap-based baseline enlarged to fit one effective request batch.
 * This is a page-count heuristic, not a reservation of native/GPU memory.
 */
internal fun translationProcessingLimit(
    batchPages: Int,
    maxHeapBytes: Long,
    usedHeapBytes: Long
): Int {
    val headroom = (maxHeapBytes - usedHeapBytes).coerceAtLeast(0)
    val allowance = minOf(maxHeapBytes.coerceAtLeast(0) / 8, headroom / 4)
    return (allowance / (1024L * 1024L)).coerceIn(20, 100).toInt().coerceAtLeast(batchPages)
}

internal fun SettingsStore.resolveTranslationProcessingLimit(useVlDirectTranslate: Boolean = false): Int {
    val runtime = Runtime.getRuntime()
    return translationProcessingLimit(
        loadTranslationBatchPages(useVlDirectTranslate), runtime.maxMemory(),
        runtime.totalMemory() - runtime.freeMemory()
    )
}
