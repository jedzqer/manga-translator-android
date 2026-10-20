package com.manga.translate.settings

/** Successful automatic checks use rolling intervals, independent of calendar midnight. */
data class AutoUpdateCheckState(
    val lastSuccessAt: Long = 0L,
    val noUpdateCount: Int = 0
) {
    fun isDue(now: Long): Boolean =
        lastSuccessAt == 0L || now < lastSuccessAt || now - lastSuccessAt >= intervalMillis

    val intervalMillis: Long
        get() = INTERVAL_DAYS[(noUpdateCount - 1).coerceIn(0, INTERVAL_DAYS.lastIndex)] * DAY_MS

    fun afterSuccess(now: Long, hasUpdate: Boolean): AutoUpdateCheckState =
        AutoUpdateCheckState(now, if (hasUpdate) 0 else (noUpdateCount.coerceIn(0, INTERVAL_DAYS.size) + 1)
            .coerceAtMost(INTERVAL_DAYS.size))

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val INTERVAL_DAYS = longArrayOf(1, 1, 2, 3, 7, 14, 30)
    }
}
