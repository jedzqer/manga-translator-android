package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.manga.translate.databinding.DialogLlmParamsBinding
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogButtonLayoutTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h480dp-notnight-mdpi")
    fun narrowLightDialogs() = checkDialogs("zh-light")

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night-mdpi")
    fun narrowDarkDialogs() = checkDialogs("ru-dark")

    @Test @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight-mdpi")
    fun longActionLabels() = checkDialogs("pt-light")

    private fun checkDialogs(name: String) {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_MangaTranslator)
        val activity = controller.setup().get()
        try {
            for (form in listOf(true, false)) {
                var confirmed = false
                val builder = AlertDialog.Builder(activity)
                    .setTitle(R.string.llm_params_title)
                    .setPositiveButton(android.R.string.ok) { _, _ -> confirmed = true }
                    .setNegativeButton(android.R.string.cancel, null)
                val binding = if (form) DialogLlmParamsBinding.inflate(
                    android.view.LayoutInflater.from(builder.context)
                ) else null
                if (binding != null) {
                    builder.setView(binding.root)
                } else {
                    builder.setSingleChoiceItems(arrayOf(activity.getString(R.string.llm_params_note)), 0, null)
                        .setNeutralButton(R.string.share_error_logs, null)
                }
                val dialog = builder.show()
                val decor = dialog.window!!.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.AT_MOST)
                )
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                val panel = dialog.findViewById<View>(androidx.appcompat.R.id.buttonPanel)!!
                for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
                    val button = dialog.getButton(which)
                    if (button.visibility != View.VISIBLE) continue
                    val bounds = android.graphics.Rect(0, 0, button.width, button.height)
                    (panel as ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
                    assertTrue("Button top stays inside its panel: $bounds", bounds.top >= panel.paddingTop)
                    val buttonBar = button.parent as ViewGroup
                    assertTrue("Button top clears the button bar's clipping padding", button.top >= buttonBar.paddingTop)
                    assertTrue("Button bottom clears the button bar's clipping padding", button.bottom <= buttonBar.height - buttonBar.paddingBottom)
                    assertTrue("Button stays above the dialog's inset bottom edge", panel.height - bounds.bottom >= 12)
                    assertTrue(bounds.left >= 0 && bounds.right <= panel.width)
                    assertTrue(button.height >= button.layout.height + button.compoundPaddingTop + button.compoundPaddingBottom)
                    repeat(button.lineCount) { assertEquals(0, button.layout.getEllipsisCount(it)) }
                    button.isPressed = true
                    savePreview(decor, "$name-${if (form) "form" else "list"}-$which-pressed")
                    button.isPressed = false
                }
                if (binding != null) {
                    val scroll = binding.root as ScrollView
                    scroll.fullScroll(View.FOCUS_DOWN)
                    assertTrue("Long settings form remains scrollable", scroll.scrollY > 0)
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(confirmed)
                assertFalse(dialog.isShowing)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun savePreview(view: View, name: String) {
        val output = File("build/reports/dialog-buttons/$name.png")
        output.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
