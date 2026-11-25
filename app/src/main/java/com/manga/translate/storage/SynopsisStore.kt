package com.manga.translate.storage

import com.manga.translate.platform.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Persists the rolling full-translation synopsis independently of the glossary. */
class SynopsisStore {
    internal data class Snapshot(val text: String = "", val completedPages: Set<String> = emptySet())

    fun load(folder: File, targetKey: String = DEFAULT_TARGET_KEY): String {
        val file = fileFor(folder, targetKey)
        if (!file.exists()) return ""
        return try { JSONObject(file.readText()).optString("synopsis").trim() }
        catch (e: Exception) {
            AppLogger.log("SynopsisStore", "Failed to load synopsis for ${folder.name}", e)
            ""
        }
    }

    fun save(folder: File, synopsis: String, targetKey: String = DEFAULT_TARGET_KEY) {
        write(fileFor(folder, targetKey), Snapshot(synopsis))
    }

    /** Keep the loaded snapshot current until the folder workflow finishes updating it. */
    internal suspend fun <T> withUpdate(
        folder: File,
        targetKey: String,
        action: suspend (Snapshot, (Snapshot) -> Unit) -> T
    ): T {
        val file = withContext(Dispatchers.IO) { fileFor(folder.canonicalFile, targetKey) }
        return updateLocks.computeIfAbsent(file.path) { Mutex() }.withLock {
            val initial = withContext(Dispatchers.IO) { read(file) }
            action(initial) { write(file, it) }
        }
    }

    private fun read(file: File): Snapshot {
        if (!file.exists()) return Snapshot()
        val json = JSONObject(file.readText())
        val synopsis = json.getString("synopsis")
        val completedPages = buildSet {
            val pages = json.optJSONArray("completedPages") ?: return@buildSet
            for (i in 0 until pages.length()) {
                val name = pages.opt(i)
                if (name is String && name.isNotBlank()) add(name)
            }
        }
        return Snapshot(synopsis, completedPages)
    }

    private fun write(file: File, snapshot: Snapshot) {
        val json = JSONObject().put("synopsis", snapshot.text)
        if (snapshot.completedPages.isNotEmpty()) {
            json.put("completedPages", JSONArray(snapshot.completedPages))
        }
        writeFileAtomically(file, json.toString())
    }

    private fun fileFor(folder: File, targetKey: String): File {
        val key = targetKey.trim().lowercase()
        return File(folder, if (key.isBlank() || key == DEFAULT_TARGET_KEY) "synopsis.json" else "synopsis_$key.json")
    }

    companion object {
        private const val DEFAULT_TARGET_KEY = "zh_hans"
        private val updateLocks = ConcurrentHashMap<String, Mutex>()
    }
}
