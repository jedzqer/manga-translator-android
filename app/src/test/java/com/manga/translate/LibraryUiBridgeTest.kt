package com.manga.translate

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import com.manga.translate.background.ServiceLibraryUiCallbacks
import com.manga.translate.library.LibraryUiBridge
import com.manga.translate.platform.GlobalTaskProgressState
import com.manga.translate.platform.GlobalTaskProgressStore
import com.manga.translate.platform.TaskProgressWriter
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryUiBridgeTest {
    private val updates = mutableListOf<GlobalTaskProgressState>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var writer: TaskProgressWriter
    private lateinit var service: ServiceLibraryUiCallbacks
    private class ViewOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun attach(): ViewOwner {
        val owner = ViewOwner()
        owner.registry.currentState = Lifecycle.State.CREATED
        scope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LibraryUiBridge.taskState.collect {
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    updates += it
                }
            }
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        shadowOf(Looper.getMainLooper()).idle()
        return owner
    }

    @Before fun setUp() {
        GlobalTaskProgressStore.reset()
        LibraryUiBridge.reset()
        writer = GlobalTaskProgressStore.begin("task", "Translation", "Preparing")
        service = ServiceLibraryUiCallbacks(writer.taskId)
    }

    @After fun tearDown() {
        scope.cancel()
        LibraryUiBridge.reset()
        GlobalTaskProgressStore.reset()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `ten thousand background OCR updates deliver only latest status on main`() {
        attach(); updates.clear()
        background { repeat(10_000) { service.setFolderStatus("OCR", "$it.jpg") } }
        assertTrue(updates.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("OCR  9999.jpg"), updates.map { it.detail })
    }

    @Test fun `completion and clear supersede queued progress`() {
        attach(); updates.clear()
        background { service.setFolderStatus("OCR", "old.jpg") }
        writer.complete("Translation", "Done")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Done"), updates.map { it.detail })
        GlobalTaskProgressStore.begin("next", "Translation", "Preparing")
        service = ServiceLibraryUiCallbacks("next")
        updates.clear()
        background { service.setFolderStatus("OCR", "old.jpg") }
        service.clearFolderStatus()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(""), updates.map { it.detail })
    }

    @Test fun `destroyed view receives nothing and recreated view replays latest without subscribers`() {
        val oldView = attach(); updates.clear()
        background { service.setFolderStatus("OCR", "page.jpg") }
        oldView.registry.currentState = Lifecycle.State.DESTROYED
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(updates.isEmpty())
        writer.complete("Translation", "Done")
        attach()
        assertEquals(listOf("Done"), updates.map { it.detail })
    }

    @Test fun `old callbacks queued before replacement cannot change new task or show errors`() {
        var delivered = false
        background { LibraryUiBridge.dispatch(writer.taskId) { delivered = true } }
        GlobalTaskProgressStore.begin("new", "New", "Preparing")
        service.setFolderStatus("Old progress")
        service.showApiError("OLD", "Late failure")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(delivered)
        assertEquals("Preparing", LibraryUiBridge.taskState.value.detail)
        assertTrue(LibraryUiBridge.pendingErrors.value.isEmpty())
    }

    @Test fun `model decisions survive view recreation and are acknowledged once`() {
        var retries = 0
        var skips = 0
        service.showModelError("Response", false, { retries++ }, { skips++ })
        val error = LibraryUiBridge.pendingErrors.value.single()
        val first = attach()
        first.registry.currentState = Lifecycle.State.DESTROYED
        attach()
        assertEquals(error, LibraryUiBridge.pendingErrors.value.single())
        assertEquals(0, skips)
        LibraryUiBridge.acknowledge(error.id, retry = true)
        LibraryUiBridge.acknowledge(error.id, retry = true)
        assertEquals(1, retries)
        assertTrue(LibraryUiBridge.pendingErrors.value.isEmpty())
    }

    @Test fun `expired or old decisions cannot resume a task and API errors await acknowledgement`() {
        var retries = 0
        service.showModelError("Response", false, { retries++ }, null)
        val id = LibraryUiBridge.pendingErrors.value.single().id
        service.clearModelErrors()
        LibraryUiBridge.acknowledge(id, retry = true)
        assertEquals(0, retries)
        service.showApiError("network", "Details")
        writer.fail("Translation", "Failed")
        val error = LibraryUiBridge.pendingErrors.value.single()
        attach()
        assertEquals(error, LibraryUiBridge.pendingErrors.value.single())
        LibraryUiBridge.acknowledge(error.id)
        assertTrue(LibraryUiBridge.pendingErrors.value.isEmpty())
    }

    private fun background(action: () -> Unit) {
        val task = java.util.concurrent.FutureTask { action() }
        Thread(task).start()
        task.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }
}
