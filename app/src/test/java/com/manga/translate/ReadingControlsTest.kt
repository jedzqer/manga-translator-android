package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.app.MangaTranslateApp
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.di.appContainer
import com.manga.translate.library.LibraryStateHostActivity
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.ReadingDisplayMode
import com.manga.translate.reader.ReadingFragment
import com.manga.translate.reader.ReadingImageTransformController
import com.manga.translate.reader.ReadingSessionViewModel
import com.manga.translate.storage.ReadingProgressStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MangaTranslateApp::class, qualifiers = "en-w320dp-h480dp-notnight")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
            layoutReader(binding)
            test(fragment, binding, vm)
        } finally { controller.pause().stop().destroy() }
    }

    private fun layoutReader(binding: FragmentReadingBinding, width: Int = 320) {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            binding.readingBookTitle.forceLayout()
            binding.readingPageNumber.forceLayout()
            binding.readingPageInfo.forceLayout()
            binding.root.forceLayout()
            binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            binding.root.layout(0, 0, width, 480)
        }
    }

    private fun savePreview(binding: FragmentReadingBinding, name: String) {
        val bitmap = Bitmap.createBitmap(binding.root.width, binding.root.height, Bitmap.Config.ARGB_8888)
        binding.root.draw(Canvas(bitmap))
        val output = File("build/reports/reading-controls/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun longTitleLeavesEditControlsClear() = withReader { _, binding, _ ->
        assertEquals(1, binding.readingBookTitle.maxLines)
        assertTrue(binding.readingPageInfo.width <= 192)
        assertTrue(binding.readingPageInfo.right < binding.readingEditControls.left)
    }

    private fun assertPageNumberVisible(binding: FragmentReadingBinding, expected: String) {
        val number = binding.readingPageNumber
        assertEquals(expected, number.text.toString())
        assertNotNull("page info visibility=${binding.readingPageInfo.visibility}, number=${number.width}x${number.height}, root=${binding.root.width}", number.layout)
        assertEquals(1, number.layout.lineCount)
        assertEquals(0, number.layout.getEllipsisCount(0))
        assertTrue(number.layout.getLineWidth(0) <= number.width - number.totalPaddingLeft - number.totalPaddingRight)
        assertTrue(number.right <= binding.readingPageInfo.width - binding.readingPageInfo.paddingRight)
        assertTrue(binding.readingBookTitle.right <= number.left)
        assertTrue("info=${binding.readingPageInfo.left}..${binding.readingPageInfo.right}, edit=${binding.readingEditControls.left}..${binding.readingEditControls.right}, titleMax=${binding.readingBookTitle.maxWidth}, root=${binding.root.width}",
            binding.readingPageInfo.right < binding.readingEditControls.left)
    }

    @Test fun longTitleKeepsFullPageNumber() = withReader(count = 1234) { _, binding, vm ->
        assertPageNumberVisible(binding, "(1/1234)")
        assertTrue(binding.readingBookTitle.layout.getEllipsisCount(0) > 0)
        savePreview(binding, "long-title-light")
        vm.moveTo(1233)
        layoutReader(binding, width = 240)
        assertPageNumberVisible(binding, "(1234/1234)")
        savePreview(binding, "narrow-four-digit-page")
    }

    @Test fun webtoonLongTitleKeepsFullPageNumber() = withReader(FolderReadingMode.WEBTOON_SCROLL) { _, binding, _ ->
        assertPageNumberVisible(binding, "(1/3)")
        savePreview(binding, "webtoon-long-title")
    }

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun nightThemeDialogRemainsUsable() = withReader { _, binding, _ ->
        assertPageNumberVisible(binding, "(1/3)")
        savePreview(binding, "long-title-dark")
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

    @Test fun webtoonJumpClearsScrollAnchor() = withReader(FolderReadingMode.WEBTOON_SCROLL, count = 6) { _, binding, vm ->
        vm.recordViewport(1, -120)
        binding.translationOverlay.onTap!!.invoke(300f)
        assertEquals(2, vm.state.value.index)
        layoutReader(binding)
        vm.recordViewport(1, -120)
        shadowOf(Looper.getMainLooper()).idle()
        binding.readingPageInfo.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val slider = dialog.findViewById<SeekBar>(R.id.reading_page_jump_slider)!!
        assertEquals(1, slider.progress)
        slider.progress = 2
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        layoutReader(binding)
        assertEquals(2, vm.state.value.index)
        assertEquals(2, vm.state.value.viewport?.imageIndex)
    }

    @Test fun webtoonReopensAtThePersistedPageAfterScrolling() =
        withReader(FolderReadingMode.WEBTOON_SCROLL, count = 6) { fragment, binding, vm ->
            val session = vm.state.value
            val folder = checkNotNull(session.currentFolder)
            val context = fragment.requireContext()
            val store = context.appContainer.readingProgressStore
            val list = binding.readingWebtoonList
            vm.moveTo(2)
            layoutReader(binding)
            assertEquals(2, store.load(folder))
            list.scrollBy(0, 480)
            assertEquals(3, store.load(folder))

            val reopenedIndex = ReadingProgressStore(context).load(folder)
            assertEquals(3, reopenedIndex)
            vm.setFolder(folder, session.images, 0, session.readingMode)
            layoutReader(binding)
            vm.setFolder(folder, session.images, reopenedIndex, session.readingMode)
            layoutReader(binding)
            assertEquals(3, store.load(folder))
            assertEquals("(4/6)", binding.readingPageNumber.text.toString())
            assertEquals(3, vm.state.value.viewport?.imageIndex)
        }

    @Test fun singlePageDisablesSlider() = withReader(count = 1) { _, binding, vm ->
        binding.readingPageInfo.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(dialog.findViewById<SeekBar>(R.id.reading_page_jump_slider)!!.isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(0, vm.state.value.index)
    }
}
