package com.manga.translate.storage

import com.manga.translate.platform.AppLogger
import java.io.File
import org.json.JSONObject

class GlossaryStore {
    fun load(folder: File, targetKey: String = DEFAULT_TARGET_KEY): MutableMap<String, String> {
        val file = glossaryFileFor(folder, targetKey)
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) {
                val value = json.optString(key).trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    map[key] = value
                }
            }
            map
        } catch (e: Exception) {
            AppLogger.log("GlossaryStore", "Failed to load glossary for ${folder.name}", e)
            mutableMapOf()
        }
    }

    fun save(folder: File, glossary: Map<String, String>, targetKey: String = DEFAULT_TARGET_KEY) {
        val json = JSONObject()
        for ((key, value) in glossary) {
            json.put(key, value)
        }
        val file = glossaryFileFor(folder, targetKey)
        writeFileAtomically(file, json.toString())
    }

    fun glossaryFileFor(folder: File, targetKey: String = DEFAULT_TARGET_KEY): File {
        val normalizedTarget = targetKey.trim().lowercase()
        val fileName = when (normalizedTarget) {
            "", DEFAULT_TARGET_KEY -> "glossary.json"
            else -> "glossary_${normalizedTarget}.json"
        }
        return File(folder, fileName)
    }

    companion object {
        private const val DEFAULT_TARGET_KEY = "zh_hans"
    }
}
