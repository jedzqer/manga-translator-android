package com.manga.translate

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.File

class ReadingSessionViewModel : ViewModel() {
    private val _currentFolder = MutableLiveData<File?>(null)
    val currentFolder: LiveData<File?> = _currentFolder

    private val _images = MutableLiveData<List<File>>(emptyList())
    val images: LiveData<List<File>> = _images

    private val _index = MutableLiveData(0)
    val index: LiveData<Int> = _index

    private val _isEmbedded = MutableLiveData(false)
    val isEmbedded: LiveData<Boolean> = _isEmbedded

    fun setFolder(folder: File, images: List<File>, startIndex: Int, embeddedMode: Boolean = false) {
        _currentFolder.value = folder
        _images.value = images
        val clamped = startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        _index.value = clamped
        _isEmbedded.value = embeddedMode
    }

    fun next() {
        val list = _images.value.orEmpty()
        if (list.isEmpty()) return
        val current = _index.value ?: 0
        val next = (current + 1).coerceAtMost(list.lastIndex)
        _index.value = next
    }

    fun prev() {
        val current = _index.value ?: 0
        val prev = (current - 1).coerceAtLeast(0)
        _index.value = prev
    }
}
