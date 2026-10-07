package com.manga.translate

import com.manga.translate.translation.forEachOrderedBounded
import com.manga.translate.translation.forEachBounded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPageWorkTest {
    @Test
    fun `slow first page bounds reorder memory and results retain page order`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val visited = mutableListOf<Int>()
        val received = mutableListOf<Int>()
        val job = launch {
            (0 until 10_000).toList().forEachOrderedBounded(3, prepare = { page ->
                visited.add(page)
                if (page == 0) gate.await()
                page
            }, consume = { received.add(it) })
        }
        repeat(10) { yield() }
        assertEquals(listOf(0, 1, 2), visited)
        assertTrue(received.isEmpty())
        gate.complete(Unit)
        job.join()
        assertEquals((0 until 10_000).toList(), received)
    }

    @Test
    fun `ordered workers cancel while downstream is blocked`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var prepared = 0
        val job = launch {
            (0 until 10_000).toList().forEachOrderedBounded(3, prepare = {
                prepared++
                it
            }, consume = { gate.await() })
        }
        repeat(10) { yield() }
        assertTrue(prepared <= 4)
        job.cancel()
        job.join()
        assertTrue(job.children.none())
        emptyList<Int>().forEachOrderedBounded(2, { error("unexpected") }, { error("unexpected") })
    }

    @Test
    fun `slow translation bounds prepared pages even with ten thousand inputs`() = runBlocking {
        val workers = 3
        val capacity = 4
        val ready = Channel<Int>(capacity)
        var prepared = 0
        val producer = launch {
            (0 until 10_000).toList().forEachBounded(workers) { _, page ->
                prepared++
                ready.send(page)
            }
            ready.close()
        }
        // All workers suspend at the downstream queue, without preparing the rest.
        repeat(10) { yield() }
        assertEquals(capacity + workers, prepared)
        val received = mutableSetOf<Int>()
        for (page in ready) assertTrue(received.add(page))
        producer.join()
        assertEquals(10_000, received.size)
    }

    @Test
    fun `worker count and exactly once execution are independent of input size`() = runBlocking {
        val visits = IntArray(10_000)
        val jobs = mutableSetOf<Any>()
        visits.indices.toList().forEachBounded(5) { index, item ->
            assertEquals(index, item)
            jobs.add(currentCoroutineContext().job)
            visits[item]++
            yield()
        }
        assertEquals(5, jobs.size)
        assertTrue(visits.all { it == 1 })
    }

    @Test
    fun `cancellation stops pending preparation and all blocked senders`() = runBlocking {
        val queue = Channel<Int>(1)
        var prepared = 0
        val job = launch {
            (0 until 10_000).toList().forEachBounded(2) { _, value ->
                prepared++
                queue.send(value)
            }
        }
        repeat(10) { yield() }
        job.cancel()
        job.join()
        assertEquals(3, prepared)
        assertTrue(job.children.none())
        queue.cancel()
    }

    @Test
    fun `worker failure cancels siblings and propagates to caller`() = runBlocking {
        val blocked = CompletableDeferred<Unit>()
        var failed = false
        try {
            coroutineScope {
                (0 until 10_000).toList().forEachBounded(3) { _, value ->
                    if (value == 1) error("broken page")
                    blocked.await()
                }
            }
        } catch (e: IllegalStateException) {
            failed = e.message == "broken page"
        }
        assertTrue(failed)
    }

    @Test
    fun `empty input does not start work`() = runBlocking {
        emptyList<Int>().forEachBounded(4) { _, _ -> error("unexpected work") }
    }
}
