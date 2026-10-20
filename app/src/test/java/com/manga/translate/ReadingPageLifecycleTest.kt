package com.manga.translate

import android.content.res.Configuration
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.app.MangaTranslateApp
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.library.LibraryStateHostActivity
import com.manga.translate.reader.ReadingFragment
import com.manga.translate.reader.ReadingSessionViewModel
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MangaTranslateApp::class, qualifiers = "en-w320dp-h480dp-notnight")
@LooperMode(LooperMode.Mode.PAUSED)
class ReadingPageLifecycleTest {
    @Test fun `view and activity recreation render the retained reading session`() {
        val app = ApplicationProvider.getApplicationContext<MangaTranslateApp>()
        val folder = File(app.cacheDir, "reading-session").apply { mkdirs() }
        val images = List(3) { File(folder, "$it.png") }
        val controller = Robolectric.buildActivity(LibraryStateHostActivity::class.java).setup()
        try {
            var activity = controller.get()
            val vm = ViewModelProvider(activity)[ReadingSessionViewModel::class.java]
            vm.setFolder(folder, images, 1)
            val fragment = ReadingFragment()
            activity.supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment, "reader").commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            assertPage(fragment, 2)
            activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
            vm.next()
            activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            assertPage(fragment, 3)
            controller.configurationChange(Configuration(activity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            })
            activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(vm, ViewModelProvider(activity)[ReadingSessionViewModel::class.java])
            val restored = activity.supportFragmentManager.findFragmentByTag("reader") as ReadingFragment
            assertPage(restored, 3)
        } finally { controller.pause().stop().destroy() }
    }

    private fun assertPage(fragment: ReadingFragment, page: Int) {
        val binding = FragmentReadingBinding.bind(fragment.requireView())
        assertEquals(fragment.getString(R.string.reading_page_info, "reading-session", page, 3),
            binding.readingPageInfo.text.toString())
    }
}
