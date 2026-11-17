package com.manga.translate.storage

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Rebuildable display hints only. Never used to decide whether to translate a page. */
internal class LibraryStatusCache(private val directory: File?) {
    data class Entry(val modified: Long, val size: Long, val translated: Boolean)

    fun load(folder: File): Map<String, Entry> = runCatching {
        val file = fileFor(folder) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        val json = JSONObject(file.readText())
        if (json.optInt("version") != 1) return emptyMap()
        val pages = json.getJSONObject("pages")
        buildMap {
            for (name in pages.keys()) {
                val entry = pages.getJSONObject(name)
                put(name, Entry(entry.getLong("modified"), entry.getLong("size"), entry.getBoolean("translated")))
            }
        }
    }.getOrDefault(emptyMap())

    fun save(folder: File, entries: Map<String, Entry>) {
        runCatching {
            val file = fileFor(folder) ?: return
            file.parentFile?.mkdirs()
            val pages = JSONObject()
            entries.forEach { (name, entry) ->
                pages.put(name, JSONObject().put("modified", entry.modified)
                    .put("size", entry.size).put("translated", entry.translated))
            }
            writeFileAtomically(file, JSONObject().put("version", 1).put("pages", pages).toString())
        }
    }

    private fun fileFor(folder: File): File? {
        val root = directory ?: return null
        val key = MessageDigest.getInstance("SHA-256").digest(folder.absolutePath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$key.json")
    }
}
