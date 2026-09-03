package com.manga.translate.translation

import com.manga.translate.platform.AppLogger
import com.manga.translate.storage.GlossaryStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Throttles `glossary.json` writes during batch translation.
 *
 * Callers submit a full glossary snapshot per change. Snapshots are coalesced per
 * folder, so a burst of successful pages produces one write instead of one write
 * per page. The newest submitted snapshot always wins, which keeps the on-disk
 * file a superset-consistent view of the in-memory glossary; [flush] and
 * [writeNow] guarantee the final state is persisted.
 *
 * A [throttleMillis] of `0` disables coalescing: every [submit] writes through
 * immediately. Use it when the write must stay ordered with another persistence
 * step (e.g. the glossary must be durable before the page's `*.json` lands, so a
 * process death cannot lose already-applied entries).
 *
 * **Known limitation**: If the process is killed by the system during the
 * throttle window (Low Memory Killer, vendor cleanup, force-stop, crash, or
 * shutdown), the pending snapshot is lost. This affects glossary context for
 * subsequent pages in batch translation, not already-saved translations (their
 * `*.json` files contain the terms that were applied). The short window (3s)
 * and low probability of system kills make this an acceptable tradeoff for
 * reducing I/O during batch operations.
 *
 * This changes write frequency only. Load, merge and persistence semantics stay
 * in [GlossaryStore].
 */
internal class GlossaryWriteCoalescer(
    private val glossaryStore: GlossaryStore,
    private val targetKeyProvider: () -> String,
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class FolderState {
        val mutex = Mutex()
        var pending: Map<String, String>? = null
        var lastWriteAt = 0L
    }

    private val states = ConcurrentHashMap<String, FolderState>()

    private fun stateFor(folder: File): FolderState =
        states.computeIfAbsent(folder.absolutePath) { FolderState() }

    /** Records [snapshot] and writes it only if the throttle window has elapsed. */
    suspend fun submit(folder: File, snapshot: Map<String, String>) {
        val state = stateFor(folder)
        state.mutex.withLock {
            state.pending = snapshot
            if (clock() - state.lastWriteAt >= throttleMillis) {
                writeLocked(folder, state)
            }
        }
    }

    /** Writes [snapshot] immediately, superseding anything pending for [folder]. */
    suspend fun writeNow(folder: File, snapshot: Map<String, String>) {
        val state = stateFor(folder)
        state.mutex.withLock {
            state.pending = snapshot
            writeLocked(folder, state)
        }
    }

    /** Writes the pending snapshot for [folder], if any. */
    suspend fun flush(folder: File) {
        val state = states[folder.absolutePath] ?: return
        state.mutex.withLock {
            if (state.pending != null) {
                writeLocked(folder, state)
            }
        }
    }

    /** Writes pending snapshots for every folder touched so far. */
    suspend fun flushAll() {
        for (path in states.keys.toList()) {
            flush(File(path))
        }
    }

    private suspend fun writeLocked(folder: File, state: FolderState) {
        val snapshot = state.pending ?: return
        try {
            withContext(Dispatchers.IO) {
                glossaryStore.save(folder, snapshot, targetKeyProvider())
            }
            state.pending = null
            state.lastWriteAt = clock()
        } catch (e: Exception) {
            // Keep the snapshot pending so a later flush retries it.
            AppLogger.log("Glossary", "Failed to persist glossary for ${folder.name}", e)
        }
    }

    companion object {
        private const val DEFAULT_THROTTLE_MILLIS = 3_000L
    }
}
