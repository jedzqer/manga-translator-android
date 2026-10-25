package com.manga.translate

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.manga.translate.databinding.DialogNormalBubbleRenderSettingsBinding
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import com.manga.translate.settings.ui.dialogs.NormalBubbleRenderSettingsDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class NormalBubbleExpansionDialogTest {
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun narrowLightDialog() = checkDialog()

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun narrowDarkDialog() = checkDialog()

    private fun checkDialog() {
        val controller = Robolectric.buildActivity(ExpansionSettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val settings = SettingsStore(activity)
            settings.saveNormalBubbleRenderSettings(settings.loadNormalBubbleRenderSettings().copy(
                shrinkPercent = 0,
                freeBubbleSizeAdjustPercent = 10
            ))
            val fragment = SettingsFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            fun show(): Pair<AlertDialog, DialogNormalBubbleRenderSettingsBinding> {
                NormalBubbleRenderSettingsDialog(fragment, settings).show()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                var root: View = dialog.findViewById(R.id.normal_bubble_shrink_percent_input)!!
                while (root !is ScrollView) root = root.parent as View
                return dialog to DialogNormalBubbleRenderSettingsBinding.bind(root)
            }
            val (dialog, binding) = show()
            assertEquals("0", binding.normalBubbleShrinkPercentInput.text.toString())
            assertEquals("10", binding.normalBubbleFreeShrinkPercentInput.text.toString())
            val scroll = binding.root
            val density = activity.resources.displayMetrics.density
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((180 * density).toInt(), View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            assertFalse(scroll.clipToPadding)
            assertTrue(binding.normalBubbleFreeShrinkPercentInput.width > 0)
            assertEquals("100", binding.normalBubbleTextFillPercentInput.text.toString())
            assertEquals("100", binding.normalBubbleFreeTextFillPercentInput.text.toString())
            binding.normalBubbleTextFillPercentInput.setText("60")
            binding.normalBubbleFreeTextFillPercentInput.setText("20")
            binding.normalBubbleShrinkPercentInput.setText("4")
            binding.normalBubbleFreeShrinkPercentInput.setText("-12")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val saved = settings.loadNormalBubbleRenderSettings()
            assertEquals(60, saved.textFillPercent)
            assertEquals(20, saved.freeBubbleTextFillPercent)
            assertEquals(4, saved.shrinkPercent)
            assertEquals(-12, saved.freeBubbleSizeAdjustPercent)
            val (cancelDialog, cancelBinding) = show()
            assertEquals("60", cancelBinding.normalBubbleTextFillPercentInput.text.toString())
            assertEquals("20", cancelBinding.normalBubbleFreeTextFillPercentInput.text.toString())
            cancelBinding.normalBubbleTextFillPercentInput.setText("90")
            cancelBinding.normalBubbleFreeTextFillPercentInput.setText("80")
            cancelBinding.normalBubbleFreeShrinkPercentInput.setText("0")
            cancelDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(-12, settings.loadNormalBubbleRenderSettings().freeBubbleSizeAdjustPercent)
            assertEquals(60, settings.loadNormalBubbleRenderSettings().textFillPercent)
            assertEquals(20, settings.loadNormalBubbleRenderSettings().freeBubbleTextFillPercent)
        } finally {
            controller.destroy()
        }
    }
}

class ExpansionSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MangaTranslator)
        super.onCreate(savedInstanceState)
    }
}
