package com.manga.translate.library

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.R
import com.manga.translate.model.ImageItem
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LibraryImageStatusUiTest {
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun pendingStatusFitsNarrowLightRow() = checkRow()

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun pendingStatusFitsNarrowDarkRow() = checkRow()

    private fun checkRow() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MangaTranslator)
        var clicked = false
        val adapter = FolderImageAdapter({}, {}, { clicked = true })
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        val item = ImageItem(File("A very long manga page name that wraps on a narrow phone screen.jpg"), false, false)
        holder.bind(item, false, false)
        val row = holder.itemView
        row.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        val status = row.findViewById<TextView>(R.id.image_status)
        assertEquals(context.getString(R.string.image_status_pending), status.text.toString())
        assertTrue(status.measuredWidth <= 320)
        assertTrue(status.height > 0)
        row.performClick()
        assertTrue(clicked)
        holder.bind(item.copy(translated = true, statusLoaded = true), false, false)
        assertEquals(context.getString(R.string.image_translated), status.text.toString())
    }
}
