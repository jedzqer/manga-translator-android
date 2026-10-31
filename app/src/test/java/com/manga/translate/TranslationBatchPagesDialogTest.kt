package com.manga.translate

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.manga.translate.databinding.DialogTranslationRequestSettingsBinding
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class TranslationBatchPagesDialogTest {
    @Test
    @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun `narrow light screen supports validation saving and cancellation`() = checkDialog()

    @Test
    @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun `narrow dark screen supports long translated labels and scrolling`() = checkDialog()

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight")
    fun `portuguese dialog remains scrollable on a narrow screen`() = checkDialog()

    private fun checkDialog() {
        val controller = Robolectric.buildActivity(BatchSettingsTestActivity::class.java).setup()
        try {
            val activity = controller.get()
            val settings = SettingsStore(activity)
            settings.saveMaxConcurrency(3)
            settings.saveTranslationBatchPages(4)
            val fragment = SettingsFragment()
            activity.supportFragmentManager.beginTransaction()
                .add(android.R.id.content, fragment).commitNow()
            val settingsBinding = fragment.fragmentBinding
            val button = settingsBinding.translationRequestSettingsButton
            val parent = button.parent as ViewGroup
            assertEquals(
                parent.indexOfChild(settingsBinding.enableThinkingSwitch) + 1,
                parent.indexOfChild(settingsBinding.fetchModelsButton)
            )
            button.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val input = dialog.findViewById<View>(R.id.batch_pages_input)!!
            val scroll = input.parent.parent.parent.parent as ScrollView
            val binding = DialogTranslationRequestSettingsBinding.bind(scroll)
            assertEquals("3", binding.maxConcurrencyInput.text.toString())
            assertEquals("4", binding.batchPagesInput.text.toString())
            assertFalse(scroll.clipToPadding)
            val density = activity.resources.displayMetrics.density
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((140 * density).toInt(), View.MeasureSpec.EXACTLY)
            )
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            assertTrue(binding.batchPagesInput.width > 0)

            assertTrue(binding.maxConcurrencyInput.width > 0)
            binding.maxConcurrencyInput.setText("5")
            for (invalidPages in listOf("", "0", "501")) {
                binding.batchPagesInput.setText(invalidPages)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertNotNull(binding.batchPagesLayout.error)
                assertEquals(3, settings.loadMaxConcurrency())
                assertEquals(4, settings.loadTranslationBatchPages())
            }
            binding.batchPagesInput.setText("500")
            for (invalidConcurrency in listOf("", "0", "201")) {
                binding.maxConcurrencyInput.setText(invalidConcurrency)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertNotNull(binding.maxConcurrencyLayout.error)
                assertEquals(3, settings.loadMaxConcurrency())
                assertEquals(4, settings.loadTranslationBatchPages())
            }
            binding.maxConcurrencyInput.setText("5")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
            assertEquals(5, settings.loadMaxConcurrency())
            assertEquals(500, settings.loadTranslationBatchPages())
            assertEquals(
                activity.getString(R.string.translation_request_settings_button_format, 5, 500),
                button.text.toString()
            )

            button.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            val reopenedInput = reopened.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.batch_pages_input
            )!!
            assertEquals("500", reopenedInput.text.toString())
            val reopenedConcurrency = reopened.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.max_concurrency_input
            )!!
            assertEquals("5", reopenedConcurrency.text.toString())
            reopenedInput.setText("10")
            reopenedConcurrency.setText("8")
            reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(reopened.isShowing)
            assertEquals(5, settings.loadMaxConcurrency())
            assertEquals(500, settings.loadTranslationBatchPages())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

class BatchSettingsTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MangaTranslator)
        super.onCreate(savedInstanceState)
    }
}
