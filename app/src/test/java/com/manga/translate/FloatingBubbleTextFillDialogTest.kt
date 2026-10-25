package com.manga.translate

import android.os.Looper
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.manga.translate.databinding.DialogFloatingBubbleRenderSettingsBinding
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import com.manga.translate.settings.ui.dialogs.FloatingBubbleRenderSettingsDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class FloatingBubbleTextFillDialogTest {
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun narrowLightDialog() = checkDialog()

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun narrowDarkDialog() = checkDialog()

    private fun checkDialog() {
        val controller = Robolectric.buildActivity(ExpansionSettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val settings = SettingsStore(activity)
            settings.saveFloatingBubbleRenderSettings(settings.loadFloatingBubbleRenderSettings().copy(textFillPercent = 100))
            settings.saveNormalBubbleRenderSettings(settings.loadNormalBubbleRenderSettings().copy(
                textFillPercent = 60, freeBubbleTextFillPercent = 40))
            val fragment = SettingsFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            fun show(): Pair<AlertDialog, DialogFloatingBubbleRenderSettingsBinding> {
                FloatingBubbleRenderSettingsDialog(fragment, settings).show()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                var root: View = dialog.findViewById(R.id.floating_bubble_text_fill_percent_input)!!
                while (root !is ScrollView) root = root.parent as View
                return dialog to DialogFloatingBubbleRenderSettingsBinding.bind(root)
            }
            val (dialog, binding) = show()
            assertEquals("100", binding.floatingBubbleTextFillPercentInput.text.toString())
            val scroll = binding.root
            val density = activity.resources.displayMetrics.density
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((180 * density).toInt(), View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            assertFalse(scroll.clipToPadding)
            assertTrue(binding.floatingBubbleTextFillPercentInput.width > 0)
            binding.floatingBubbleTextFillPercentInput.setText("1")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(20, settings.loadFloatingBubbleRenderSettings().textFillPercent)
            assertEquals(60, settings.loadNormalBubbleRenderSettings().textFillPercent)
            assertEquals(40, settings.loadNormalBubbleRenderSettings().freeBubbleTextFillPercent)
            val (cancelDialog, cancelBinding) = show()
            assertEquals("20", cancelBinding.floatingBubbleTextFillPercentInput.text.toString())
            cancelBinding.floatingBubbleTextFillPercentInput.setText("80")
            cancelDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(20, settings.loadFloatingBubbleRenderSettings().textFillPercent)
            val (emptyDialog, emptyBinding) = show()
            emptyBinding.floatingBubbleTextFillPercentInput.setText("")
            emptyDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(20, settings.loadFloatingBubbleRenderSettings().textFillPercent)
        } finally {
            controller.destroy()
        }
    }
}
