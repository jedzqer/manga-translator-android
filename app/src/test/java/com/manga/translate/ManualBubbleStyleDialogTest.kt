package com.manga.translate

import android.os.Bundle
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.manga.translate.app.MangaTranslateApp
import com.manga.translate.databinding.FragmentReadingBinding
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.TranslationResult
import com.manga.translate.reader.ReadingFragment
import com.manga.translate.reader.ManualBubbleShape
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MangaTranslateApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ManualBubbleStyleDialogTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h480dp-notnight-mdpi")
    fun `Chinese light creation asks for style and enters eight handle editing`() = checkDialog()

    @Test @Config(qualifiers = "b+zh+Hant-w320dp-h480dp-night-mdpi")
    fun `traditional Chinese dark creation asks for style`() = checkDialog()

    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight-mdpi")
    fun `English style dialog fits a narrow screen`() = checkDialog()

    @Test @Config(qualifiers = "pt-rBR-w320dp-h480dp-notnight-mdpi")
    fun `Portuguese long title and options remain accessible`() = checkDialog()

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night-mdpi")
    fun `Russian dark style dialog remains accessible`() = checkDialog()

    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight-mdpi")
    fun `webtoon creation also asks for style and creates an editable ellipse`() = checkDialog(webtoon = true)

    private fun checkDialog(webtoon: Boolean = false) {
        val controller = Robolectric.buildActivity(ManualBubbleTestActivity::class.java).setup()
        try {
            val activity = controller.get()
            val fragment = ReadingFragment()
            activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
            shadowOf(Looper.getMainLooper()).idle()
            val binding = FragmentReadingBinding.bind(fragment.requireView())
            setField(fragment, "currentTranslation", TranslationResult("page.jpg", 800, 1000, emptyList()))
            setField(fragment, "isEditMode", true)
            if (webtoon) setField(fragment, "folderReadingMode", FolderReadingMode.WEBTOON_SCROLL)
            val overlay = binding.translationOverlay
            overlay.setTranslations(result(fragment))
            overlay.setDisplayRect(android.graphics.RectF(0f, 0f, 800f, 1000f))
            overlay.setEditMode(true)
            fun show(): AlertDialog {
                binding.readingAddButton.performClick()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals(activity.getString(R.string.reading_bubble_shape_title),
                    dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
                assertEquals(2, dialog.listView.adapter.count)
                assertEquals(activity.getString(R.string.reading_bubble_shape_rectangle),
                    dialog.listView.adapter.getItem(0))
                assertEquals(activity.getString(R.string.reading_bubble_shape_ellipse),
                    dialog.listView.adapter.getItem(1))
                val root = dialog.window!!.decorView
                root.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.AT_MOST))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertTrue(dialog.listView.height > 0)
                for (i in 0 until dialog.listView.childCount) {
                    val label = dialog.listView.getChildAt(i).findViewById<TextView>(android.R.id.text1)
                    assertTrue(label.width > 0)
                    assertEquals(0, label.layout.getEllipsisCount(0))
                }
                assertFalse(overlay.isInCreateBubbleMode())
                return dialog
            }
            // Dismissing the prompt must not enter drawing or create data.
            show().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(result(fragment).bubbles.isEmpty())
            assertFalse(overlay.isInCreateBubbleMode())
            fun choose(index: Int) {
                val dialog = show()
                dialog.listView.performItemClick(dialog.listView.getChildAt(index), index, index.toLong())
                if (!webtoon) {
                    assertTrue(overlay.isInCreateBubbleMode())
                    for ((action, x, y) in listOf(
                        Triple(MotionEvent.ACTION_DOWN, 100f, 100f),
                        Triple(MotionEvent.ACTION_MOVE, 300f, 300f),
                        Triple(MotionEvent.ACTION_UP, 300f, 300f)
                    )) {
                        val event = MotionEvent.obtain(0, 100, action, x, y, 0)
                        overlay.onTouchEvent(event)
                        event.recycle()
                    }
                    assertFalse(overlay.isInCreateBubbleMode())
                }
            }
            choose(1)
            val ellipse = result(fragment).bubbles.single()
            assertNotNull(ellipse.maskContour)
            assertTrue(ellipse.supportsResizeEditing())
            if (!webtoon) {
                assertEquals(ellipse.id, overlay.getResizeModeBubbleId())
                overlay.setOffsets(mapOf(ellipse.id to (20f to 30f)))
                for ((action, x) in listOf(
                    MotionEvent.ACTION_DOWN to 320f,
                    MotionEvent.ACTION_MOVE to 360f,
                    MotionEvent.ACTION_UP to 360f
                )) {
                    val event = MotionEvent.obtain(0, 100, action, x, 230f, 0)
                    overlay.onTouchEvent(event)
                    event.recycle()
                }
                val resized = result(fragment).bubbles.single()
                assertEquals(RectF(120f, 130f, 360f, 330f), resized.rect)
                assertArrayEquals(ManualBubbleShape.ELLIPSE.contour(resized.rect, 800, 1000),
                    resized.maskContour, 0.00001f)
                assertTrue(overlay.getOffsets().isEmpty())
                binding.readingBubbleSizePlus.performClick()
                val enlarged = result(fragment).bubbles.single()
                assertTrue(enlarged.rect.width() > resized.rect.width())
                assertArrayEquals(ManualBubbleShape.ELLIPSE.contour(enlarged.rect, 800, 1000),
                    enlarged.maskContour, 0.00001f)
            }
            choose(0)
            assertEquals(2, result(fragment).bubbles.size)
            assertNull(result(fragment).bubbles.last().maskContour)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun setField(fragment: ReadingFragment, name: String, value: Any) {
        ReadingFragment::class.java.getDeclaredField(name).apply { isAccessible = true }.set(fragment, value)
    }

    private fun result(fragment: ReadingFragment): TranslationResult =
        ReadingFragment::class.java.getDeclaredField("currentTranslation").apply { isAccessible = true }
            .get(fragment) as TranslationResult
}

class ManualBubbleTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MangaTranslator)
        super.onCreate(savedInstanceState)
    }
}
