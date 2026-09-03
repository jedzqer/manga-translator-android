package com.manga.translate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.platform.DETECTION_MAX_EDGE
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.recycleSafely
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PipelineBitmapDecoderTest {
    @Test
    fun `readImageSize caches by fingerprint and re-reads replaced files`() {
        val file = File.createTempFile("size-cache", ".png")
        try {
            writePng(file, 40, 24)
            assertEquals(40, PipelineBitmapDecoder.readImageSize(file)?.width)
            // Second read is served from cache and must agree with the first.
            assertEquals(40, PipelineBitmapDecoder.readImageSize(file)?.width)

            // Replacing the image changes length and mtime, so the cache must miss.
            writePng(file, 80, 24)
            file.setLastModified(file.lastModified() + 5_000L)
            val refreshed = PipelineBitmapDecoder.readImageSize(file)
            assertEquals(80, refreshed?.width)
            assertEquals(24, refreshed?.height)
        } finally {
            file.delete()
        }
    }

    private fun writePng(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `in-memory full-page crop owns its bitmap`() = runBlocking {
        val mutableSource = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val source = requireNotNull(mutableSource.copy(Bitmap.Config.ARGB_8888, false))
        mutableSource.recycle()

        val decoded = PipelineBitmapDecoder.openCropSource(source).use { cropSource ->
            cropSource.decodeRegion(
                RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                maxEdge = DETECTION_MAX_EDGE
            )
        }

        assertNotNull(decoded)
        assertNotSame(source, decoded)
        decoded.recycleSafely()
        assertFalse(source.isRecycled)
        source.recycleSafely()
    }

    @Test
    fun `crop source scales decoded bitmap to exact max edge`() = runBlocking {
        val source = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        try {
            val decoded = requireNotNull(
                PipelineBitmapDecoder.openCropSource(source).use { cropSource ->
                    cropSource.decodeRegion(
                        RectF(0f, 0f, cropSource.width.toFloat(), cropSource.height.toFloat()),
                        maxEdge = 300
                    )
                }
            )

            assertEquals(300, maxOf(decoded.width, decoded.height))
            decoded.recycleSafely()
            assertFalse(source.isRecycled)
        } finally {
            source.recycleSafely()
        }
    }
}
