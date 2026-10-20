package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.manga.translate.settings.ui.dialogs.CustomPromptDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomPromptDialogTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h480dp-notnight-mdpi")
    fun narrowLight() = checkEditor("light")

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night-mdpi")
    fun narrowDarkLongLabels() = checkEditor("dark")

    private fun checkEditor(name: String) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_MangaTranslator)
        val activity = controller.setup().get()
        try {
            val fragment = CustomPromptDialog()
            fragment.showNow(activity.supportFragmentManager, "prompts")
            val dialog = fragment.requireDialog()
            val editor = dialog.findViewById<EditText>(R.id.prompt_editor)
            val spinner = dialog.findViewById<Spinner>(R.id.prompt_mode)
            assertEquals(7, spinner.count)
            assertTrue(editor.text.contains("system_prompt"))
            editor.setText("draft retained when switching modes")
            spinner.setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(editor.text.contains("system_prompt"))
            spinner.setSelection(0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("draft retained when switching modes", editor.text.toString())
            val decor = dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            val scroll = editor.parent.parent as ScrollView
            scroll.fullScroll(View.FOCUS_DOWN)
            assertTrue(scroll.scrollY > 0)
            for (id in listOf(R.id.prompt_import, R.id.prompt_export, R.id.prompt_reset)) {
                val button = dialog.findViewById<android.widget.Button>(id)
                assertTrue(button.width > 0)
                assertTrue(button.height >= button.layout.height + button.compoundPaddingTop + button.compoundPaddingBottom)
                repeat(button.lineCount) { assertEquals(0, button.layout.getEllipsisCount(it)) }
            }
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val file = File("build/reports/custom-prompts/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            fragment.dismissNow()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
