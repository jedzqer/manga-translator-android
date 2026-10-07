package com.manga.translate.translation

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Pull work only when a worker finishes its previous item, including downstream
 * sends. A semaphore inside one coroutine per page does not bound retained results.
 * The input holds lightweight references; workers never accumulate task futures.
 */
internal suspend fun <T> List<T>.forEachBounded(
    concurrency: Int,
    action: suspend (index: Int, item: T) -> Unit
) {
    require(concurrency > 0)
    coroutineScope {
        val next = AtomicInteger(0)
        List(minOf(concurrency, size)) {
            launch {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val index = next.getAndIncrement()
                    if (index >= size) break
                    action(index, this@forEachBounded[index])
                }
            }
        }.joinAll()
    }
}

/**
 * Fixed workers with rendezvous result lanes. A slow earlier page cannot cause an
 * unbounded reorder buffer: each worker retains at most one completed result.
 */
internal suspend fun <T, R> List<T>.forEachOrderedBounded(
    concurrency: Int,
    prepare: suspend (T) -> R,
    consume: suspend (R) -> Unit
) = coroutineScope {
    require(concurrency > 0)
    val workers = minOf(concurrency, size)
    val lanes = List(workers) { Channel<R>(Channel.RENDEZVOUS) }
    val jobs = lanes.mapIndexed { worker, lane ->
        launch {
            try {
                for (index in worker until size step workers) {
                    currentCoroutineContext().ensureActive()
                    lane.send(prepare(this@forEachOrderedBounded[index]))
                }
            } finally {
                lane.close()
            }
        }
    }
    try {
        for (index in indices) consume(lanes[index % workers].receive())
        jobs.joinAll()
    } finally {
        jobs.forEach { it.cancel() }
        lanes.forEach { it.cancel() }
    }
}
