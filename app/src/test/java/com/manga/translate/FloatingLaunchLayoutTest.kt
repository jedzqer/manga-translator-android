package com.manga.translate

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.manga.translate.library.LibraryDialogs
import com.manga.translate.model.TranslationLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class FloatingLaunchLayoutTest {
    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun `launch selection confirms orientation and cancel discards draft`() = checkDialog()

    @Test @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight")
    fun `launch selection supports narrow light screen`() = checkDialog()

    private fun checkDialog() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_MangaTranslator)
        controller.setup()
        var selected: Boolean? = null
        fun show(): AlertDialog {
            LibraryDialogs().showLanguageSettingConfirmDialog(activity, TranslationLanguage.entries,
                TranslationLanguage.JA_TO_ZH, true) { _, horizontal -> selected = horizontal }
            return ShadowDialog.getLatestDialog() as AlertDialog
        }
        fun switch(view: View): SwitchCompat? {
            if (view is SwitchCompat) return view
            if (view is ViewGroup) repeat(view.childCount) { switch(view.getChildAt(it))?.let { return it } }
            return null
        }
        try {
            var dialog = show()
            val toggle = requireNotNull(switch(dialog.window!!.decorView))
            assertFalse(toggle.isChecked)
            toggle.isChecked = true
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(selected)
            dialog = show()
            requireNotNull(switch(dialog.window!!.decorView)).isChecked = true
            assertEquals(TranslationLanguage.entries.size, dialog.listView.adapter.count)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(false, selected)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
