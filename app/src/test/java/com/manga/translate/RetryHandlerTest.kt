package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.network.RetryHandler
import com.manga.translate.network.RetryMode
import com.manga.translate.network.RetryPolicy
import com.manga.translate.settings.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RetryHandlerTest {
    private val handler = RetryHandler(SettingsStore(ApplicationProvider.getApplicationContext<Context>()))

    @Test
    fun `only transient failures retry within the attempt budget`() {
        for (mode in RetryMode.entries) {
            val policy = RetryPolicy(3, mode)
            for (code in listOf("TIMEOUT", "NETWORK_ERROR", "HTTP 408", "HTTP 429", "HTTP 500", "HTTP 503", "HTTP 599")) {
                assertTrue(code, handler.canRetry(1, policy, code, null))
                assertFalse(handler.canRetry(3, policy, code, null))
            }
            for (code in listOf(null, "HTTP 400", "HTTP 401", "HTTP 403", "HTTP 404", "INVALID_RESPONSE", "HTTP 600")) {
                assertFalse(code, handler.canRetry(1, policy, code, null))
            }
        }
        assertTrue(handler.canRetry(1, RetryPolicy(3, RetryMode.CONFIGURABLE), "HTTP 400", "Server overloaded; try again later"))
    }

    @Test
    fun `backoff has jitter and stays positive and capped even for huge attempt counts`() {
        for (mode in RetryMode.entries) {
            for (attempt in listOf(1, 2, 3, 32, Int.MAX_VALUE)) {
                val samples = List(50) { handler.retryDelayMs(attempt, mode) }
                assertTrue(samples.all { it in 1..RetryHandler.RETRY_MAX_DELAY_MS.toLong() })
                assertTrue(samples.distinct().size > 1)
            }
        }
    }
}
