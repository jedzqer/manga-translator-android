package com.manga.translate

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.EditText
import androidx.appcompat.widget.SwitchCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VlSettingsLayoutTest {
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun `English light narrow settings wrap and scroll`() = verifySettings()
    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun `Russian dark narrow settings wrap and scroll`() = verifySettings()
    @Test @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight")
    fun `Portuguese narrow settings wrap and scroll`() = verifySettings()

    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun `batch settings light narrow screen remains editable and scrollable`() = verifyBatchSettings()
    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun `batch settings dark narrow screen wraps long warning`() = verifyBatchSettings()

    private fun verifyBatchSettings() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MangaTranslator)
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_translation_request_settings, null) as ScrollView
        val density = context.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val note = (root.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
        assertFalse(root.clipToPadding)
        assertTrue(note.lineCount > 1)
        assertEquals(0, note.layout.getEllipsisCount(note.lineCount - 1))
        val input = root.findViewById<EditText>(R.id.batch_pages_input)
        input.setText("15")
        assertEquals("15", input.text.toString())
        assertTrue(input.requestFocus())
        root.fullScroll(View.FOCUS_DOWN)
        assertTrue(root.scrollY > 0)
    }

    private fun verifySettings() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MangaTranslator)
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_floating_translate_settings, null) as ScrollView
        val density = context.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val label = root.findViewById<SwitchCompat>(R.id.floating_use_vl_direct_translate_switch)
        val note = root.findViewById<TextView>(R.id.floating_translate_vl_note)
        assertFalse(root.clipToPadding)
        assertTrue(root.getChildAt(0).height > root.height)
        assertTrue(note.lineCount > 1)
        assertEquals(0, note.layout.getEllipsisCount(note.lineCount - 1))
        assertTrue(label.measuredHeight >= label.layout.height + label.compoundPaddingTop + label.compoundPaddingBottom)
        val before = label.isChecked
        label.performClick()
        assertEquals(!before, label.isChecked)
        root.fullScroll(View.FOCUS_DOWN)
        assertTrue(root.scrollY > 0)
    }
}
