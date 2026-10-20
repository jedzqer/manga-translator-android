package com.manga.translate.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.model.FolderReadingMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReadingViewport(val imageIndex: Int, val topOffset: Int)

data class ReadingSessionState(
    val currentFolder: File? = null,
    val images: List<File> = emptyList(),
    val index: Int = 0,
    val readingMode: FolderReadingMode = FolderReadingMode.STANDARD,
    val viewport: ReadingViewport? = null
)

class ReadingSessionViewModel(private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val mutableState = MutableStateFlow(ReadingSessionState(
        currentFolder = savedState.get<String>("folder")?.let(::File),
        index = savedState.get<Int>("index") ?: 0,
        viewport = savedState.get<Int>("scrollOffset")?.let {
            ReadingViewport(savedState.get<Int>("index") ?: 0, it)
        }
    ))
    val state = mutableState.asStateFlow()
    private var restoring = false
    private var sessionGeneration = 0L

    fun setFolder(folder: File, images: List<File>, startIndex: Int,
                  readingMode: FolderReadingMode = FolderReadingMode.STANDARD) {
        sessionGeneration++
        publish(ReadingSessionState(folder, images.toList(),
            startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)), readingMode))
    }

    internal fun restoreSession(repository: LibraryRepository, preferences: LibraryPreferencesGateway) {
        val before = state.value
        val folder = before.currentFolder ?: return
        if (before.images.isNotEmpty() || restoring) return
        restoring = true
        val generation = sessionGeneration
        viewModelScope.launch {
            try {
                val images = withContext(Dispatchers.IO) { repository.listImages(folder) }
                if (generation == sessionGeneration) {
                    val latest = state.value
                    publish(latest.copy(images = images.toList(),
                        index = latest.index.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
                        readingMode = preferences.getReadingMode(folder)))
                }
            } finally { restoring = false }
        }
    }

    fun updateReadingMode(folder: File, readingMode: FolderReadingMode) {
        val current = state.value
        if (current.currentFolder?.absolutePath != folder.absolutePath) return
        publish(current.copy(readingMode = readingMode))
    }

    fun next() = moveTo(state.value.index + 1)
    fun prev() = moveTo(state.value.index - 1)

    fun moveTo(index: Int) {
        val current = state.value
        publish(current.copy(index = index.coerceIn(0, (current.images.size - 1).coerceAtLeast(0)), viewport = null))
    }

    fun recordViewport(imageIndex: Int, topOffset: Int) {
        val current = state.value
        if (current.images.isEmpty() || current.readingMode != FolderReadingMode.WEBTOON_SCROLL) return
        publish(current.copy(viewport = ReadingViewport(imageIndex.coerceIn(0, current.images.lastIndex), topOffset)))
    }

    private fun publish(state: ReadingSessionState) {
        savedState["folder"] = state.currentFolder?.absolutePath
        savedState["index"] = state.viewport?.imageIndex ?: state.index
        if (state.viewport != null) savedState["scrollOffset"] = state.viewport.topOffset
        else savedState.remove<Int>("scrollOffset")
        mutableState.value = state
    }
}
