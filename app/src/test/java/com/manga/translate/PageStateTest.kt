package com.manga.translate

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.manga.translate.reader.ReadingSessionViewModel
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.library.LibrarySelectionState
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PageStateTest {
    @Test fun `reading folder images index and mode change atomically and detach from input list`() {
        val vm = ReadingSessionViewModel()
        val folder = File("/book")
        val images = mutableListOf(File(folder, "1.jpg"), File(folder, "2.jpg"))
        vm.setFolder(folder, images, 100, FolderReadingMode.WEBTOON_SCROLL)
        val state = vm.state.value
        images.clear()
        assertEquals(folder, state.currentFolder)
        assertEquals(2, state.images.size)
        assertEquals(1, state.index)
        assertEquals(FolderReadingMode.WEBTOON_SCROLL, state.readingMode)
        vm.updateReadingMode(File("/other"), FolderReadingMode.STANDARD)
        assertSame(state, vm.state.value)
        vm.prev(); vm.prev()
        assertEquals(0, vm.state.value.index)
    }

    @Test fun `configuration owners reuse the session and saved state contains only light recovery data`() {
        val store = ViewModelStore()
        val saved = SavedStateHandle()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ReadingSessionViewModel(saved) as T
            }
        }
        fun provider() = ViewModelProvider(object : ViewModelStoreOwner {
            override val viewModelStore = store
        }, factory)
        val first = provider()[ReadingSessionViewModel::class.java]
        val folder = File("/book")
        first.setFolder(folder, listOf(File(folder, "1.jpg"), File(folder, "2.jpg")), 1)
        assertSame(first, provider()[ReadingSessionViewModel::class.java])
        assertEquals(setOf("folder", "index"), saved.keys())
        val restarted = ReadingSessionViewModel(SavedStateHandle(mapOf(
            "folder" to saved.get<String>("folder"), "index" to saved.get<Int>("index"))))
        assertEquals(folder, restarted.state.value.currentFolder)
        assertEquals(1, restarted.state.value.index)
        assertTrue(restarted.state.value.images.isEmpty())
        store.clear()
    }

    @Test fun `selection mode and paths reset together and snapshots are immutable`() {
        val selection = LibrarySelectionState()
        selection.setEnabled(true)
        selection.select(setOf("a", "b"))
        val before = selection.state.value
        selection.retain(setOf("b"))
        assertEquals(setOf("a", "b"), before.paths)
        assertEquals(setOf("b"), selection.state.value.paths)
        selection.setEnabled(false)
        assertFalse(selection.state.value.enabled)
        assertTrue(selection.state.value.paths.isEmpty())
    }

    @Test fun `webtoon viewport survives session recovery without changing navigation requests`() {
        val saved = SavedStateHandle()
        val vm = ReadingSessionViewModel(saved)
        val folder = File("/book")
        val images = List(4) { File(folder, "$it.jpg") }
        vm.setFolder(folder, images, 0, FolderReadingMode.WEBTOON_SCROLL)
        vm.recordViewport(2, -17)
        assertEquals(0, vm.state.value.index)
        assertEquals(2, vm.state.value.viewport?.imageIndex)
        assertEquals(-17, vm.state.value.viewport?.topOffset)
        val restarted = ReadingSessionViewModel(saved)
        assertEquals(2, restarted.state.value.index)
        assertEquals(-17, restarted.state.value.viewport?.topOffset)
        vm.moveTo(3)
        assertNull(vm.state.value.viewport)
        assertFalse(saved.contains("scrollOffset"))
    }
}
