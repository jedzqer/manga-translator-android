package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.os.LocaleList
import android.text.Layout
import android.text.TextPaint
import com.manga.translate.rendering.BubbleTextLayoutCache
import com.manga.translate.rendering.BubbleTextLayoutPreparer
import com.manga.translate.rendering.BubbleTextScaling
import com.manga.translate.rendering.VerticalTextRenderer
import com.manga.translate.rendering.VerticalTextLayoutCalculator
import com.manga.translate.rendering.VerticalTextSymbolConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleTextLayoutCacheTest {
    @Test
    fun repeatedDrawsReuseLayoutsAndRefreshColorInBothDirections() {
        val cache = BubbleTextLayoutCache()
        val paint = TextPaint().apply { color = Color.RED }
        val horizontal = cache.horizontal("Hello world", 120, 80, 100, paint, Layout.Alignment.ALIGN_CENTER)
        val vertical = cache.vertical("日本語", 80, 120, 100, paint)

        paint.color = Color.BLUE
        assertSame(horizontal, cache.horizontal("Hello world", 120, 80, 100, paint, Layout.Alignment.ALIGN_CENTER))
        assertSame(vertical, cache.vertical("日本語", 80, 120, 100, paint))
        assertEquals(Color.BLUE, horizontal.paint.color)
        assertEquals(Color.BLUE, vertical.paint.color)
        assertSame(horizontal.paint, horizontal.layout.paint)
    }

    @Test
    fun differentBubbleLayoutsCannotMutateEachOthersPaint() {
        val cache = BubbleTextLayoutCache()
        val paint = TextPaint().apply { textSize = 17f }
        val first = cache.horizontal("Hello world", 120, 80, 100, paint, Layout.Alignment.ALIGN_CENTER)
        val firstSize = first.paint.textSize
        val second = cache.horizontal("Other bubble", 400, 300, 100, paint, Layout.Alignment.ALIGN_CENTER)
        val vertical = cache.vertical("日本語", 80, 120, 50, paint)

        paint.textSize = 99f
        paint.typeface = Typeface.MONOSPACE
        assertNotSame(paint, first.paint)
        assertNotSame(first.paint, second.paint)
        assertNotSame(first.paint, vertical.paint)
        assertEquals(firstSize, first.layout.paint.textSize, 0f)
        assertTrue(first.paint.typeface != Typeface.MONOSPACE)
    }

    @Test
    fun geometryTextFillAlignmentAndFontChangesSelectNewLayouts() {
        val cache = BubbleTextLayoutCache()
        val paint = TextPaint()
        fun layout(text: String = "Hello world", width: Int = 120, height: Int = 80,
                   fill: Int = 100, alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER) =
            cache.horizontal(text, width, height, fill, paint, alignment)
        val original = layout()
        assertNotSame(original, layout(text = "Updated text"))
        assertNotSame(original, layout(width = 121))
        assertNotSame(original, layout(height = 81))
        val smaller = layout(fill = 50)
        assertEquals(original.paint.textSize / 2f, smaller.paint.textSize, 0.01f)
        val normal = layout(alignment = Layout.Alignment.ALIGN_NORMAL)
        assertNotSame(original, normal)
        assertEquals(Layout.Alignment.ALIGN_CENTER, original.layout.alignment)
        assertEquals(Layout.Alignment.ALIGN_NORMAL, normal.layout.alignment)
        assertNotSame(original, cache.horizontal("Hello world", 120, 80, 100, paint,
            Layout.Alignment.ALIGN_CENTER, lineSpacing = 3f))

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val bold = layout()
        assertNotSame(original, bold)
        assertEquals(paint.typeface, bold.paint.typeface)
        paint.textScaleX = 1.2f
        val scaled = layout()
        assertNotSame(bold, scaled)
        paint.textLocales = LocaleList(Locale.JAPANESE)
        assertNotSame(scaled, layout())
    }

    @Test
    fun verticalLayoutTracksTextGeometryFillAndTypeface() {
        val cache = BubbleTextLayoutCache()
        val paint = TextPaint()
        val original = cache.vertical("日本語", 80, 120, 100, paint)
        assertNotSame(original, cache.vertical("別の文字", 80, 120, 100, paint))
        assertNotSame(original, cache.vertical("日本語", 81, 120, 100, paint))
        assertNotSame(original, cache.vertical("日本語", 80, 121, 100, paint))
        val smaller = cache.vertical("日本語", 80, 120, 50, paint)
        assertEquals(original.paint.textSize / 2f, smaller.paint.textSize, 0.01f)
        assertTrue(smaller.layout.fits)
        paint.typeface = Typeface.MONOSPACE
        assertNotSame(original, cache.vertical("日本語", 80, 120, 100, paint))
    }

    @Test
    fun cachePreparesSourceLineBreaksAndVerticalSymbols() {
        val cache = BubbleTextLayoutCache()
        val paint = TextPaint()
        val raw = " 你好\n世界（！）\n\n第二段 "
        val prepared = BubbleTextScaling.prepareTextForLayout(raw)
        val horizontal = cache.horizontal(raw, 160, 240, 100, paint, Layout.Alignment.ALIGN_CENTER)
        val vertical = cache.vertical(raw, 160, 240, 100, paint)

        assertEquals("你好世界（！）\n\n第二段", horizontal.layout.text.toString())
        assertEquals(VerticalTextSymbolConverter.convert(prepared).replace("\n", ""),
            vertical.layout.glyphs.joinToString("") { it.text })
        assertTrue(BubbleTextScaling.layoutFits(horizontal.layout, 160, 240))
        assertTrue(vertical.layout.fits)
    }

    @Test
    fun cachedAndExportLayoutsPreserveMinusAndLongVowelDistinction() {
        val cache = BubbleTextLayoutCache()
        val paint = TextPaint()
        for (text in listOf("ラ－12", "ラー12", "価格は－12円。")) {
            for (width in listOf(40, 80, 160)) {
                val cached = cache.vertical(text, width, 100, 100, paint)
                val exported = BubbleTextLayoutPreparer.vertical(text, width, 100, 100, paint)
                val expected = VerticalTextLayoutCalculator.build(
                    TextPaint(), text, width, 100, cached.paint.textSize
                )
                assertEquals("text=$text width=$width", expected.glyphs, cached.layout.glyphs)
                assertEquals(expected.glyphs, exported.layout.glyphs)
                assertTrue(cached.layout.fits)
                assertSame(cached, cache.vertical(text, width, 100, 100, paint))
            }
        }
    }

    @Test
    fun leastRecentlyUsedEntriesAreEvictedAndClearReleasesAllLayouts() {
        val cache = BubbleTextLayoutCache(maxEntries = 2)
        val paint = TextPaint()
        fun horizontal(text: String) = cache.horizontal(text, 120, 80, 100, paint, Layout.Alignment.ALIGN_CENTER)
        val first = horizontal("first")
        val second = horizontal("second")
        assertSame(first, horizontal("first"))
        cache.vertical("第三", 80, 120, 100, paint)
        assertEquals(2, cache.size)
        assertSame(first, horizontal("first"))
        assertNotSame(second, horizontal("second"))
        assertEquals(2, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
        assertNotSame(first, horizontal("first"))
    }

    @Test
    fun cachedDrawsMatchFreshExportPreparationAfterOtherBubblesAndColorChanges() {
        val cache = BubbleTextLayoutCache()
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val text = "A long translation\nwith punctuation (!).\n\nSecond paragraph."
        val width = 95
        val height = 240
        val horizontal = cache.horizontal(text, width, height, 80, paint, Layout.Alignment.ALIGN_CENTER)
        val vertical = cache.vertical(text, width, height, 80, paint)
        cache.horizontal("Another bubble", 400, 100, 100, paint, Layout.Alignment.ALIGN_NORMAL)
        cache.vertical("別の気泡", 180, 80, 50, paint)
        paint.color = Color.BLUE
        paint.textSize = 100f

        fun bitmap(draw: (Canvas) -> Unit): Bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
        val cachedHorizontal = cache.horizontal(text, width, height, 80, paint, Layout.Alignment.ALIGN_CENTER)
        val freshHorizontal = BubbleTextLayoutPreparer.horizontal(text, width, height, 80, paint,
            Layout.Alignment.ALIGN_CENTER)
        val cachedVertical = cache.vertical(text, width, height, 80, paint)
        val freshVertical = BubbleTextLayoutPreparer.vertical(text, width, height, 80, paint)
        assertSame(horizontal, cachedHorizontal)
        assertSame(vertical, cachedVertical)
        assertTrue(bitmap { cachedHorizontal.layout.draw(it) }.sameAs(bitmap { freshHorizontal.layout.draw(it) }))
        for (startFromTop in listOf(false, true)) {
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            assertTrue(bitmap {
                VerticalTextRenderer.draw(it, rect, cachedVertical.paint,
                    cachedVertical.layout, startFromTop)
            }.sameAs(bitmap {
                VerticalTextRenderer.draw(it, rect, freshVertical.paint,
                    freshVertical.layout, startFromTop)
            }))
        }
        assertEquals(100f, paint.textSize, 0f)
    }
}
