package com.manga.translate

import android.graphics.Bitmap
import com.manga.translate.reader.DecodedReadingBitmap
import com.manga.translate.reader.ReadingBitmapCache
import com.manga.translate.reader.ReadingTiledBitmapDrawable
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingBitmapCacheTest {

    @Test
    fun `same page is decoded once and shared between leases`() = runBlocking {
        val cache = ReadingBitmapCache(maxSizeKb = 1024)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        var decodeCount = 0

        val first = cache.acquire(File("page.webp")) {
            decodeCount += 1
            decoded(bitmap)
        }
        val second = cache.acquire(File("page.webp")) {
            decodeCount += 1
            error("cache miss")
        }

        assertEquals(1, decodeCount)
        assertSame(first?.decoded?.bitmap, second?.decoded?.bitmap)
        assertFalse(bitmap.isRecycled)

        first?.close()
        assertFalse(bitmap.isRecycled)
        second?.close()
        cache.clear()
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun `clearing cache never recycles a bitmap retained by hardware rendering`() = runBlocking {
        val cache = ReadingBitmapCache(maxSizeKb = 1024)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val lease = cache.acquire(File("page.jpg")) { decoded(bitmap) }

        cache.clear()
        assertFalse(bitmap.isRecycled)

        lease?.close()
        assertFalse(bitmap.isRecycled)
    }

    private fun decoded(bitmap: Bitmap): DecodedReadingBitmap {
        return DecodedReadingBitmap(
            drawable = ReadingTiledBitmapDrawable.single(bitmap),
            bitmap = bitmap,
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            displayWidth = bitmap.width,
            displayHeight = bitmap.height,
            isTiled = false
        )
    }
}
