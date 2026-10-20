package com.manga.translate.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class GlobalTaskProgressStage {
    PREPARING_TRANSLATION,
    DETECTING_REGIONS,
    OCR,
    GLOSSARY,
    TRANSLATING
}

internal data class GlobalTaskProgressState(
    val taskId: String? = null,
    val translationTask: Boolean = false,
    val running: Boolean = false,
    val visible: Boolean = false,
    val title: String = "",
    val detail: String = "",
    val folderLeft: String = "",
    val folderRight: String = "",
    val progress: Int? = null,
    val total: Int? = null,
    val failedCount: Int? = null,
    val stage: GlobalTaskProgressStage? = null,
    val terminal: Boolean = false,
    val error: Boolean = false,
    val canceled: Boolean = false
)

internal object GlobalTaskProgressStore {
    private val _state = MutableStateFlow(GlobalTaskProgressState())
    val state: StateFlow<GlobalTaskProgressState> = _state.asStateFlow()

    @Synchronized
    fun begin(taskId: String, title: String, detail: String, translationTask: Boolean = true): TaskProgressWriter {
        _state.value = GlobalTaskProgressState(
            taskId = taskId, translationTask = translationTask, running = translationTask,
            visible = true, title = title, detail = detail, folderLeft = detail
        )
        return TaskProgressWriter(taskId)
    }

    @Synchronized
    internal fun update(taskId: String, transform: (GlobalTaskProgressState) -> GlobalTaskProgressState): Boolean {
        val current = _state.value
        if (current.taskId != taskId || current.terminal) return false
        _state.value = transform(current)
        return true
    }

    /** Dismiss presentation without reopening a completed task to late progress. */
    @Synchronized
    fun hide() {
        _state.value = _state.value.copy(visible = false)
    }

    @Synchronized
    fun reset() {
        _state.value = GlobalTaskProgressState()
    }

    /** Service releases ownership only after job cleanup and persistence have finished. */
    @Synchronized
    fun release(taskId: String) {
        if (_state.value.taskId == taskId) _state.value = _state.value.copy(running = false)
    }
}

/** A producer keeps this identity for its whole lifetime, including queued callbacks. */
internal class TaskProgressWriter(val taskId: String) {
    fun show(
        title: String,
        detail: String,
        progress: Int? = null,
        total: Int? = null,
        failedCount: Int? = null,
        stage: GlobalTaskProgressStage? = null,
        folderLeft: String = detail,
        folderRight: String = ""
    ) {
        GlobalTaskProgressStore.update(taskId) { it.copy(
            visible = true,
            title = title,
            detail = detail,
            progress = progress,
            total = total,
            failedCount = failedCount,
            stage = stage,
            folderLeft = folderLeft,
            folderRight = folderRight
        ) }
    }

    fun complete(title: String, detail: String) {
        GlobalTaskProgressStore.update(taskId) { it.copy(
            visible = true,
            title = title,
            detail = detail,
            terminal = true, stage = null, progress = null, total = null, failedCount = null,
            folderLeft = detail, folderRight = ""
        ) }
    }

    fun fail(title: String, detail: String) {
        GlobalTaskProgressStore.update(taskId) { it.copy(
            visible = true,
            title = title,
            detail = detail,
            terminal = true,
            error = true, stage = null, progress = null, total = null, failedCount = null,
            folderLeft = detail, folderRight = ""
        ) }
    }

    fun cancel(title: String, detail: String) {
        GlobalTaskProgressStore.update(taskId) { it.copy(
            visible = true, title = title, detail = detail,
            terminal = true, canceled = true, stage = null, progress = null, total = null, failedCount = null,
            folderLeft = detail, folderRight = ""
        ) }
    }

    fun status(left: String, right: String = "") {
        GlobalTaskProgressStore.update(taskId) { it.copy(
            detail = listOf(left, right).filter(String::isNotBlank).joinToString("  "),
            folderLeft = left, folderRight = right
        ) }
    }
}
