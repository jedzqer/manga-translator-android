package com.manga.translate

import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.platform.DeviceResourcePolicy
import com.manga.translate.platform.PdfImageCodec
import com.manga.translate.platform.PdfImportMemoryEstimator
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfImportMemoryEstimatorTest {
    @Before
    fun initializePdfBox() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `source images and soft masks are counted per strip and repeated draws share the cache`() = runBlocking {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(200f, 2000f))
            document.addPage(page)
            val bitmap = createBitmap(400, 600)
            val image = try { LosslessFactory.createFromImage(document, bitmap) } finally { bitmap.recycle() }
            PDPageContentStream(document, page).use { stream ->
                // Same source in both strips and twice in the first strip.
                stream.drawImage(image, 0f, 1100f, 200f, 600f)
                stream.drawImage(image, 0f, 1300f, 200f, 600f)
                stream.drawImage(image, 0f, 100f, 200f, 600f)
            }
            val size = PdfImageCodec.ImportPageSize(200, 2000, 400_000L, 1f, 1000)
            // The transparent source has a full-sized soft mask, also budgeted as RGBA.
            val source = DeviceResourcePolicy.estimateBitmapBytes(400, 600) * 2
            assertEquals(PdfImportMemoryEstimator.renderBytes(size, source * 2, 123),
                PdfImportMemoryEstimator.estimatePage(page, size, 123))
        }
    }

    @Test
    fun `rotation and transformed images use displayed strip coordinates`() = runBlocking {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(2000f, 200f)).apply { rotation = 90 }
            document.addPage(page)
            // No pixel payload: estimating geometry must not attempt image decoding.
            val first = metadataImage(document)
            val second = metadataImage(document)
            PDPageContentStream(document, page).use { stream ->
                stream.saveGraphicsState()
                stream.transform(Matrix.getTranslateInstance(100f, 0f))
                stream.drawImage(first, 0f, 0f, 600f, 200f)
                stream.restoreGraphicsState()
                stream.drawImage(second, 1100f, 0f, 600f, 200f)
            }
            val size = PdfImageCodec.ImportPageSize(200, 2000, 400_000L, 1f, 1000)
            val source = DeviceResourcePolicy.estimateBitmapBytes(400, 600)
            assertEquals(PdfImportMemoryEstimator.renderBytes(size, source * 2),
                PdfImportMemoryEstimator.estimatePage(page, size, 0))
        }
    }

    @Test
    fun `working memory includes encoding overhead and saturates instead of overflowing`() {
        val size = PdfImageCodec.ImportPageSize(100, 100, 10_000L, 1f)
        assertEquals(64L * 1024 * 1024 + 80_000, PdfImportMemoryEstimator.renderBytes(size))
        assertEquals(Long.MAX_VALUE, PdfImportMemoryEstimator.renderBytes(size, Long.MAX_VALUE, Long.MAX_VALUE))
    }

    private fun metadataImage(document: PDDocument) = PDImageXObject(document).apply {
        width = 400
        height = 600
        bitsPerComponent = 8
        colorSpace = PDDeviceRGB.INSTANCE
    }
}
