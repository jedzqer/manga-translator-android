package com.manga.translate.library

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manga.translate.model.FolderItem
import com.manga.translate.storage.TranslationStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class LibraryPageState(
    val folder: File? = null,
    val parent: File? = null,
    val filter: FolderFilter? = null,
    val folders: List<FolderItem> = emptyList(),
    val content: FolderContent? = null,
    val contentRevision: Long = 0,
    val statusLeft: String = "",
    val statusRight: String = ""
)

internal class LibraryViewModel(
    private val savedState: SavedStateHandle,
    private val repository: LibraryRepository,
    private val preferences: LibraryPreferencesGateway,
    translationStore: TranslationStore
) : ViewModel() {
    private val loader = LibraryPageLoader(repository, preferences, translationStore)
    private val mutableState = MutableStateFlow(LibraryPageState(
        folder = savedState.get<String>("folder")?.let(::File),
        parent = savedState.get<String>("parent")?.let(::File),
        filter = savedState.get<String>("tag")?.let(FolderFilter::CustomTag)
    ))
    val state = mutableState.asStateFlow()
    val taskState = LibraryUiBridge.taskState
    val pendingErrors = LibraryUiBridge.pendingErrors
    val folderSelection = LibrarySelectionState()
    val chapterSelection = LibrarySelectionState()
    val imageSelection = LibrarySelectionState()
    val detailScrollPositions = mutableMapOf<String, Int>()
    val listScrollStates = mutableMapOf<String, Parcelable>()
    var pendingChapterImportParent: File? = null
    var pendingArchiveImportCollection: File? = null
    private var folderJob: Job? = null
    private var contentJob: Job? = null
    private var folderGeneration = 0L
    private var contentGeneration = 0L

    fun navigate(folder: File?, parent: File? = null) {
        if (state.value.folder == folder && state.value.parent == parent) return
        contentJob?.cancel()
        contentGeneration++
        savedState["folder"] = folder?.absolutePath
        savedState["parent"] = parent?.absolutePath
        imageSelection.setEnabled(false)
        chapterSelection.setEnabled(false)
        mutableState.update { it.copy(folder = folder, parent = parent, content = null, statusLeft = "", statusRight = "") }
    }

    fun setFilter(filter: FolderFilter?) {
        savedState["tag"] = (filter as? FolderFilter.CustomTag)?.tag
        mutableState.update { it.copy(filter = filter) }
    }

    fun setStatus(left: String, right: String = "") {
        mutableState.update { it.copy(statusLeft = left, statusRight = right) }
    }

    fun loadFolders() {
        val generation = ++folderGeneration
        folderJob?.cancel()
        val filter = state.value.filter
        val sortField = preferences.getLibrarySortField()
        val ascending = preferences.isLibrarySortAscending()
        folderJob = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                loader.buildFolderPreviewItems(repository.listFolders(sortField, ascending)).filter { item ->
                    filter !is FolderFilter.CustomTag || filter.tag in item.customTags
                }
            }
            if (generation != folderGeneration) return@launch
            folderSelection.retain(items.map { it.folder.absolutePath }.toSet())
            mutableState.update { it.copy(folders = items) }
            if (items.all { it.statsLoaded }) return@launch
            val complete = withContext(Dispatchers.IO) {
                items.map {
                    currentCoroutineContext().ensureActive()
                    if (it.statsLoaded) it else loader.loadAndCacheFolderStats(it)
                }
            }
            if (generation == folderGeneration) mutableState.update { it.copy(folders = complete) }
        }
    }

    fun loadImages(folder: File) {
        if (state.value.folder != folder) return
        val generation = ++contentGeneration
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { loader.loadContent(folder) }
            if (generation != contentGeneration || state.value.folder != folder) return@launch
            when (content) {
                is FolderContent.Chapters -> chapterSelection.retain(content.items.map { it.folder.absolutePath }.toSet())
                is FolderContent.Images -> imageSelection.retain(content.items.map { it.file.absolutePath }.toSet())
            }
            mutableState.update { it.copy(content = content, contentRevision = generation) }
        }
    }
}
