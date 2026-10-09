package com.manga.translate.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.R
import com.manga.translate.databinding.FragmentLibraryBinding
import com.manga.translate.model.FolderItem
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryChapterSelectionTest {
    @Test
    @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun singleAndMultipleChaptersOfferNormalTranslationInLightTheme() = checkSelection()

    @Test
    @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun longLabelsFitAndScrollInNarrowDarkTheme() = checkSelection()

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight")
    fun portugueseLabelsFitWithLargeFont() = checkSelection(1.3f)

    private fun checkSelection(fontScale: Float = 1f) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val configuration = android.content.res.Configuration(application.resources.configuration)
        configuration.fontScale = fontScale
        val context = ContextThemeWrapper(
            application.createConfigurationContext(configuration), R.style.Theme_MangaTranslator
        )
        val binding = FragmentLibraryBinding.inflate(LayoutInflater.from(context))
        fun adapter() = LibraryFolderAdapter({}, {}, {}, {})
        val chapters = adapter()
        val first = File(context.cacheDir, "chapter-1")
        val second = File(context.cacheDir, "chapter-2")
        chapters.submit(listOf(FolderItem(first, 1), FolderItem(second, 1)))
        val ui = Proxy.newProxyInstance(
            LibraryUiCallbacks::class.java.classLoader,
            arrayOf(LibraryUiCallbacks::class.java)
        ) { _, _, _ -> null } as LibraryUiCallbacks
        var exitedImageSelection = false
        val manager = LibrarySelectionManager(adapter(), chapters, ui, { binding }) {
            exitedImageSelection = true
        }

        // The shared action must replace its previous image retranslation label.
        binding.folderRetranslateSelected.setActionDescription(R.string.retranslate_selected)
        binding.folderRetranslateSelected.visibility = View.GONE
        manager.enterChapterSelectionMode(first)
        binding.folderSelectionCount.text = context.getString(R.string.chapter_selection_count, 1)
        binding.folderSelectAll.setActionDescription(R.string.clear_all)
        assertTrue(exitedImageSelection)
        assertEquals(listOf(first), manager.chapterSelectedFolders())
        assertEquals(View.VISIBLE, binding.folderSelectionActions.visibility)
        assertEquals(View.VISIBLE, binding.folderRetranslateSelected.visibility)
        assertEquals(context.getString(R.string.translate_selected), binding.folderRetranslateSelected.contentDescription)
        manager.enterChapterSelectionMode(second)
        assertEquals(listOf(first, second), manager.chapterSelectedFolders())

        var ancestor = binding.folderSelectionActions.parent
        while (ancestor != null && ancestor !is NestedScrollView) ancestor = ancestor.parent
        assertNull("Selection actions must stay outside the scrolling content", ancestor)
        assertSame(binding.root, binding.folderSelectionActions.parent)
        val actions = binding.folderSelectionActions
        val width = (288 * context.resources.displayMetrics.density).toInt()
        actions.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        actions.layout(0, 0, width, actions.measuredHeight)
        assertEquals(binding.folderRetranslateSelected.top, binding.folderDeleteSelected.top)
        assertSame(binding.folderSelectAll.parent, binding.folderCancelSelection.parent)
        assertSame(binding.folderRetranslateSelected.parent, binding.folderSelectAll.parent)
        assertEquals(binding.folderSelectAll.top, binding.folderCancelSelection.top)
        assertEquals(context.getString(R.string.cancel_selection), binding.folderCancelSelection.contentDescription)
        assertTrue(kotlin.math.abs(binding.folderSelectAll.width - binding.folderCancelSelection.width) <= 1)
        assertTrue("Actions should use the row width evenly",
            kotlin.math.abs(binding.folderRetranslateSelected.width - binding.folderDeleteSelected.width) <= 1)
        val screenshot = Bitmap.createBitmap(width, actions.measuredHeight, Bitmap.Config.ARGB_8888)
        actions.draw(Canvas(screenshot))
        val locale = context.resources.configuration.locales[0].toLanguageTag()
        val output = File("build/reports/selection-panel/$locale.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        val buttons = listOf(binding.folderRetranslateSelected, binding.folderDeleteSelected,
            binding.folderSelectAll, binding.folderCancelSelection)
        for (button in buttons) {
            assertNotNull(button.drawable)
            assertFalse(button.contentDescription.isNullOrBlank())
            assertTrue(button.width >= 48 * context.resources.displayMetrics.density)
            assertEquals((48 * context.resources.displayMetrics.density).toInt(), button.height)
        }
        binding.libraryListContainer.visibility = View.GONE
        binding.folderDetailContainer.visibility = View.VISIBLE
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec((320 * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((480 * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY)
        )
        binding.root.layout(0, 0, binding.root.measuredWidth, binding.root.measuredHeight)
        assertTrue("The list must leave space for the panel",
            binding.folderDetailContainer.bottom <= actions.top)
        val panelTop = actions.top
        binding.folderDetailScroll.scrollTo(0, 500)
        assertEquals("Scrolling must not move the panel", panelTop, actions.top)
        manager.exitChapterSelectionMode()
        assertTrue(manager.chapterSelectedFolders().isEmpty())
        assertEquals(View.GONE, actions.visibility)
        manager.enterChapterSelectionMode(second)
        assertEquals(View.VISIBLE, binding.folderRetranslateSelected.visibility)
    }

    @Test
    fun librarySelectionHidesCreateButtonAndRestoresItOnExit() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MangaTranslator
        )
        val binding = FragmentLibraryBinding.inflate(LayoutInflater.from(context))
        val folders = LibraryFolderAdapter({}, {}, {}, {})
        val target = File(context.cacheDir, "folder")
        folders.submit(listOf(FolderItem(target, 1)))
        val ui = Proxy.newProxyInstance(
            LibraryUiCallbacks::class.java.classLoader,
            arrayOf(LibraryUiCallbacks::class.java)
        ) { _, _, _ -> null } as LibraryUiCallbacks
        val manager = LibrarySelectionManager(
            folders, LibraryFolderAdapter({}, {}, {}, {}), ui, { binding }, {}
        )
        manager.enterLibrarySelectionMode(target)
        assertEquals(View.GONE, binding.addFolderFab.visibility)
        assertEquals(View.VISIBLE, binding.librarySelectionActions.visibility)
        manager.toggleSelectAllLibraryFolders()
        assertEquals(0, manager.librarySelectedCount())
        assertEquals(View.VISIBLE, binding.librarySelectionActions.visibility)
        manager.toggleSelectAllLibraryFolders()
        assertEquals(1, manager.librarySelectedCount())
        manager.exitLibrarySelectionMode()
        assertEquals(View.VISIBLE, binding.addFolderFab.visibility)
        assertEquals(View.GONE, binding.librarySelectionActions.visibility)
        assertEquals(0, manager.librarySelectedCount())
    }

    @Test
    fun imageSelectionResetsChapterActionsAndKeepsRetranslation() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MangaTranslator
        )
        val binding = FragmentLibraryBinding.inflate(LayoutInflater.from(context))
        val images = FolderImageAdapter({}, {}, {})
        val ui = Proxy.newProxyInstance(
            LibraryUiCallbacks::class.java.classLoader,
            arrayOf(LibraryUiCallbacks::class.java)
        ) { _, _, _ -> null } as LibraryUiCallbacks
        val repository = LibraryRepository(context)
        val controller = LibrarySelectionController(
            images, com.manga.translate.storage.TranslationStore(),
            com.manga.translate.storage.OcrStore(), repository,
            LibraryPreferencesGateway(context, context.getSharedPreferences("selection-test", 0), repository),
            ui, LibraryDialogs(), { binding }, { context }, { _, _, _ -> }
        )
        controller.enterSelectionMode(File(context.cacheDir, "image.jpg"))
        assertEquals(context.getString(R.string.retranslate_selected), binding.folderRetranslateSelected.contentDescription)
        assertEquals(context.getString(R.string.folder_selection_count, 1), binding.folderSelectionCount.text)
        assertTrue(binding.folderRetranslateSelected.isEnabled)
        controller.enterSelectionMode(File(context.cacheDir, "image.jpg"))
        assertFalse(binding.folderRetranslateSelected.isEnabled)
        assertFalse(binding.folderDeleteSelected.isEnabled)
        binding.folderSelectAll.isEnabled = false
        controller.enterSelectionMode(File(context.cacheDir, "image.jpg"))
        assertFalse(binding.folderRetranslateSelected.isEnabled)
        controller.exitSelectionMode()
        assertEquals(View.GONE, binding.folderSelectionActions.visibility)
        assertEquals(0, images.selectedCount())
    }
}
