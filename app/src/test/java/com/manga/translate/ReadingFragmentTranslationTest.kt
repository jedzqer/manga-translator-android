package com.manga.translate

import android.app.Activity
import android.graphics.RectF
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.app.MangaTranslateApp
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.di.appContainer
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationResult
import com.manga.translate.reader.ReadingFragment
import com.manga.translate.library.LibraryStateHostActivity
import com.manga.translate.storage.TranslationStore
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MangaTranslateApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ReadingFragmentTranslationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `exit edit waits for blocked save before pending bubbles can be merged`() {
        withEditingFragment { fragment, store, image ->
            val pending = manualResult(image).copy(bubbles = listOf(
                BubbleTranslation.pending(1, RectF(10f, 10f, 80f, 80f), "hello", BubbleSource.MANUAL)
            ))
            setField(fragment, "currentTranslation", pending)
            val lock = Mutex(locked = true)
            @Suppress("UNCHECKED_CAST")
            val locks = TranslationStore::class.java.getDeclaredField("writeLocks").apply {
                isAccessible = true
            }.get(store) as java.util.concurrent.ConcurrentHashMap<String, Mutex>
            locks[store.translationFileFor(image).absolutePath] = lock
            try {
                invoke(fragment, "toggleEditMode")
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(getField(fragment, "isEditMode") as Boolean)
                assertNull(getField(fragment, "emptyBubbleJob"))
                assertNull(store.load(image))
                // A second edit while the first snapshot is blocked must also be saved.
                setField(fragment, "currentTranslation", pending.copy(bubbles = pending.bubbles +
                    BubbleTranslation.pending(2, RectF(1f, 1f, 5f, 5f), "new", BubbleSource.MANUAL)))
                invoke(fragment, "toggleEditMode")
            } finally {
                lock.unlock()
            }
            awaitMain { !(getField(fragment, "isEditMode") as Boolean) }
            assertEquals(listOf(1, 2), store.load(image)!!.bubbles.map { it.id })
            val translated = pending.bubbles.single().withTranslationResult("补译")
            store.mergePending(image, mapOf(1 to translated))
            awaitMain { (getField(fragment, "finishEditJob") as? kotlinx.coroutines.Job)?.isActive != true }
            assertEquals("补译", TranslationStore().load(image)!!.bubbles.first().translatedText)
        }
    }

    @Test
    fun `failed edit save retains edit session and does not start retry`() {
        withEditingFragment { fragment, store, image ->
            val result = manualResult(image).copy(bubbles = listOf(
                BubbleTranslation.pending(1, RectF(10f, 10f, 80f, 80f), "hello", BubbleSource.MANUAL)
            ))
            setField(fragment, "currentTranslation", result)
            val destination = store.translationFileFor(image)
            destination.mkdirs()
            File(destination, "blocker").writeText("force write failure")
            invoke(fragment, "toggleEditMode")
            awaitMain { (getField(fragment, "finishEditJob") as? kotlinx.coroutines.Job)?.isActive != true }
            assertTrue(getField(fragment, "isEditMode") as Boolean)
            assertEquals(result.bubbles, (getField(fragment, "currentTranslation") as TranslationResult).bubbles)
            assertNull(getField(fragment, "emptyBubbleJob"))
        }
    }

    private fun withEditingFragment(block: (ReadingFragment, TranslationStore, File) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<MangaTranslateApp>()
        val controller = Robolectric.buildActivity(LibraryStateHostActivity::class.java).setup()
        val fragment = ReadingFragment()
        try {
            controller.get().supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val image = temporaryFolder.newFile("edit.jpg")
            setField(fragment, "currentImageFile", image)
            setField(fragment, "currentTranslation", manualResult(image))
            invoke(fragment, "toggleEditMode")
            assertTrue(getField(fragment, "isEditMode") as Boolean)
            block(fragment, app.appContainer.translationStore, image)
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun invoke(fragment: ReadingFragment, method: String) {
        ReadingFragment::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(fragment)
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Timed out waiting for reader save", condition())
    }

    @Test
    fun `parallel reader loads retain saved manual translation without fragment view or folder`() {
        val app = ApplicationProvider.getApplicationContext<MangaTranslateApp>()
        val fragment = ReadingFragment().apply { onAttach(app) }
        val image = temporaryFolder.newFile("page.jpg")
        val result = manualResult(image)
        app.appContainer.translationStore.save(image, result)
        val jsonFile = app.appContainer.translationStore.translationFileFor(image)
        val savedJson = jsonFile.readText()
        fragment.onDetach()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(List(32) { Callable { load(fragment, image) } })
            results.forEach { assertEquals(result.bubbles, it.get(10, TimeUnit.SECONDS)?.bubbles) }
            assertEquals(savedJson, jsonFile.readText())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `queued pending refresh cannot hide newly completed manual translation`() {
        val app = ApplicationProvider.getApplicationContext<MangaTranslateApp>()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_MangaTranslator)
        val binding = FragmentReadingBinding.inflate(LayoutInflater.from(activity))
        activity.setContentView(binding.root)
        shadowOf(Looper.getMainLooper()).idle()
        val fragment = ReadingFragment().apply { onAttach(app) }
        val image = temporaryFolder.newFile("manual.jpg")
        val completed = manualResult(image)
        val pending = completed.copy(bubbles = completed.bubbles.map {
            BubbleTranslation.pending(it.id, it.rect, it.originalText, BubbleSource.MANUAL)
        })
        app.appContainer.translationStore.save(image, pending)
        setField(fragment, "_binding", binding)
        setField(fragment, "currentImageFile", image)
        setField(fragment, "currentTranslation", pending)
        try {
            runBlocking { reload(fragment, image) }
            // The IO read finished, but its View.post has not executed yet.
            app.appContainer.translationStore.save(image, completed)
            setField(fragment, "currentTranslation", completed)
            binding.translationOverlay.setTranslations(completed)
            binding.translationOverlay.visibility = View.VISIBLE
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(completed, getField(fragment, "currentTranslation"))
            assertEquals(View.VISIBLE, binding.translationOverlay.visibility)
            assertEquals(completed.bubbles, load(fragment, image)?.bubbles)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun manualResult(image: File) = TranslationResult(
        imageName = image.name,
        width = 100,
        height = 100,
        bubbles = listOf(BubbleTranslation.translated(
            id = 1,
            rect = RectF(10f, 10f, 80f, 80f),
            originalText = "hello",
            translatedText = "你好",
            source = BubbleSource.MANUAL
        ))
    )

    private fun load(fragment: ReadingFragment, image: File): TranslationResult? =
        ReadingFragment::class.java.getDeclaredMethod(
            "loadValidTranslationForCurrentFolder", File::class.java
        ).apply { isAccessible = true }.invoke(fragment, image) as TranslationResult?

    private suspend fun reload(fragment: ReadingFragment, image: File): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            ReadingFragment::class.java.getDeclaredMethod(
                "reloadCurrentImageTranslation", File::class.java, Continuation::class.java
            ).apply { isAccessible = true }.invoke(fragment, image, continuation)
        }

    private fun setField(fragment: ReadingFragment, name: String, value: Any) {
        ReadingFragment::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(fragment, value)
        }
    }

    private fun getField(fragment: ReadingFragment, name: String): Any? =
        ReadingFragment::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(fragment)
}
