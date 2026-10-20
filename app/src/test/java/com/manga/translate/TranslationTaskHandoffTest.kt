package com.manga.translate

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.background.PendingTranslationTasks
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.platform.GlobalTaskProgressStore
import com.manga.translate.storage.FolderTranslationTaskDescriptor
import com.manga.translate.storage.TranslationTaskDescriptor
import com.manga.translate.storage.toJsonString
import com.manga.translate.storage.parseTranslationTaskDescriptor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationTaskHandoffTest {
    @After
    fun tearDown() = GlobalTaskProgressStore.reset()

    @Test
    fun `ten thousand image paths never enter the service intent`() {
        for (fullTranslate in listOf(false, true)) {
            val descriptor = descriptor(fullTranslate)
            val context = CapturingContext()
            TranslationKeepAliveService.startTranslationTask(context, descriptor)
            val intent = requireNotNull(context.startedIntent)
            val parcel = Parcel.obtain()
            try {
                // Demonstrate the old payload exceeds Binder's shared 1 MiB buffer.
                parcel.writeString(descriptor.toJsonString())
                assertTrue(parcel.dataSize() > 1024 * 1024)
            } finally {
                parcel.recycle()
            }
            val intentParcel = Parcel.obtain()
            try {
                intent.writeToParcel(intentParcel, 0)
                assertTrue(intentParcel.dataSize() < 16 * 1024)
            } finally {
                intentParcel.recycle()
            }
            assertSame(descriptor, TranslationKeepAliveService.takePendingTranslationTask(intent))
            assertNull(TranslationKeepAliveService.takePendingTranslationTask(intent))
        }
    }

    @Test
    fun `failed service start releases task and reports failure`() {
        val context = CapturingContext(fail = true)
        TranslationKeepAliveService.startTranslationTask(context, descriptor(false))
        assertNull(TranslationKeepAliveService.takePendingTranslationTask(requireNotNull(context.startedIntent)))
        assertTrue(GlobalTaskProgressStore.state.value.error)
    }

    @Test
    fun `handoff is isolated one shot and cannot survive process restart`() {
        val pending = PendingTranslationTasks()
        val first = descriptor(false)
        val second = descriptor(true)
        val firstId = pending.put(first)
        val secondId = pending.put(second)
        assertNull(PendingTranslationTasks().take(firstId))
        assertNull(pending.take(null))
        assertNull(pending.take("missing"))
        assertSame(second, pending.take(secondId))
        assertSame(first, pending.take(firstId))
        assertNull(pending.take(firstId))
    }

    @Test
    fun `service restart clears persisted description without resuming`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val persistence = com.manga.translate.storage.TranslationTaskPersistence(context)
        persistence.save(descriptor(false))
        GlobalTaskProgressStore.begin("dead-process", "Translation", "Preparing")
        val controller = org.robolectric.Robolectric.buildService(TranslationKeepAliveService::class.java).create()
        try {
            assertEquals(android.app.Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1))
            assertNull(persistence.load())
            assertNull(GlobalTaskProgressStore.state.value.taskId)
        } finally { controller.destroy() }
    }

    @Test
    fun `only-bubbles option survives handoff and missing legacy field defaults off`() {
        val enabledDescriptor = descriptor(full = false, onlyTranslateBubbles = true)
        val serialized = enabledDescriptor.toJsonString()
        assertTrue(parseTranslationTaskDescriptor(JSONObject(serialized)).tasks.single().onlyTranslateBubbles)

        val legacyJson = JSONObject(serialized)
        legacyJson.getJSONArray("tasks").getJSONObject(0).remove("onlyTranslateBubbles")
        assertFalse(parseTranslationTaskDescriptor(legacyJson).tasks.single().onlyTranslateBubbles)
    }

    @Test
    fun `failed competing start leaves active task intact`() {
        GlobalTaskProgressStore.begin("active", "Translation", "OCR")
        val current = GlobalTaskProgressStore.state.value
        TranslationKeepAliveService.startTranslationTask(CapturingContext(fail = true), descriptor(false))
        assertEquals(current, GlobalTaskProgressStore.state.value)
    }

    @Test
    fun `old notification cancellation cannot cancel the new service task`() {
        val writer = GlobalTaskProgressStore.begin("new", "Translation", "OCR")
        val controller = org.robolectric.Robolectric.buildService(TranslationKeepAliveService::class.java).create()
        val service = controller.get()
        TranslationKeepAliveService::class.java.getDeclaredField("taskProgress").apply {
            isAccessible = true
            set(service, writer)
        }
        var cancellations = 0
        val registration = com.manga.translate.platform.TranslationCancellationRegistry.register {
            cancellations++
            true
        }
        fun cancel(id: String) = Intent(service, TranslationKeepAliveService::class.java).apply {
            action = "com.manga.translate.action.CANCEL_TRANSLATION"
            putExtra("extra_task_id", id)
        }
        try {
            service.onStartCommand(cancel("old"), 0, 1)
            assertEquals(0, cancellations)
            service.onStartCommand(cancel("new"), 0, 2)
            assertEquals(1, cancellations)
        } finally {
            registration.unregister()
            controller.destroy()
        }
    }

    @Test
    fun `service destruction seals a canceled job before late cleanup progress`() {
        val writer = GlobalTaskProgressStore.begin("ending", "Translation", "OCR")
        val controller = org.robolectric.Robolectric.buildService(TranslationKeepAliveService::class.java).create()
        fun field(name: String, value: Any) {
            TranslationKeepAliveService::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(controller.get(), value)
            }
        }
        field("taskProgress", writer)
        field("translationJob", kotlinx.coroutines.Job().apply { cancel() })
        controller.destroy()
        val terminal = GlobalTaskProgressStore.state.value
        assertTrue(terminal.canceled)
        assertTrue(terminal.terminal)
        assertFalse(terminal.running)
        writer.show("Translation", "Late OCR")
        assertEquals(terminal, GlobalTaskProgressStore.state.value)
    }

    private class CapturingContext(private val fail: Boolean = false) :
        ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        var startedIntent: Intent? = null
        override fun startForegroundService(service: Intent): ComponentName? {
            startedIntent = service
            if (fail) throw IllegalStateException("Service unavailable")
            return service.component
        }
    }

    private fun descriptor(full: Boolean, onlyTranslateBubbles: Boolean = false) = TranslationTaskDescriptor(
        mode = "single",
        tasks = listOf(FolderTranslationTaskDescriptor(
            folderPath = "/storage/emulated/0/Android/data/com.manga.translate/files/manga_library/series/chapter",
            imagePaths = List(10_000) {
                "/storage/emulated/0/Android/data/com.manga.translate/files/manga_library/series/chapter/page_$it.jpg"
            },
            force = false,
            fullTranslate = full,
            glossaryProcessingEnabled = true,
            useVlDirectTranslate = false,
            language = TranslationLanguage.JA_TO_ZH,
            onlyTranslateBubbles = onlyTranslateBubbles
        ))
    )
}
