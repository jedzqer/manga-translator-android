package com.manga.translate

import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.translation.VlPageLayout
import com.manga.translate.translation.chunks
import com.manga.translate.translation.withPageAnnotation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageAnnotationPreparationTest {
    @Test
    fun `annotation is released after success failure and cancellation`() = runBlocking {
        val layout = VlPageLayout(100, 100,
            listOf(BubbleTranslation.pending(0, RectF(10f, 10f, 80f, 80f), "")))
        for (failure in listOf(null, IllegalStateException("request failed"), CancellationException("cancelled"))) {
            val crop = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val source = object : BitmapCropSource {
                override val width = 100
                override val height = 100
                override suspend fun decodeRegion(rect: RectF, maxEdge: Int) = crop
                override fun close() = Unit
            }
            var annotation: Bitmap? = null
            try {
                val result = withPageAnnotation(source, layout, layout.chunks().single(),
                    decodeFailure = { IllegalStateException("decode failed") }
                ) { image ->
                    annotation = image
                    assertTrue(crop.isRecycled)
                    assertFalse(image.isRecycled)
                    if (failure != null) throw failure
                    "recognized"
                }
                assertNull(failure)
                assertEquals("recognized", result)
            } catch (error: Exception) {
                assertSame(failure, error)
            }
            assertTrue(crop.isRecycled)
            assertNotNull(annotation)
            assertTrue(annotation!!.isRecycled)
        }
    }
}
