package com.manga.translate

import com.manga.translate.network.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RequestTimeoutBudgetTest {
    @Test
    fun `page extension and retries are capped without integer overflow`() {
        assertEquals(120_000, RequestTimeoutPolicy.callTimeoutMs(60_000, 2))
        assertEquals(900_000, RequestTimeoutPolicy.callTimeoutMs(300_000, 8))
        assertEquals(900_000, RequestTimeoutPolicy.callTimeoutMs(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(1_200_000L, RequestTimeoutPolicy.totalTimeoutMs(900_000, 3))
        assertEquals(180_000L, RequestTimeoutPolicy.totalTimeoutMs(60_000, 3))
        assertEquals(60_000L, RequestTimeoutPolicy.totalTimeoutMs(60_000, 1))
    }

    @Test
    fun `second attempt receives only time left in the batch`() {
        var nanos = 0L
        val budget = RequestTimeoutBudget(1_200_000) { nanos }
        assertEquals(900_000, budget.attemptTimeoutMs(900_000))
        nanos += 900_000L * 1_000_000
        assertEquals(300_000, budget.attemptTimeoutMs(900_000))
        nanos += 4_000L * 1_000_000
        assertEquals(296_000, budget.attemptTimeoutMs(900_000))
        nanos += 296_000L * 1_000_000
        val error = assertThrows(LlmRequestException::class.java) { budget.attemptTimeoutMs(900_000) }
        assertEquals(LlmErrorCode.Timeout, error.errorCode)
    }

    @Test
    fun `budget retains nullable success and converts its own expiration`() = runBlocking {
        assertNull(RequestTimeoutBudget(1_000).run { null })
        try {
            RequestTimeoutBudget(30).run { delay(5_000) }
            fail("Expected budget expiration")
        } catch (error: LlmRequestException) {
            assertEquals(LlmErrorCode.Timeout, error.errorCode)
        }
    }

    @Test
    fun `caller cancellation and enclosing timeouts stay cancellations`() = runBlocking {
        val original = CancellationException("user cancelled")
        try {
            RequestTimeoutBudget(1_000).run { throw original }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals(original.message, error.message)
        }
        try {
            withTimeout(30) { RequestTimeoutBudget(5_000).run { delay(10_000) } }
            fail("Expected parent timeout")
        } catch (_: TimeoutCancellationException) {
            // Parent cancellation must not be converted into a retryable request error.
        }
    }

    @Test
    fun `transport separates connect write and complete call limits`() {
        val client = HttpTransport().getOrBuildClient(900_000, RequestTimeoutPolicy.TEXT_WRITE_TIMEOUT_MS)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.writeTimeoutMillis)
        assertEquals(900_000, client.readTimeoutMillis)
        assertEquals(900_000, client.callTimeoutMillis)
    }
}
