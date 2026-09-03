package com.manga.translate.storage

import com.manga.translate.platform.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class PageProgressStatus(val jsonValue: String) {
    PENDING("pending"),
    OCR_DONE("ocr_done"),
    SAVED("saved"),
    SKIPPED("skipped"),
    FAILED("failed");

    companion object {
        fun fromJson(value: String?): PageProgressStatus? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.jsonValue.equals(value, ignoreCase = true) }
        }
    }
}

data class PageProgressEntry(
    val status: PageProgressStatus,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Page-level progress for folder translation, used to resume interrupted tasks.
 *
 * The state of a folder is kept in an in-memory snapshot and flushed to disk on
 * a throttle, so a 1000-page folder no longer reads and rewrites the whole file
 * once per page transition (which grew quadratically with folder size and was
 * the main cause of batch translation slowing down in its second half).
 *
 * Durability contract: translated pages are authoritative in their own `*.json`
 * results, this file only records intent, so losing the last few unflushed
 * transitions cannot invalidate a translation. Callers must still [flush] on
 * task completion, cancellation, failure and Service teardown.
 */
internal class TranslationProgressStore(
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class FolderState {
        val mutex = Mutex()
        var entries: LinkedHashMap<String, PageProgressEntry>? = null
        var dirty = false
        var lastWriteAt = 0L
    }

    private val states = ConcurrentHashMap<String, FolderState>()

    private fun stateFor(folder: File): FolderState {
        return states.computeIfAbsent(folder.absolutePath) { FolderState() }
    }

    fun fileFor(folder: File): File = File(folder, FILE_NAME)

    /**
     * Returns the current page states, preferring the in-memory snapshot over disk
     * so callers observe transitions that have not been flushed yet.
     *
     * Takes the folder mutex: the snapshot is mutated in place by [update], so
     * copying it without the lock could race with a concurrent page transition.
     */
    suspend fun load(folder: File): Map<String, PageProgressEntry> {
        val state = stateFor(folder)
        return state.mutex.withLock {
            state.entries?.let { LinkedHashMap(it) } ?: readFromDisk(folder)
        }
    }

    private fun readFromDisk(folder: File): Map<String, PageProgressEntry> {
        val file = fileFor(folder)
        if (!file.exists()) return emptyMap()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            AppLogger.log("Progress", "Failed to load progress for ${folder.name}", e)
            emptyMap()
        }
    }

    suspend fun update(
        folder: File,
        imageName: String,
        status: PageProgressStatus,
        error: String? = null
    ) {
        if (imageName.isBlank()) return
        val state = stateFor(folder)
        state.mutex.withLock {
            val entries = state.entries ?: LinkedHashMap(readFromDisk(folder)).also {
                state.entries = it
            }
            entries[imageName] = PageProgressEntry(
                status = status,
                lastError = error,
                updatedAt = clock()
            )
            state.dirty = true
            val now = clock()
            if (now - state.lastWriteAt >= throttleMillis) {
                writeLocked(folder, state)
            }
        }
    }

    /**
     * Flushes every folder with unflushed state, then drops the in-memory
     * snapshots. Called on task teardown, where a batch or collection task may
     * have touched many folders.
     *
     * Releasing keeps memory bounded across long sessions and makes the next
     * [load] re-read from disk, so progress files changed from outside the task
     * (import, export, manual restore) are not shadowed by a stale snapshot.
     */
    suspend fun flushAll() {
        for (path in states.keys.toList()) {
            val folder = File(path)
            flush(folder)
            states[path]?.let { state ->
                state.mutex.withLock {
                    if (!state.dirty) {
                        state.entries = null
                    }
                }
            }
        }
    }

    /** Writes the pending snapshot if anything is unflushed. Safe to call repeatedly. */
    suspend fun flush(folder: File) {
        val state = states[folder.absolutePath] ?: return
        state.mutex.withLock {
            if (state.dirty) {
                writeLocked(folder, state)
            }
        }
    }

    suspend fun clear(folder: File) {
        val state = stateFor(folder)
        state.mutex.withLock {
            state.entries = null
            state.dirty = false
            state.lastWriteAt = 0L
            val file = fileFor(folder)
            if (file.exists()) {
                if (!file.delete()) {
                    AppLogger.log("Progress", "Failed to delete progress for ${folder.name}")
                }
            }
        }
    }

    private fun writeLocked(folder: File, state: FolderState) {
        val entries = state.entries ?: return
        writeAtomic(folder, entries)
        state.dirty = false
        state.lastWriteAt = clock()
    }

    private fun writeAtomic(folder: File, entries: Map<String, PageProgressEntry>) {
        val file = fileFor(folder)
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            AppLogger.log("Progress", "Cannot create parent directory for ${file.absolutePath}")
            return
        }
        try {
            if (!writeFileAtomically(file, serialize(entries))) {
                AppLogger.log("Progress", "Atomic rename failed for ${file.absolutePath}")
            }
        } catch (e: Exception) {
            AppLogger.log("Progress", "Failed to write progress for ${folder.name}", e)
        }
    }

    private fun serialize(entries: Map<String, PageProgressEntry>): String {
        val pages = JSONObject()
        for ((name, entry) in entries) {
            pages.put(name, JSONObject().apply {
                put("status", entry.status.jsonValue)
                if (!entry.lastError.isNullOrBlank()) put("lastError", entry.lastError)
                put("updatedAt", entry.updatedAt)
            })
        }
        return JSONObject()
            .put("version", VERSION)
            .put("pages", pages)
            .toString()
    }

    private fun parse(raw: String): Map<String, PageProgressEntry> {
        val json = JSONObject(raw)
        val pages = json.optJSONObject("pages") ?: return emptyMap()
        val out = LinkedHashMap<String, PageProgressEntry>(pages.length())
        val keys = pages.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = pages.optJSONObject(key) ?: continue
            val status = PageProgressStatus.fromJson(obj.optString("status")) ?: continue
            out[key] = PageProgressEntry(
                status = status,
                lastError = obj.optString("lastError").takeIf { it.isNotBlank() },
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        }
        return out
    }

    companion object {
        private const val FILE_NAME = ".translation_progress.json"
        private const val VERSION = 1
        private const val DEFAULT_THROTTLE_MILLIS = 2_000L
    }
}
