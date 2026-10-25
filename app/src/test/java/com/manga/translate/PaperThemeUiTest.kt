package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.manga.translate.databinding.ActivityMainBinding
import com.manga.translate.databinding.FragmentLibraryBinding
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.databinding.FragmentSettingsBinding
import com.manga.translate.settings.CustomThemeColors
import com.manga.translate.theming.CustomThemeUiApplier
import com.manga.translate.theming.ThemePaletteRuntime
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.tabs.TabLayout
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperThemeUiTest {
    @After
    fun clearPalette() = ThemePaletteRuntime.clear()

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-notnight-mdpi")
    fun `Chinese paper UI fits a narrow light screen`() = verifyPaperUi("zh-light")

    @Test @Config(qualifiers = "b+zh+Hant-w320dp-h640dp-night-mdpi")
    fun `traditional Chinese paper UI fits a narrow dark screen`() = verifyPaperUi("hant-dark")

    @Test @Config(qualifiers = "en-w320dp-h640dp-notnight-mdpi")
    fun `English action labels wrap without overlapping`() = verifyPaperUi("en-light")

    @Test @Config(qualifiers = "pt-rBR-w320dp-h640dp-notnight-mdpi")
    fun `Portuguese paper UI remains scrollable`() = verifyPaperUi("pt-light")

    @Test @Config(qualifiers = "ru-w320dp-h640dp-night-mdpi")
    fun `Russian labels fit the dark paper UI`() = verifyPaperUi("ru-dark")

    @Test @Config(qualifiers = "en-w320dp-h640dp-notnight-mdpi")
    fun `custom colors preserve action tiles switches and transparent reader controls`() {
        val palette = ThemePaletteRuntime.activate(CustomThemeColors.DEFAULT)
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MangaTranslator_Custom_Light)
        val inflater = LayoutInflater.from(context)
        val library = FragmentLibraryBinding.inflate(inflater)
        val settings = FragmentSettingsBinding.inflate(inflater)
        val reader = FragmentReadingBinding.inflate(inflater)
        val actionBackground = library.importCbzButton.background
        val switchBackground = settings.modelIoLoggingSwitch.background
        val readerBackground = reader.readingAddButton.background
        CustomThemeUiApplier.apply(library.root)
        CustomThemeUiApplier.apply(settings.root)
        CustomThemeUiApplier.apply(reader.root)
        assertSame(actionBackground, library.importCbzButton.background)
        assertSame(switchBackground, settings.modelIoLoggingSwitch.background)
        assertSame(readerBackground, reader.readingAddButton.background)
        settings.themeButton.isEnabled = false
        assertEquals(palette.mutedForeground, settings.themeButton.currentTextColor)
        settings.themeButton.isEnabled = true
        assertEquals(palette.buttonText, settings.themeButton.currentTextColor)
        assertTrue(ColorUtils.calculateContrast(palette.heroText, palette.heroStart) >= 4.5)
    }

    private fun verifyPaperUi(name: String) {
        ThemePaletteRuntime.clear()
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MangaTranslator)
        val inflater = LayoutInflater.from(context)
        val main = ActivityMainBinding.inflate(inflater)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_MangaTranslator)
        controller.setup().get().setContentView(main.root)
        listOf(R.string.tab_reading, R.string.tab_library, R.string.tab_settings).forEach {
            main.mainTabs.addTab(main.mainTabs.newTab().setText(it))
        }
        main.mainTabs.getTabAt(1)!!.select()
        val library = FragmentLibraryBinding.inflate(inflater)
        val tileBackground = library.importCbzButton.background
        CustomThemeUiApplier.apply(library.root)
        assertSame(tileBackground, library.importCbzButton.background)

        // Render the actual page resources in the shared notebook backing without starting
        // the translation services or the activity's network/update lifecycle.
        val pager = main.mainPager
        val page = FrameLayout(context).apply { id = pager.id }
        val params = pager.layoutParams
        main.root.removeView(pager)
        main.root.addView(page, params)
        page.addView(library.root)
        measure(main.root)
        listOf(library.importEhviewerButton, library.importCbzButton,
            library.floatingTranslateButton, library.tutorialButton).forEach(::assertTextFits)
        savePreview(main.root, "$name-library")
        assertSelectedTabReadable(context, main.mainTabs)
        assertTabIndicatorContained(main.mainTabs)
        main.mainTabs.setScrollPosition(2, 0f, true)
        savePreview(main.root, "$name-settings-tab")
        main.mainTabs.setScrollPosition(1, 0f, true)
        val secondRowTop = topIn(main.root, library.floatingTranslateButton)
        listOf(library.importEhviewerButton, library.importCbzButton).forEach { first ->
            val firstBottom = topIn(main.root, first) + first.height
            assertTrue("Second row $secondRowTop follows ${first.text} bottom $firstBottom",
                secondRowTop >= firstBottom)
        }

        val settings = FragmentSettingsBinding.inflate(inflater)
        // Exercise the Android 17 permission entry with every locale on a narrow screen.
        settings.localNetworkPermissionButton.visibility = View.VISIBLE
        page.removeAllViews()
        page.addView(settings.root)
        measure(main.root)
        assertTrue(settings.root.getChildAt(0).height > settings.root.height)
        assertTextFits(settings.themeButton)
        assertTextFits(settings.localNetworkPermissionButton)
        val checked = settings.modelIoLoggingSwitch.isChecked
        settings.modelIoLoggingSwitch.performClick()
        assertEquals(!checked, settings.modelIoLoggingSwitch.isChecked)
        savePreview(main.root, "$name-settings")
        settings.root.fullScroll(View.FOCUS_DOWN)
        assertTrue(settings.root.scrollY > 0)

        val translationSettings = library.folderTranslationSettings
        (translationSettings.parent as android.view.ViewGroup).removeView(translationSettings)
        page.removeAllViews()
        val scroll = android.widget.ScrollView(context)
        page.addView(scroll)
        scroll.addView(translationSettings)
        measure(main.root)
        val labels = listOf(library.folderFullTranslateLabel,
            library.folderGlossaryProcessingLabel, library.folderVlDirectTranslateLabel,
            library.folderOnlyTranslateBubblesLabel)
        val helpButtons = listOf(library.folderFullTranslateInfo,
            library.folderGlossaryProcessingInfo, library.folderVlDirectTranslateInfo,
            library.folderOnlyTranslateBubblesInfo)
        val switches = listOf(library.folderFullTranslateSwitch,
            library.folderGlossaryProcessingSwitch, library.folderVlDirectTranslateSwitch,
            library.folderOnlyTranslateBubblesSwitch)
        labels.indices.forEach { index ->
            val label = labels[index]
            val help = helpButtons[index]
            val switch = switches[index]
            assertTextFits(label)
            assertTrue(help.width >= 48 && help.height >= 48)
            assertTrue(label.right <= help.left)
            assertTrue(help.right <= switch.left)
            assertTrue(switch.right <= (switch.parent as View).width)
            assertFalse(help.contentDescription.isNullOrBlank())
            val before = switch.isChecked
            switch.performClick()
            assertEquals(!before, switch.isChecked)
        }
        savePreview(main.root, "$name-translation-settings")
        controller.pause().stop().destroy()
    }

    private fun assertSelectedTabReadable(context: Context, tabs: TabLayout) {
        val selected = tabs.tabTextColors!!.getColorForState(
            intArrayOf(android.R.attr.state_selected), Color.MAGENTA
        )
        val attrs = context.obtainStyledAttributes(intArrayOf(R.attr.heroStartColor))
        val heroStart = attrs.getColor(0, 0)
        attrs.recycle()
        assertTrue("Selected tab text contrasts with the indicator",
            ColorUtils.calculateContrast(selected, heroStart) >= 4.5)
    }

    private fun assertTabIndicatorContained(tabs: TabLayout) {
        val strip = tabs.getChildAt(0)
        val bitmap = Bitmap.createBitmap(tabs.width, tabs.height, Bitmap.Config.ARGB_8888)
        try {
            // Exercise the same offsets delivered by ViewPager2, including the last tab.
            for (step in 0..20) {
                val position = step / 10
                tabs.setScrollPosition(position, (step % 10) / 10f, true)
                tabs.draw(Canvas(bitmap))
                val bounds = tabs.tabSelectedIndicator.bounds
                val left = strip.left - tabs.scrollX + bounds.left
                val right = strip.left - tabs.scrollX + bounds.right
                assertTrue("Indicator left $left stays inside menu", left >= tabs.paddingLeft)
                assertTrue("Indicator right $right stays inside menu width ${tabs.width}",
                    right <= tabs.width - tabs.paddingRight)
            }
        } finally {
            bitmap.recycle()
            tabs.setScrollPosition(1, 0f, true)
        }
    }

    private fun topIn(root: View, view: View): Int {
        var top = 0
        var current: View = view
        while (current !== root) {
            top += current.top
            current = current.parent as View
        }
        return top
    }

    private fun measure(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun assertTextFits(view: TextView) {
        assertTrue("${view.text} has a layout", view.lineCount > 0)
        assertTrue("${view.text} fits vertically",
            view.height >= view.layout.height + view.compoundPaddingTop + view.compoundPaddingBottom)
        repeat(view.lineCount) { assertEquals(0, view.layout.getEllipsisCount(it)) }
    }

    private fun savePreview(view: View, name: String) {
        val output = File("build/reports/paper-ui/$name.png")
        output.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
