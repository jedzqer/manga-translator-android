package com.manga.translate.storage

import com.manga.translate.platform.AppLogger
import java.io.File
import org.json.JSONObject

/** Persists the rolling full-translation synopsis independently of the glossary. */
class SynopsisStore {
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
        writeFileAtomically(fileFor(folder, targetKey), JSONObject().put("synopsis", synopsis).toString())
    }

    private fun fileFor(folder: File, targetKey: String): File {
        val key = targetKey.trim().lowercase()
        return File(folder, if (key.isBlank() || key == DEFAULT_TARGET_KEY) "synopsis.json" else "synopsis_$key.json")
    }
    companion object { private const val DEFAULT_TARGET_KEY = "zh_hans" }
}
