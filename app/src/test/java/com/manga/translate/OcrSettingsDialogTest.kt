package com.manga.translate

import android.view.View
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.manga.translate.databinding.DialogOcrSettingsBinding
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import com.manga.translate.settings.ui.dialogs.OcrSettingsDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OcrSettingsDialogTest {
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun narrowLightDialog() = checkDialog()

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun narrowDarkDialog() = checkDialog()

    private fun checkDialog() {
        val controller = Robolectric.buildActivity(ExpansionSettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val settings = SettingsStore(activity)
            settings.saveOcrApiSettings(settings.loadOcrApiSettings().copy(
                useLocalOcr = true, apiSupplementEnabled = false, manualBubblesUseApi = false))
            val fragment = SettingsFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            fun show(): Pair<AlertDialog, DialogOcrSettingsBinding> {
                OcrSettingsDialog(fragment, settings).show()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                var root: View = dialog.findViewById(R.id.use_local_ocr_switch)!!
                while (root !is ScrollView) root = root.parent as View
                return dialog to DialogOcrSettingsBinding.bind(root)
            }
            val (dialog, binding) = show()
            val reuseMainApiHint = activity.getString(R.string.ocr_api_reuse_main_hint)
            assertEquals(reuseMainApiHint, binding.ocrApiUrlLayout.placeholderText)
            assertEquals(reuseMainApiHint, binding.ocrApiKeyLayout.placeholderText)
            assertEquals(reuseMainApiHint, binding.ocrModelNameLayout.placeholderText)
            assertEquals(View.GONE, binding.ocrApiUrlLayout.visibility)
            binding.apiOcrSupplementSlider.progress = 1
            assertEquals(View.VISIBLE, binding.ocrApiUrlLayout.visibility)
            assertTrue(binding.ocrApiUrlInput.isEnabled)
            binding.apiOcrSupplementSlider.progress = 0
            assertEquals(View.GONE, binding.ocrApiUrlLayout.visibility)
            binding.manualBubblesApiOcrSwitch.isChecked = true
            assertEquals(View.VISIBLE, binding.ocrApiUrlLayout.visibility)
            binding.useLocalOcrSwitch.isChecked = false
            assertFalse(binding.apiOcrSupplementSlider.isEnabled)
            binding.useLocalOcrSwitch.isChecked = true
            assertTrue(binding.apiOcrSupplementSlider.isEnabled)
            for (level in 1..3) {
                binding.apiOcrSupplementSlider.progress = level
                assertEquals(View.VISIBLE, binding.ocrApiUrlLayout.visibility)
                assertTrue(binding.apiOcrSupplementDescription.text.isNotBlank())
            }
            val scroll = binding.root
            val density = activity.resources.displayMetrics.density
            scroll.measure(View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((180 * density).toInt(), View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            assertFalse(scroll.clipToPadding)
            assertTrue(binding.apiOcrSupplementDescription.layout.lineCount > 1)
            assertEquals(0, binding.apiOcrSupplementDescription.layout.getEllipsisCount(0))
            assertTrue(binding.apiOcrSupplementSlider.width > 0)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(settings.loadOcrApiSettings().manualBubblesUseApi)
            assertTrue(settings.loadOcrApiSettings().apiSupplementEnabled)
            assertEquals(3, settings.loadOcrApiSettings().apiSupplementLevel)
            val (cancelDialog, cancelBinding) = show()
            assertTrue(cancelBinding.manualBubblesApiOcrSwitch.isChecked)
            assertEquals(3, cancelBinding.apiOcrSupplementSlider.progress)
            cancelBinding.apiOcrSupplementSlider.progress = 0
            cancelDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(settings.loadOcrApiSettings().apiSupplementEnabled)
        } finally { controller.destroy() }
    }
}
