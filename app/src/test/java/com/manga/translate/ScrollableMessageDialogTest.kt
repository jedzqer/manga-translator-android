package com.manga.translate

import android.view.View
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.platform.SafeNestedScrollView
import com.manga.translate.platform.createWithScrollableMessage
import com.manga.translate.platform.setScrollableMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ScrollableMessageDialogTest {
    @Test
    @Config(qualifiers = "w320dp-h480dp-notnight")
    fun longMessageWorksInNarrowLightDialog() = checkLongMessage()

    @Test
    @Config(qualifiers = "w320dp-h480dp-night")
    fun longMessageWorksInNarrowDarkDialog() = checkLongMessage()

    private fun checkLongMessage() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(),
            androidx.appcompat.R.style.Theme_AppCompat_DayNight
        )
        // A subclass failed the old getDeclaredField lookup on Builder.P.
        val builder = object : AlertDialog.Builder(context) {}
        val message = "Long message 中文 Русский Português\n".repeat(150)
        var clicked = false
        val dialog = builder.setMessage("obsolete")
            .setScrollableMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> clicked = true }
            .createWithScrollableMessage()
        dialog.show()
        try {
            val decor = dialog.window!!.decorView
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
            )
            decor.layout(0, 0, 320, 480)
            val views = descendants(decor)
            assertEquals(1, views.filterIsInstance<TextView>().count { it.text.toString() == message })
            assertFalse(views.filterIsInstance<TextView>().any { it.text.toString() == "obsolete" })
            val scroll = views.filterIsInstance<SafeNestedScrollView>().single()
            assertTrue(scroll.getChildAt(0).height > scroll.height)
            scroll.scrollTo(0, 100)
            assertTrue(scroll.scrollY > 0)
            assertFalse(scroll.isVerticalScrollBarEnabled)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(clicked)
        } finally {
            dialog.dismiss()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
