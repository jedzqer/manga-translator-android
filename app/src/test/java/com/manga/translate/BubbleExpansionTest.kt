package com.manga.translate

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.rendering.BubbleShapePaths
import com.manga.translate.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleExpansionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `defaults inset normal bubbles and expand free bubbles by ten percent`() {
        val settings = SettingsStore(context).loadNormalBubbleRenderSettings()
        assertEquals(10, settings.shrinkPercent)
        assertEquals(10, settings.freeBubbleSizeAdjustPercent)
    }

    @Test
    fun `old outward settings are not interpreted as an ordinary bubble inset`() {
        prefs.edit().putInt("normal_bubble_shrink_percent", 15)
            .putInt("normal_bubble_vertical_expansion_percent", 25)
            .putInt("normal_free_bubble_shrink_percent_v2", 7)
            .putInt("normal_free_bubble_vertical_expansion_percent", 18).commit()
        val settings = SettingsStore(context).loadNormalBubbleRenderSettings()
        assertEquals(10, settings.shrinkPercent)
        assertEquals(7, settings.freeBubbleSizeAdjustPercent)
    }

    @Test
    fun `signed free adjustment persists and settings clamp their own ranges`() {
        val store = SettingsStore(context)
        store.saveNormalBubbleRenderSettings(store.loadNormalBubbleRenderSettings().copy(
            shrinkPercent = 5, freeBubbleSizeAdjustPercent = -12))
        assertEquals(5, store.loadNormalBubbleRenderSettings().shrinkPercent)
        assertEquals(-12, store.loadNormalBubbleRenderSettings().freeBubbleSizeAdjustPercent)
        store.saveNormalBubbleRenderSettings(store.loadNormalBubbleRenderSettings().copy(
            shrinkPercent = -10, freeBubbleSizeAdjustPercent = 100))
        assertEquals(0, store.loadNormalBubbleRenderSettings().shrinkPercent)
        assertEquals(30, store.loadNormalBubbleRenderSettings().freeBubbleSizeAdjustPercent)
        store.saveNormalBubbleRenderSettings(store.loadNormalBubbleRenderSettings().copy(
            shrinkPercent = 100, freeBubbleSizeAdjustPercent = -100))
        assertEquals(30, store.loadNormalBubbleRenderSettings().shrinkPercent)
        assertEquals(-30, store.loadNormalBubbleRenderSettings().freeBubbleSizeAdjustPercent)
    }

    @Test
    fun `mask follows page coordinates with offsets scaling and uniform inset`() {
        val path = Path()
        val bubble = BubbleTranslation(0, RectF(0f, 0f, 200f, 400f),
            maskContour = floatArrayOf(0.5f, 0.25f, 0.75f, 0.5f, 0.5f, 0.75f, 0.25f, 0.5f))
        BubbleShapePaths.buildPath(path, bubble, 200, 400, 10f, 20f, 2f, 0.5f,
            offsetX = 5f, offsetY = 10f, shrinkPercent = 10)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        assertEquals(130f, bounds.left, 0.01f)
        assertEquals(80f, bounds.top, 0.01f)
        assertEquals(310f, bounds.right, 0.01f)
        assertEquals(170f, bounds.bottom, 0.01f)
        val region = Region().apply { setPath(path, Region(0, 0, 500, 500)) }
        assertTrue(region.contains(220, 125))
        assertFalse(region.contains(140, 90))
    }

    @Test
    fun `missing or malformed masks fall back to the detection rectangle`() {
        for (contour in listOf(null, floatArrayOf(0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f),
            floatArrayOf(Float.NaN, 0f, 1f, 0f, 1f, 1f))) {
            val path = Path()
            BubbleShapePaths.buildPath(path,
                BubbleTranslation(0, RectF(50f, 50f, 150f, 250f), maskContour = contour),
                300, 400, 0f, 0f, 1f, 1f)
            val bounds = RectF()
            path.computeBounds(bounds, true)
            assertEquals(RectF(50f, 50f, 150f, 250f), bounds)
        }
    }

    @Test
    fun `inset and expansion scale both axes around the same center`() {
        for (percent in listOf(-10, 0, 20)) {
            val path = Path()
            BubbleShapePaths.buildPath(
                path, BubbleTranslation(0, RectF(50f, 50f, 150f, 250f)),
                300, 400, 0f, 0f, 1f, 1f, shrinkPercent = percent)
            val bounds = RectF()
            path.computeBounds(bounds, true)
            assertEquals(100f - percent, bounds.width(), 0.01f)
            assertEquals(200f - 2 * percent, bounds.height(), 0.01f)
            assertEquals(100f, bounds.centerX(), 0.01f)
            assertEquals(150f, bounds.centerY(), 0.01f)
        }
    }
}
