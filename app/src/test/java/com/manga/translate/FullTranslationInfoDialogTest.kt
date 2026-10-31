package com.manga.translate

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.manga.translate.library.LibraryDialogs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullTranslationInfoDialogTest {
    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp-notnight")
    fun `simplified Chinese explanation scrolls in narrow light dialog`() = checkDialog(expectScrolling = true)

    @Test
    @Config(qualifiers = "b+zh+Hant-w320dp-h480dp-night")
    fun `traditional Chinese explanation scrolls in narrow dark dialog`() = checkDialog(expectScrolling = true)

    @Test
    @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun `English explanation scrolls and dismisses`() = checkDialog(expectScrolling = true)

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight")
    fun `Portuguese explanation scrolls and dismisses`() = checkDialog(expectScrolling = true)

    @Test
    @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun `long Russian explanation scrolls and dismisses`() = checkDialog(expectScrolling = true)

    private fun checkDialog(expectScrolling: Boolean) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_MangaTranslator)
        controller.setup()
        try {
            val activity = controller.get()
            LibraryDialogs().showFullTranslateInfo(activity)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val expected = activity.getString(R.string.folder_full_translate_info)
            assertTrue(expected.isNotBlank())
            val views = descendants(requireNotNull(dialog.window).decorView).toList()
            val message = views.filterIsInstance<TextView>().single { it.text.toString() == expected }
            val scroll = message.parent as NestedScrollView
            val density = activity.resources.displayMetrics.density
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((90 * density).toInt(), View.MeasureSpec.EXACTLY)
            )
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            assertNull(message.ellipsize)
            val textLayout = requireNotNull(message.layout)
            assertEquals(expected.length, textLayout.getLineEnd(textLayout.lineCount - 1))
            assertTrue(textLayout.height <= message.height - message.compoundPaddingTop - message.compoundPaddingBottom)
            if (expectScrolling) {
                assertTrue("Message height ${message.height}, viewport ${scroll.height}", message.height > scroll.height)
            } else {
                assertEquals(scroll.height, message.height)
            }
            scroll.scrollTo(0, message.height)
            if (expectScrolling) {
                assertTrue(scroll.scrollY > 0)
            } else {
                assertEquals(0, scroll.scrollY)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
