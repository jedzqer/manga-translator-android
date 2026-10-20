package com.manga.translate.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LibrarySelectionSnapshot(val enabled: Boolean = false, val paths: Set<String> = emptySet())

/** Page-owned selection; adapters render it and forward user actions. No view references. */
class LibrarySelectionState {
    private val mutableState = MutableStateFlow(LibrarySelectionSnapshot())
    val state = mutableState.asStateFlow()
    fun setEnabled(enabled: Boolean) {
        mutableState.update { if (enabled) it.copy(enabled = true) else LibrarySelectionSnapshot() }
    }
    fun toggle(path: String) {
        mutableState.update { it.copy(paths = if (path in it.paths) it.paths - path else it.paths + path) }
    }
    fun select(paths: Set<String>) { mutableState.update { it.copy(paths = paths.toSet()) } }
    fun clear() = select(emptySet())
    fun retain(paths: Set<String>) { mutableState.update { it.copy(paths = it.paths.intersect(paths)) } }
}
