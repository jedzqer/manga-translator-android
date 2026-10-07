package com.manga.translate

import com.manga.translate.network.DynamicConcurrencyLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkConcurrencyTest {
    @Test
    fun `release wakes waiters and cancelling holder returns permit`() = runBlocking {
        withTimeout(5_000) {
            val limiter = DynamicConcurrencyLimiter()
            val entered = CompletableDeferred<Unit>()
            val holder = launch {
                limiter.withPermit(1) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            var ran = false
            val waiter = launch { limiter.withPermit(1) { ran = true } }
            repeat(5) { yield() }
            assertTrue(!ran)
            holder.cancelAndJoin()
            waiter.join()
            assertTrue(ran)
            limiter.withPermit(1) { }
        }
    }

    @Test
    fun `cancelled waiter does not consume permit and changed limits take effect`() = runBlocking {
        withTimeout(5_000) {
            val limiter = DynamicConcurrencyLimiter()
            val entered = CompletableDeferred<Unit>()
            val holder = launch {
                limiter.withPermit(1) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()
            val cancelled = launch { limiter.withPermit(1) { error("Cancelled waiter ran") } }
            yield()
            cancelled.cancelAndJoin()
            // A new caller may raise the limit while an earlier call is still active.
            limiter.withPermit(2) { }
            var ran = false
            val lowerLimit = launch { limiter.withPermit(1) { ran = true } }
            yield()
            assertTrue(!ran)
            holder.cancelAndJoin()
            lowerLimit.join()
            assertTrue(ran)
        }
    }

    @Test
    fun `contention never exceeds limit and exceptions release permits`() = runBlocking {
        withTimeout(5_000) {
            val limiter = DynamicConcurrencyLimiter()
            var active = 0
            var peak = 0
            var completed = 0
            coroutineScope {
                repeat(500) {
                    launch {
                        limiter.withPermit(3) {
                            active++
                            peak = maxOf(peak, active)
                            yield()
                            active--
                            completed++
                        }
                    }
                }
            }
            assertEquals(3, peak)
            assertEquals(500, completed)
            try {
                limiter.withPermit(1) { error("failed") }
            } catch (_: IllegalStateException) { }
            limiter.withPermit(1) { }
        }
    }
}
