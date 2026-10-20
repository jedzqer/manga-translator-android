package com.manga.translate.library

import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.manga.translate.R
import com.manga.translate.app.MangaTranslateApp
import com.manga.translate.databinding.FragmentLibraryBinding
import com.manga.translate.di.appContainer
import com.manga.translate.platform.GlobalTaskProgressStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import androidx.test.core.app.ApplicationProvider

class LibraryStateHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MangaTranslator)
        super.onCreate(savedInstanceState)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MangaTranslateApp::class, qualifiers = "en-w320dp-h480dp-notnight")
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryPageLifecycleTest {
    @After fun reset() {
        GlobalTaskProgressStore.reset()
        LibraryUiBridge.reset()
    }

    @Test fun `view recreation retains selection and restores latest task snapshot`() {
        val app = ApplicationProvider.getApplicationContext<MangaTranslateApp>()
        app.appContainer.settingsStore.markTutorialPromptShown()
        val folder = requireNotNull(app.appContainer.libraryRepository.createFolder("A very long library folder title"))
        val controller = Robolectric.buildActivity(LibraryStateHostActivity::class.java).setup()
        try {
            val manager = controller.get().supportFragmentManager
            val fragment = LibraryFragment()
            manager.beginTransaction().add(android.R.id.content, fragment, "library").commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val vm = ViewModelProvider(fragment)[LibraryViewModel::class.java]
            await { vm.state.value.folders.any { it.folder == folder } }
            vm.folderSelection.setEnabled(true)
            vm.folderSelection.toggle(folder.absolutePath)
            shadowOf(Looper.getMainLooper()).idle()
            manager.beginTransaction().detach(fragment).commitNow()
            val task = GlobalTaskProgressStore.begin("task", "Translation", "OCR")
            task.complete("Translation", "Finished while detached")
            GlobalTaskProgressStore.release(task.taskId)
            manager.beginTransaction().attach(fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val binding = FragmentLibraryBinding.bind(fragment.requireView())
            assertEquals(setOf(folder.absolutePath), vm.folderSelection.state.value.paths)
            assertEquals(android.view.View.VISIBLE, binding.librarySelectionActions.visibility)
            assertEquals("Finished while detached", binding.folderProgressLeft.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun `configuration change retains page owner navigation filter and selection`() {
        val app = ApplicationProvider.getApplicationContext<MangaTranslateApp>()
        app.appContainer.settingsStore.markTutorialPromptShown()
        val folder = requireNotNull(app.appContainer.libraryRepository.createFolder("Long title"))
        val controller = Robolectric.buildActivity(LibraryStateHostActivity::class.java).setup()
        try {
            val fragment = LibraryFragment()
            controller.get().supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment, "library").commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val first = ViewModelProvider(fragment)[LibraryViewModel::class.java]
            first.setFilter(FolderFilter.CustomTag("tag"))
            first.navigate(folder)
            first.imageSelection.setEnabled(true)
            val configuration = Configuration(controller.get().resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            controller.configurationChange(configuration)
            shadowOf(Looper.getMainLooper()).idle()
            val restored = controller.get().supportFragmentManager.findFragmentByTag("library")!!
            val second = ViewModelProvider(restored)[LibraryViewModel::class.java]
            assertSame(first, second)
            assertEquals(folder, second.state.value.folder)
            assertEquals(FolderFilter.CustomTag("tag"), second.state.value.filter)
            assertTrue(second.imageSelection.state.value.enabled)
        } finally { controller.pause().stop().destroy() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(condition())
    }
}
