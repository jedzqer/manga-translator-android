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

    fun setFolder(folder: File, images: List<File>) {
        _currentFolder.value = folder
        _images.value = images
        _index.value = 0
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
