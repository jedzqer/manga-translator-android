package com.manga.translate

import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.app.MangaTranslateApp
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.library.LibraryStateHostActivity
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.reader.ReadingFragment
import com.manga.translate.reader.ReadingImageTransformController
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MangaTranslateApp::class, qualifiers = "en-w320dp-h480dp-notnight")
@LooperMode(LooperMode.Mode.PAUSED)
class ReadingControlsTest {
    private fun withReader(mode: FolderReadingMode = FolderReadingMode.STANDARD, count: Int = 3,
                           test: (ReadingFragment, FragmentReadingBinding, ReadingSessionViewModel) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<MangaTranslateApp>()
        val folder = File(app.cacheDir, "A very long manga title ".repeat(7)).apply { mkdirs() }
        val controller = Robolectric.buildActivity(LibraryStateHostActivity::class.java).setup()
        try {
            val activity = controller.get()
            val vm = ViewModelProvider(activity)[ReadingSessionViewModel::class.java]
            vm.setFolder(folder, List(count) { File(folder, "$it.png") }, 0, mode)
            val fragment = ReadingFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val binding = FragmentReadingBinding.bind(fragment.requireView())
            repeat(2) {
                binding.root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
                binding.root.layout(0, 0, 320, 480)
            }
            test(fragment, binding, vm)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun longTitleLeavesEditControlsClear() = withReader { _, binding, _ ->
        assertEquals(1, binding.readingPageInfo.maxLines)
        assertTrue(binding.readingPageInfo.width <= 192)
        assertTrue(binding.readingPageInfo.right < binding.readingEditControls.left)
    }

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun nightThemeDialogRemainsUsable() = withReader { _, binding, _ ->
        assertTrue(binding.readingPageInfo.right < binding.readingEditControls.left)
        binding.readingPageInfo.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertNotNull(dialog.findViewById<SeekBar>(R.id.reading_page_jump_slider))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    @Test fun hiddenControlsSurvivePageChanges() = withReader { _, binding, vm ->
        binding.translationOverlay.onTap!!.invoke(160f)
        assertEquals(View.GONE, binding.readingPageInfo.visibility)
        assertEquals(View.GONE, binding.readingEditControls.visibility)
        vm.next()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, binding.readingPageInfo.visibility)
        assertEquals(View.GONE, binding.readingEditControls.visibility)
        binding.translationOverlay.onTap!!.invoke(160f)
        assertEquals(View.VISIBLE, binding.readingPageInfo.visibility)
        assertEquals(View.VISIBLE, binding.readingEditControls.visibility)
    }

    @Test fun zoomedPagesTurnByTappingEitherSide() = withReader { fragment, binding, vm ->
        val field = ReadingFragment::class.java.getDeclaredField("imageTransformController").apply { isAccessible = true }
        val transform = field.get(fragment) as ReadingImageTransformController
        transform.resetContent(320, 480, ReadingDisplayMode.FIT_WIDTH)
        transform.toggleDoubleTapZoom(160f, 240f)
        assertTrue(transform.isZoomed())
        binding.translationOverlay.onTap!!.invoke(300f)
        assertEquals(1, vm.state.value.index)
        binding.translationOverlay.onTap!!.invoke(20f)
        assertEquals(0, vm.state.value.index)
    }

    @Test fun sliderNavigatesOnlyAfterConfirmation() = withReader { _, binding, vm ->
        binding.readingPageInfo.performClick()
        var dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<SeekBar>(R.id.reading_page_jump_slider)!!.progress = 2
        assertEquals(0, vm.state.value.index)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(0, vm.state.value.index)
        binding.readingPageInfo.performClick()
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<SeekBar>(R.id.reading_page_jump_slider)!!.progress = 2
        assertEquals(2, dialog.findViewById<SeekBar>(R.id.reading_page_jump_slider)!!.progress)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, vm.state.value.index)
    }

    @Test fun webtoonJumpClearsScrollAnchor() = withReader(FolderReadingMode.WEBTOON_SCROLL) { _, binding, vm ->
        vm.recordViewport(1, -120)
        binding.translationOverlay.onTap!!.invoke(300f)
        assertEquals(2, vm.state.value.index)
        vm.recordViewport(1, -120)
        binding.readingPageInfo.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val slider = dialog.findViewById<SeekBar>(R.id.reading_page_jump_slider)!!
        assertEquals(1, slider.progress)
        slider.progress = 2
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, vm.state.value.index)
        assertTrue(vm.state.value.viewport == null || vm.state.value.viewport?.imageIndex == 2)
    }

    @Test fun singlePageDisablesSlider() = withReader(count = 1) { _, binding, vm ->
        binding.readingPageInfo.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(dialog.findViewById<SeekBar>(R.id.reading_page_jump_slider)!!.isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(0, vm.state.value.index)
    }
}
