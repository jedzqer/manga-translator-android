package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.reader.ReadingBitmapDecoder
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingBitmapDecoderTest {
    @Test
    fun `whole image fallback respects texture and pixel limits`() {
        val longImageSample = ReadingBitmapDecoder.calculateInSampleSize(1080, 24000, 960, 1440)
        assertEquals(4, longImageSample)
        assertTrue(24000 / longImageSample <= 8192)
        assertEquals(4, ReadingBitmapDecoder.calculateInSampleSize(12000, 6000, 2160, 4800))
        assertEquals(2, ReadingBitmapDecoder.calculateInSampleSize(8193, 100, 2160, 4800))
        assertEquals(1, ReadingBitmapDecoder.calculateInSampleSize(1600, 2400, 2160, 4800))
    }

    @Test
    fun `long image returns drawable pixels through the bottom of the page`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("reading-long-", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(800, 24000, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.RED)
            Canvas(source).drawRect(0f, 12000f, 100f, 24000f,
                android.graphics.Paint().apply { color = Color.BLUE })
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
            val decoded = requireNotNull(ReadingBitmapDecoder.decode(file, 320, 480))
            try {
                assertEquals(800, decoded.sourceWidth)
                assertEquals(24000, decoded.sourceHeight)
                if (decoded.isTiled) {
                    assertNull(decoded.bitmap)
                    assertTrue(decoded.displayHeight > 8192)
                } else {
                    assertNotNull(decoded.bitmap)
                    assertTrue(decoded.displayHeight <= 8192)
                }
                val output = Bitmap.createBitmap(800, 240, Bitmap.Config.ARGB_8888)
                try {
                    decoded.drawable.setBounds(0, 0, 800, 240)
                    var loaded = false
                    repeat(if (decoded.isTiled) 100 else 1) {
                        output.eraseColor(Color.TRANSPARENT)
                        decoded.drawable.draw(Canvas(output))
                        if (output.getPixel(400, 10) == Color.RED) loaded = true
                        if (!loaded) Thread.sleep(20)
                    }
                    assertTrue(loaded)
                } finally {
                    output.recycle()
                }
            } finally {
                decoded.drawable.close()
                decoded.bitmap?.recycle()
            }
        } finally {
            if (!source.isRecycled) source.recycle()
            file.delete()
        }
    }

    @Test
    fun `both reader layouts use ordinary image views`() {
        assertReaderImageViews()
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-night")
    fun `both reader layouts use ordinary image views in dark theme`() {
        assertReaderImageViews()
    }

    private fun assertReaderImageViews() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_MangaTranslator)
        val inflater = LayoutInflater.from(context)
        for ((layout, id) in listOf(
            R.layout.fragment_reading to R.id.reading_image,
            R.layout.item_reading_webtoon_page to R.id.reading_page_image
        )) {
            assertTrue(inflater.inflate(layout, null).findViewById<View>(id) is ImageView)
        }
    }
}
