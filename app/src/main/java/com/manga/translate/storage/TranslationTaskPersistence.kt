package com.manga.translate.storage

import android.content.Context
import androidx.core.content.edit
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.platform.AppLogger
import com.manga.translate.translation.FolderTranslationTask
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class TranslationTaskDescriptor(
    val mode: String,
    val collectionFolderPath: String? = null,
    val tasks: List<FolderTranslationTaskDescriptor>,
    val startedAtEpochMs: Long = System.currentTimeMillis()
)

internal data class FolderTranslationTaskDescriptor(
    val folderPath: String,
    val imagePaths: List<String>,
    val force: Boolean,
    val fullTranslate: Boolean,
    val glossaryProcessingEnabled: Boolean,
    val useVlDirectTranslate: Boolean,
    val language: TranslationLanguage
)

internal class TranslationTaskPersistence(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(task: TranslationTaskDescriptor) {
        prefs.edit {
            putString(KEY_ACTIVE_TASK, task.toJson().toString())
        }
    }

    fun load(): TranslationTaskDescriptor? {
        val raw = prefs.getString(KEY_ACTIVE_TASK, null) ?: return null
        return runCatching {
            parseTranslationTaskDescriptor(JSONObject(raw))
        }.getOrNull()
    }

    fun clear() {
        prefs.edit { remove(KEY_ACTIVE_TASK) }
    }

    private fun TranslationTaskDescriptor.toJson(): JSONObject {
        val taskArray = JSONArray()
        tasks.forEach { task ->
            taskArray.put(task.toJson())
        }
        return JSONObject()
            .put("mode", mode)
            .put("collectionFolderPath", collectionFolderPath)
            .put("startedAtEpochMs", startedAtEpochMs)
            .put("tasks", taskArray)
    }

    private fun FolderTranslationTaskDescriptor.toJson(): JSONObject {
        val images = JSONArray()
        imagePaths.forEach(images::put)
        return JSONObject()
            .put("folderPath", folderPath)
            .put("imagePaths", images)
            .put("force", force)
            .put("fullTranslate", fullTranslate)
            .put("glossaryProcessingEnabled", glossaryProcessingEnabled)
            .put("useVlDirectTranslate", useVlDirectTranslate)
            .put("language", language.prefValue)
    }

    companion object {
        private const val PREFS_NAME = "translation_task_persistence"
        private const val KEY_ACTIVE_TASK = "active_task"

        fun fromFolder(task: FolderTranslationTask): TranslationTaskDescriptor {
            return TranslationTaskDescriptor(
                mode = MODE_SINGLE,
                tasks = listOf(task.toDescriptor())
            )
        }

        fun fromCollection(collectionFolder: File, tasks: List<FolderTranslationTask>): TranslationTaskDescriptor {
            return TranslationTaskDescriptor(
                mode = MODE_COLLECTION,
                collectionFolderPath = collectionFolder.absolutePath,
                tasks = tasks.map { it.toDescriptor() }
            )
        }

        fun fromBatch(tasks: List<FolderTranslationTask>): TranslationTaskDescriptor {
            return TranslationTaskDescriptor(
                mode = MODE_BATCH,
                tasks = tasks.map { it.toDescriptor() }
            )
        }

        private fun FolderTranslationTask.toDescriptor(): FolderTranslationTaskDescriptor {
            return FolderTranslationTaskDescriptor(
                folderPath = folder.absolutePath,
                imagePaths = images.map(File::getAbsolutePath),
                force = force,
                fullTranslate = fullTranslate,
                glossaryProcessingEnabled = glossaryProcessingEnabled,
                useVlDirectTranslate = useVlDirectTranslate,
                language = language
            )
        }

        private const val MODE_SINGLE = "single"
        internal const val MODE_COLLECTION = "collection"
        internal const val MODE_BATCH = "batch"
    }
}

internal fun parseTranslationTaskDescriptor(json: JSONObject): TranslationTaskDescriptor {
    val tasksJson = json.optJSONArray("tasks") ?: JSONArray()
    val tasks = ArrayList<FolderTranslationTaskDescriptor>(tasksJson.length())
    for (index in 0 until tasksJson.length()) {
        val item = tasksJson.optJSONObject(index) ?: continue
        tasks.add(parseFolderTranslationTaskDescriptor(item))
    }
    return TranslationTaskDescriptor(
        mode = json.optString("mode").ifBlank { TranslationTaskPersistence.MODE_BATCH },
        collectionFolderPath = json.optString("collectionFolderPath").takeIf { it.isNotBlank() },
        tasks = tasks,
        startedAtEpochMs = json.optLong("startedAtEpochMs", System.currentTimeMillis())
    )
}

private fun parseFolderTranslationTaskDescriptor(json: JSONObject): FolderTranslationTaskDescriptor {
    val imagePathsJson = json.optJSONArray("imagePaths") ?: JSONArray()
    val imagePaths = ArrayList<String>(imagePathsJson.length())
    for (index in 0 until imagePathsJson.length()) {
        val path = imagePathsJson.optString(index).trim()
        if (path.isNotBlank()) {
            imagePaths.add(path)
        }
    }
    return FolderTranslationTaskDescriptor(
        folderPath = json.optString("folderPath"),
        imagePaths = imagePaths,
        force = json.optBoolean("force"),
        fullTranslate = json.optBoolean("fullTranslate"),
        glossaryProcessingEnabled = json.optBoolean("glossaryProcessingEnabled", true),
        useVlDirectTranslate = json.optBoolean("useVlDirectTranslate"),
        language = TranslationLanguage.fromPref(
            json.optString("language", TranslationLanguage.JA_TO_ZH.prefValue)
        )
    )
}

internal fun TranslationTaskDescriptor.toJsonString(): String {
    val taskArray = JSONArray()
    tasks.forEach { task ->
        val images = JSONArray()
        task.imagePaths.forEach(images::put)
        taskArray.put(
            JSONObject()
                .put("folderPath", task.folderPath)
                .put("imagePaths", images)
                .put("force", task.force)
                .put("fullTranslate", task.fullTranslate)
                .put("glossaryProcessingEnabled", task.glossaryProcessingEnabled)
                .put("useVlDirectTranslate", task.useVlDirectTranslate)
                .put("language", task.language.prefValue)
        )
    }
    return JSONObject()
        .put("mode", mode)
        .put("collectionFolderPath", collectionFolderPath)
        .put("startedAtEpochMs", startedAtEpochMs)
        .put("tasks", taskArray)
        .toString()
}

internal fun TranslationTaskDescriptor.toFolderTasks(): List<FolderTranslationTask> {
    return tasks.mapNotNull { descriptor ->
        val folder = File(descriptor.folderPath)
        val images = descriptor.imagePaths.map(::File).filter(File::exists)
        if (!folder.exists() || images.isEmpty()) {
            AppLogger.log("Library", "Skipping folder task — folder or images missing: ${descriptor.folderPath}")
            null
        } else {
            FolderTranslationTask(
                folder = folder,
                images = images,
                force = descriptor.force,
                fullTranslate = descriptor.fullTranslate,
                glossaryProcessingEnabled = descriptor.glossaryProcessingEnabled,
                useVlDirectTranslate = descriptor.useVlDirectTranslate,
                language = descriptor.language
            )
        }
    }
}
