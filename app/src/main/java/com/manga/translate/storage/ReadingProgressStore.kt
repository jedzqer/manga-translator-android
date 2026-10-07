package com.manga.translate.storage

import android.content.Context
import androidx.core.content.edit
import java.io.File

class ReadingProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(folder: File): Int {
        return prefs.getInt(keyFor(folder), 0)
    }

    fun save(folder: File, index: Int) {
        prefs.edit() {
                putInt(keyFor(folder), index)
            }
    }

    fun remove(folder: File) {
        prefs.edit() {
            remove(keyFor(folder))
        }
    }

    fun removeTree(folder: File) {
        val rootPath = keyFor(folder)
        prefs.edit {
            prefs.all.keys
                .filter { it == rootPath || it.startsWith("$rootPath${File.separator}") }
                .forEach(::remove)
        }
    }

    fun migrateTree(from: File, to: File) {
        val fromPath = keyFor(from)
        val toPath = keyFor(to)
        if (fromPath == toPath) return
        prefs.edit {
            prefs.all
                .filterKeys { it == fromPath || it.startsWith("$fromPath${File.separator}") }
                .forEach { (key, value) ->
                    if (value is Int) {
                        val suffix = key.removePrefix(fromPath)
                        putInt(toPath + suffix, value)
                    }
                    remove(key)
                }
        }
    }

    private fun keyFor(folder: File): String {
        return folder.absolutePath
    }

    companion object {
        internal const val PREFS_NAME = "reading_progress"
    }
}
