package com.manga.translate

import com.manga.translate.platform.GlobalTaskProgressStage
import com.manga.translate.platform.GlobalTaskProgressStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class GlobalTaskProgressStoreTest {
    @After fun tearDown() = GlobalTaskProgressStore.reset()

    @Test fun `show keeps identity and structured progress in one snapshot`() {
        val task = GlobalTaskProgressStore.begin("a", "Translation", "Preparing")
        task.show("Translation", "Processed 3/10", 3, 10, 1, GlobalTaskProgressStage.TRANSLATING)
        val state = GlobalTaskProgressStore.state.value
        assertEquals("a", state.taskId)
        assertTrue(state.running)
        assertEquals(3, state.progress)
        assertEquals(10, state.total)
        assertEquals(1, state.failedCount)
        assertEquals(GlobalTaskProgressStage.TRANSLATING, state.stage)
    }

    @Test fun `every terminal outcome rejects late progress and other terminal outcomes`() {
        for (outcome in listOf("complete", "cancel", "fail")) {
            val task = GlobalTaskProgressStore.begin(outcome, "Translation", "Preparing")
            when (outcome) {
                "complete" -> task.complete("Translation", "Done")
                "cancel" -> task.cancel("Translation", "Canceled")
                else -> task.fail("Translation", "Failed")
            }
            val terminal = GlobalTaskProgressStore.state.value
            task.show("Translation", "Late OCR", 1, 10)
            task.status("Late status")
            task.complete("Translation", "Late completion")
            task.cancel("Translation", "Late cancellation")
            task.fail("Translation", "Late failure")
            assertEquals(terminal, GlobalTaskProgressStore.state.value)
            // Finishing the result does not release the Service's cleanup ownership.
            assertTrue(terminal.running)
            GlobalTaskProgressStore.release(task.taskId)
            assertFalse(GlobalTaskProgressStore.state.value.running)
        }
    }

    @Test fun `old producer cannot update or release the next task`() {
        val old = GlobalTaskProgressStore.begin("old", "Old", "Preparing")
        GlobalTaskProgressStore.begin("new", "New", "Preparing")
        val expected = GlobalTaskProgressStore.state.value
        old.show("Old", "Late")
        old.fail("Old", "Late failure")
        GlobalTaskProgressStore.release(old.taskId)
        assertEquals(expected, GlobalTaskProgressStore.state.value)
    }

    @Test fun `dismiss retains terminal guard and cold reset rejects previous producers`() {
        val task = GlobalTaskProgressStore.begin("a", "Translation", "Preparing")
        task.complete("Translation", "Done")
        GlobalTaskProgressStore.hide()
        task.show("Translation", "Late")
        assertFalse(GlobalTaskProgressStore.state.value.visible)
        assertTrue(GlobalTaskProgressStore.state.value.terminal)
        GlobalTaskProgressStore.reset()
        task.fail("Translation", "Late")
        assertNull(GlobalTaskProgressStore.state.value.taskId)
    }

    @Test fun `concurrent cancellation and completion settle once`() {
        repeat(50) { iteration ->
            val task = GlobalTaskProgressStore.begin("$iteration", "Translation", "Preparing")
            val gate = CountDownLatch(1)
            val completed = Thread { gate.await(); task.complete("Translation", "Done") }
            val canceled = Thread { gate.await(); task.cancel("Translation", "Canceled") }
            completed.start(); canceled.start(); gate.countDown()
            completed.join(); canceled.join()
            val terminal = GlobalTaskProgressStore.state.value
            assertTrue(terminal.terminal)
            assertEquals(terminal.detail == "Canceled", terminal.canceled)
            task.show("Translation", "Late")
            assertEquals(terminal, GlobalTaskProgressStore.state.value)
        }
    }
}
