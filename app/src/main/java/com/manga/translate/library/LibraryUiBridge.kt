package com.manga.translate.library

import android.os.Handler
import android.os.Looper
import com.manga.translate.platform.GlobalTaskProgressStore
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LibraryPendingError(
    val id: Long,
    val taskId: String,
    val content: String,
    val apiCode: String? = null,
    val useSystemOverlay: Boolean = false
)

/**
 * Task progress has exactly one source: GlobalTaskProgressStore.
 * This bridge retains unacknowledged decisions in process memory; it never owns a task/job.
 * Only transient refresh/toast/platform operations use attached callbacks.
 */
internal object LibraryUiBridge {
    val taskState = GlobalTaskProgressStore.state
    private val callbacks = CopyOnWriteArraySet<LibraryUiCallbacks>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableErrors = MutableStateFlow<List<LibraryPendingError>>(emptyList())
    val pendingErrors = mutableErrors.asStateFlow()
    private val resolutions = mutableMapOf<Long, Pair<(() -> Unit)?, (() -> Unit)?>>()
    private var nextId = 0L

    fun register(callbacks: LibraryUiCallbacks) { this.callbacks.add(callbacks) }
    fun unregister(callbacks: LibraryUiCallbacks) { this.callbacks.remove(callbacks) }
    fun hasAttachedUi(): Boolean = callbacks.any { it.isUiAttached() }

    fun dispatch(taskId: String, action: () -> Unit) {
        val deliver = Runnable {
            if (taskState.value.taskId == taskId) action()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run()
        else mainHandler.post(deliver)
    }

    @Synchronized
    fun showApiError(taskId: String, code: String, detail: String?) {
        if (taskState.value.taskId != taskId) return
        mutableErrors.value = mutableErrors.value + LibraryPendingError(++nextId, taskId, detail.orEmpty(), code)
    }

    @Synchronized
    fun showModelError(taskId: String, content: String, useSystemOverlay: Boolean,
                       onRetry: (() -> Unit)?, onSkip: (() -> Unit)?) {
        if (taskState.value.taskId != taskId || taskState.value.terminal) return
        val id = ++nextId
        resolutions[id] = onRetry to onSkip
        mutableErrors.value = mutableErrors.value + LibraryPendingError(id, taskId, content,
            useSystemOverlay = useSystemOverlay)
    }

    fun acknowledge(id: Long, retry: Boolean = false) {
        val resolution = synchronized(this) {
            val event = mutableErrors.value.firstOrNull { it.id == id } ?: return
            mutableErrors.value = mutableErrors.value.filterNot { it.id == id }
            val actions = resolutions.remove(id)
            if (event.taskId != taskState.value.taskId || taskState.value.terminal) null
            else if (retry) actions?.first else actions?.second
        }
        resolution?.invoke()
    }

    @Synchronized
    fun clearModelErrors(taskId: String) {
        val removed = mutableErrors.value.filter { it.taskId == taskId && it.apiCode == null }
        removed.forEach { resolutions.remove(it.id) }
        mutableErrors.value = mutableErrors.value - removed.toSet()
    }

    @Synchronized
    fun reset() {
        mutableErrors.value = emptyList()
        resolutions.clear()
    }

    fun setFolderExportEnabled(folder: File, enabled: Boolean) {
        callbacks.forEach { it.setFolderExportEnabled(folder, enabled) }
    }
    fun showToast(resId: Int) { callbacks.forEach { it.showToast(resId) } }
    fun showToastMessage(message: String) { callbacks.forEach { it.showToastMessage(message) } }
    fun refreshFolders() { callbacks.forEach { it.refreshFolders() } }
    fun refreshImages(folder: File) { callbacks.forEach { it.refreshImages(folder) } }
    fun showExportSuccess(path: String) {
        callbacks.firstOrNull { it.isLibraryInForeground() }?.showExportSuccess(path)
    }
    fun isAppInForeground(): Boolean = callbacks.any { it.isAppInForeground() }
    fun canShowSystemOverlay(): Boolean = callbacks.any { it.canShowSystemOverlay() }
}
