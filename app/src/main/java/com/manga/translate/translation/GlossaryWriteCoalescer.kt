package com.manga.translate.translation

import com.manga.translate.storage.GlossaryStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class GlossaryWriteCoalescer(
    private val glossaryStore: GlossaryStore,
    private val targetKeyProvider: () -> String,
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class ScopeKey(val folderPath: String, val targetKey: String)

    private class PendingWrite(
        val additions: Map<String, String>,
        val completion: CompletableDeferred<Unit>
    )

    private class ScopeState {
        val mutex = Mutex()
        var glossary: LinkedHashMap<String, String>? = null
        var pending: PendingWrite? = null
        var lastWriteAt = 0L
    }

    private val states = ConcurrentHashMap<ScopeKey, ScopeState>()

    private fun scopeKey(folder: File): ScopeKey = ScopeKey(
        folder.absolutePath,
        targetKeyProvider().trim().lowercase()
    )

    private fun stateFor(key: ScopeKey): ScopeState =
        states.computeIfAbsent(key) { ScopeState() }

    /** Queues throttled additions; use flush to persist them, or throttleMillis = 0 for write-through. */
    suspend fun submit(folder: File, additions: Map<String, String>) {
        if (additions.isEmpty()) return
        val key = scopeKey(folder)
        val state = stateFor(key)
        state.mutex.withLock {
            val merged = LinkedHashMap(state.pending?.additions.orEmpty())
            additions.forEach { (entry, translation) ->
                if (entry.isNotBlank() && translation.isNotBlank()) merged[entry] = translation
            }
            if (merged.isEmpty()) return@withLock
            val pending = state.pending
            if (pending == null) {
                PendingWrite(merged, CompletableDeferred()).also { state.pending = it }
            } else {
                state.pending = PendingWrite(merged, pending.completion)
            }
            val shouldWrite = clock() - state.lastWriteAt >= throttleMillis
            if (shouldWrite) writeLocked(folder, key, state)
        }
    }

    suspend fun writeNow(folder: File, snapshot: Map<String, String>) {
        val key = scopeKey(folder)
        val state = stateFor(key)
        val completion = state.mutex.withLock {
            state.pending?.completion?.cancel()
            val pending = PendingWrite(LinkedHashMap(snapshot), CompletableDeferred())
            state.pending = pending
            writeLocked(folder, key, state)
            pending.completion
        }
        completion.await()
    }

    suspend fun flush(folder: File) {
        val key = scopeKey(folder)
        val state = states[key] ?: return
        val completion = state.mutex.withLock {
            state.pending?.let {
                writeLocked(folder, key, state)
                it.completion
            }
        } ?: return
        completion.await()
    }

    suspend fun flushAll() {
        for (key in states.keys.toList()) flush(File(key.folderPath))
    }

    private suspend fun writeLocked(folder: File, key: ScopeKey, state: ScopeState) {
        val pending = state.pending ?: return
        val base = state.glossary ?: glossaryStore.load(folder, key.targetKey).toMutableMap().also {
            state.glossary = LinkedHashMap(it)
        }
        pending.additions.forEach { (entry, translation) -> base[entry] = translation }
        val snapshot = LinkedHashMap(base)
        try {
            withContext(Dispatchers.IO) { glossaryStore.save(folder, snapshot, key.targetKey) }
            state.glossary = snapshot
            state.pending = null
            state.lastWriteAt = clock()
            pending.completion.complete(Unit)
        } catch (error: Throwable) {
            pending.completion.completeExceptionally(error)
            state.pending = null
            throw error
        }
    }

    companion object { private const val DEFAULT_THROTTLE_MILLIS = 3_000L }
}
