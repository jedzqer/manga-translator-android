package com.manga.translate

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationResult
import com.manga.translate.reader.BubbleResizeHandle
import com.manga.translate.reader.FloatingTranslationView
import com.manga.translate.reader.ManualBubbleShape
import com.manga.translate.reader.withResizedGeometry
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.TranslationStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualBubbleEditingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `all eight handles resize only their corresponding edges with zoom and offsets`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val base = RectF(160f, 240f, 480f, 640f)
        val bubble = manualBubble(base)
        val originalContour = bubble.maskContour!!.copyOf()
        for (handle in BubbleResizeHandle.entries) {
            val view = FloatingTranslationView(context)
            view.layout(0, 0, 500, 600)
            view.setTranslations(page(bubble))
            view.setDisplayRect(RectF(20f, 30f, 420f, 530f))
            view.setOffsets(mapOf(1 to (30f to 50f)))
            view.setEditMode(true)
            view.enterResizeMode(1)
            var resized: RectF? = null
            view.onBubbleResized = { id, rect -> assertEquals(1, id); resized = rect }
            // Start slightly away from the center, including outside the selection rectangle.
            val x = 115f + 160f * handle.xFraction - 4f
            val y = 175f + 200f * handle.yFraction - 4f
            assertTrue("Handle $handle captures touches outside its frame", view.hasBubbleAt(x, y))
            touch(view, MotionEvent.ACTION_DOWN, x, y)
            touch(view, MotionEvent.ACTION_MOVE, x + 20f, y + 30f)
            touch(view, MotionEvent.ACTION_UP, x + 20f, y + 30f)
            val expected = RectF(
                if (handle.xFraction == 0f) 200f else 160f,
                if (handle.yFraction == 0f) 300f else 240f,
                if (handle.xFraction == 1f) 520f else 480f,
                if (handle.yFraction == 1f) 700f else 640f
            )
            assertEquals("Handle $handle", expected, resized)
            assertEquals(base, bubble.rect)
            assertArrayEquals(originalContour, bubble.maskContour, 0f)
            view.setEditMode(false)
        }
    }

    @Test
    fun `handles can grow and shrink without crossing the opposite edge or page limits`() {
        val base = RectF(100f, 100f, 300f, 300f)
        val limits = RectF(0f, 0f, 400f, 400f)
        assertEquals(RectF(0f, 0f, 300f, 300f),
            BubbleResizeHandle.TOP_LEFT.resize(base, -1000f, -1000f, 24f, 24f, limits))
        assertEquals(RectF(276f, 276f, 300f, 300f),
            BubbleResizeHandle.TOP_LEFT.resize(base, 1000f, 1000f, 24f, 24f, limits))
        assertEquals(RectF(100f, 100f, 400f, 400f),
            BubbleResizeHandle.BOTTOM_RIGHT.resize(base, 1000f, 1000f, 24f, 24f, limits))
        assertEquals(RectF(100f, 100f, 124f, 124f),
            BubbleResizeHandle.BOTTOM_RIGHT.resize(base, -1000f, -1000f, 24f, 24f, limits))
    }

    @Test
    fun `ellipse geometry stays editable after resizing and loading the existing JSON format`() {
        val original = manualBubble(RectF(100f, 200f, 300f, 600f))
        val resized = original.withResizedGeometry(RectF(50f, 100f, 450f, 700f), 800, 1000)
        val expected = ManualBubbleShape.ELLIPSE.contour(resized.rect, 800, 1000)!!
        assertArrayEquals(expected, resized.maskContour, 0.00001f)
        val image = temporaryFolder.newFile("page.jpg")
        TranslationStore().save(image, page(resized))
        // A separate Store exercises disk decoding rather than the memory cache.
        val loaded = TranslationStore().load(image)!!.bubbles.single()
        assertTrue(loaded.supportsResizeEditing())
        assertEquals(resized.rect, loaded.rect)
        assertArrayEquals(expected, loaded.maskContour, 0.00001f)
        assertNull(ManualBubbleShape.RECTANGLE.contour(resized.rect, 800, 1000))
        assertFalse(original.copy(source = BubbleSource.BUBBLE_DETECTOR).supportsResizeEditing())
        assertTrue(original.copy(maskContour = null).supportsResizeEditing())
    }

    @Test
    fun `ellipse fill follows a live drag and cancellation restores it without committing`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = FloatingTranslationView(controller.get())
            controller.get().setContentView(view)
            view.layout(0, 0, 500, 600)
            val bubble = manualBubble(RectF(100f, 100f, 300f, 300f))
            view.setTranslations(page(bubble))
            view.setDisplayRect(RectF(0f, 0f, 800f, 1000f))
            val settings = SettingsStore(controller.get()).loadNormalBubbleRenderSettings()
            view.setNormalBubbleRenderSettings(settings.copy(
                opacityPercent = 100, freeBubbleOpacityPercent = 100,
                autoAdaptBubbleColor = false, autoAdaptFreeBubbleColor = false
            ))
            view.setEditMode(true)
            view.enterResizeMode(1)
            var commits = 0
            view.onBubbleResized = { _, _ -> commits++ }
            fun draw(): Bitmap = Bitmap.createBitmap(500, 600, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
            draw().let {
                assertEquals(Color.WHITE, it.getPixel(200, 200))
                assertEquals(Color.TRANSPARENT, it.getPixel(115, 115))
                it.recycle()
            }
            touch(view, MotionEvent.ACTION_DOWN, 300f, 200f)
            touch(view, MotionEvent.ACTION_MOVE, 400f, 200f)
            draw().let {
                assertEquals(Color.WHITE, it.getPixel(350, 200))
                it.recycle()
            }
            touch(view, MotionEvent.ACTION_CANCEL, 400f, 200f)
            draw().let {
                assertEquals(Color.TRANSPARENT, it.getPixel(350, 200))
                it.recycle()
            }
            assertEquals(0, commits)
            assertEquals(RectF(100f, 100f, 300f, 300f), bubble.rect)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun manualBubble(rect: RectF) = BubbleTranslation.pending(
        1, rect, source = BubbleSource.MANUAL,
        maskContour = ManualBubbleShape.ELLIPSE.contour(rect, 800, 1000)
    )

    private fun page(bubble: BubbleTranslation) = TranslationResult("page.jpg", 800, 1000, listOf(bubble))

    private fun touch(view: FloatingTranslationView, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0L, 100L, action, x, y, 0).let {
            assertTrue(view.onTouchEvent(it))
            it.recycle()
        }
    }
}
