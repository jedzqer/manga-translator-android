package com.manga.translate

import org.json.JSONArray
import java.io.File

class ExtractStateStore {
    fun load(folder: File): MutableSet<String> {
        val file = stateFileFor(folder)
        if (!file.exists()) return mutableSetOf()
        return try {
            val json = JSONArray(file.readText())
            val result = mutableSetOf<String>()
            for (i in 0 until json.length()) {
                val name = json.optString(i).trim()
                if (name.isNotBlank()) {
                    result.add(name)
                }
            }
            result
        } catch (e: Exception) {
            AppLogger.log("ExtractStateStore", "Failed to load extract state for ${folder.name}", e)
            mutableSetOf()
        }
    }

    fun save(folder: File, extracted: Set<String>) {
        val json = JSONArray()
        for (name in extracted) {
            json.put(name)
        }
        stateFileFor(folder).writeText(json.toString())
    }

    private fun stateFileFor(folder: File): File {
        return File(folder, ".extract-state.json")
    }
}
