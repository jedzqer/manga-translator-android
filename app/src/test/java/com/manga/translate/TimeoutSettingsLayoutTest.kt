package com.manga.translate

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimeoutSettingsLayoutTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h480dp-notnight")
    fun `Chinese timeout explanations wrap`() = verifyLayouts()
    @Test @Config(qualifiers = "b+zh+Hant-w320dp-h480dp-night")
    fun `traditional Chinese timeout explanations wrap`() = verifyLayouts()
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun `English timeout explanations wrap`() = verifyLayouts()
    @Test @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight")
    fun `Portuguese timeout explanations wrap`() = verifyLayouts()
    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun `Russian timeout explanations wrap`() = verifyLayouts()

    private fun verifyLayouts() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MangaTranslator)
        val density = context.resources.displayMetrics.density
        for ((layout, inputId) in listOf(
            R.layout.fragment_settings to R.id.api_timeout_input,
            R.layout.dialog_floating_translate_settings to R.id.floating_api_timeout_input
        )) {
            val root = LayoutInflater.from(context).inflate(layout, null) as ViewGroup
            root.measure(View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val note = root.findViewById<TextView>(R.id.api_timeout_policy_description)
            assertTrue(note.lineCount > 1)
            assertNull(note.ellipsize)
            assertEquals(note.text.length, note.layout.getLineEnd(note.lineCount - 1))
            assertTrue(note.height >= note.layout.height + note.compoundPaddingTop + note.compoundPaddingBottom)
            assertTrue(root.getChildAt(0).height > root.height)
            val input = root.findViewById<EditText>(inputId)
            assertTrue(input.isEnabled)
            input.requestFocus()
            input.setText("1200")
            assertEquals("1200", input.text.toString())
            when (root) {
                is NestedScrollView -> root.fullScroll(View.FOCUS_DOWN)
                is ScrollView -> root.fullScroll(View.FOCUS_DOWN)
            }
            assertTrue(root.scrollY > 0)
        }
    }
}
