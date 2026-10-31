package com.manga.translate

import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.manga.translate.databinding.DialogModelContextBinding
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.ModelContextLimits
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class ModelContextDialogTest {
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun `narrow light dialog validates saves and cancels`() = checkDialog()
    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun `narrow dark dialog scrolls long labels`() = checkDialog()
    @Test @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight")
    fun `portuguese dialog supports long explanations`() = checkDialog()

    private fun checkDialog() {
        val controller = Robolectric.buildActivity(BatchSettingsTestActivity::class.java).setup()
        try {
            val activity = controller.get()
            val settings = SettingsStore(activity)
            settings.save(ApiSettings("https://example.test/v1", "key", "model"))
            val fragment = SettingsFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            val button = fragment.fragmentBinding.modelContextButton
            val parent = button.parent as ViewGroup
            assertEquals(parent.indexOfChild(fragment.fragmentBinding.thinkingLengthButton) + 1,
                parent.indexOfChild(button))
            fun open(): Pair<AlertDialog, DialogModelContextBinding> {
                button.performClick()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                return dialog to DialogModelContextBinding.bind(dialog.findViewById<ScrollView>(R.id.model_context_scroll)!!)
            }
            val (dialog, binding) = open()
            assertEquals(activity.getString(R.string.model_context_unknown, 262144), binding.contextInputLayout.hint.toString())
            assertEquals(activity.getString(R.string.model_context_unknown, 65536), binding.contextOutputLayout.hint.toString())
            assertEquals(activity.getString(R.string.model_context_unknown, 262144), binding.contextTotalLayout.hint.toString())
            val scroll = binding.root
            val density = activity.resources.displayMetrics.density
            scroll.measure(View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((140 * density).toInt(), View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertFalse(scroll.clipToPadding)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            assertTrue(binding.contextTotalInput.width > 0)
            for (invalid in listOf("0", "2147483648")) {
                binding.contextInputInput.setText(invalid)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertNotNull(binding.contextInputLayout.error)
            }
            binding.contextInputInput.setText("100000")
            binding.contextOutputInput.setText("20000")
            binding.contextTotalInput.setText("110000")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val expected = ModelContextLimits(100000, 20000, 110000)
            assertFalse(dialog.isShowing)
            assertEquals(expected, settings.loadModelContextSettings().limits)
            val (cancel, draft) = open()
            assertEquals("20000", draft.contextOutputInput.text.toString())
            cancel.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals("", draft.contextOutputInput.text.toString())
            cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(expected, settings.loadModelContextSettings().limits)
            val (clear, _) = open()
            clear.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            clear.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(ModelContextLimits(), settings.loadModelContextSettings().limits)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
