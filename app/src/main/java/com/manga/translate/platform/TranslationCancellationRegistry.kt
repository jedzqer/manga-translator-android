package com.manga.translate.platform

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Routes cancel requests (notification action, Service entry points) to the
 * component that currently owns a running translation.
 *
 * Each [register] call creates an independent slot and returns a
 * [Registration]; unregistering removes exactly that slot, so one owner can
 * never overwrite or clear another owner's handler.
 */
internal object TranslationCancellationRegistry {
    private val handlers = ConcurrentHashMap<Long, () -> Boolean>()
    private val nextKey = AtomicLong(1L)

    fun register(handler: () -> Boolean): Registration {
        val key = nextKey.getAndIncrement()
        handlers[key] = handler
        return Registration(key)
    }

    /** Invokes every active handler; true if any of them routed a cancel. */
    fun requestCancel(): Boolean {
        var requested = false
        for (handler in handlers.values) {
            if (handler.invoke()) {
                requested = true
            }
        }
        return requested
    }

    class Registration internal constructor(private val key: Long) {
        private val active = AtomicBoolean(true)

        /** Removes exactly this registration; safe to call more than once. */
        fun unregister() {
            if (active.compareAndSet(true, false)) {
                handlers.remove(key)
            }
        }
    }
}
