package com.manga.translate

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.platform.PdfImageCodec
import com.manga.translate.platform.PdfOriginalImageExtractor
import com.manga.translate.platform.resolvePdfImportRenderScale
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfImageCodecTest {
    @Before
    fun initializePdfBox() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `standard PDF page is rendered at 300 DPI`() {
        val scale = resolvePdfImportRenderScale(pageWidth = 595, pageHeight = 842)

        assertEquals(300f / 72f, scale, 0.001f)
        assertEquals(3508, (842 * scale).toInt())
    }

    @Test
    fun `large image-sized PDF page is never downscaled`() {
        val scale = resolvePdfImportRenderScale(pageWidth = 4_000, pageHeight = 8_000)

        assertEquals(2f, scale, 0f)
    }

    @Test
    fun `intermediate PDF page reaches high resolution without excessive upscaling`() {
        val scale = resolvePdfImportRenderScale(pageWidth = 1_200, pageHeight = 1_800)

        assertEquals(2f, scale, 0f)
        assertTrue(scale in 2f..(300f / 72f))
    }

    @Test
    fun `full page scan exposes original image pixels independent of PDF page size`() {
        val jpeg = createJpeg(width = 1200, height = 1800)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(60f, 90f))
            document.addPage(page)
            val image = JPEGFactory.createFromStream(document, ByteArrayInputStream(jpeg))
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(image, 0f, 0f, 60f, 90f)
            }

            val extracted = PdfOriginalImageExtractor.findFullPageImage(page)

            assertNotNull(extracted)
            assertEquals(1200, extracted!!.width)
            assertEquals(1800, extracted.height)
        }
    }

    @Test
    fun `page with extra drawing is not treated as lossless single image page`() {
        val jpeg = createJpeg(width = 120, height = 180)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(60f, 90f))
            document.addPage(page)
            val image = JPEGFactory.createFromStream(document, ByteArrayInputStream(jpeg))
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(image, 0f, 0f, 60f, 90f)
                stream.addRect(2f, 2f, 10f, 10f)
                stream.stroke()
            }

            assertNull(PdfOriginalImageExtractor.findFullPageImage(page))
        }
    }

    @Test
    fun `jpeg scan is copied byte for byte without fixed resolution rendering`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val jpeg = createJpeg(width = 1200, height = 1800)
        val pdfFile = File(context.cacheDir, "lossless-scan-test.pdf")
        val outputDir = File(context.cacheDir, "lossless-scan-output").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(60f, 90f))
                document.addPage(page)
                val image = JPEGFactory.createFromStream(document, ByteArrayInputStream(jpeg))
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(image, 0f, 0f, 60f, 90f)
                }
                document.save(pdfFile)
            }

            val extractor = PdfOriginalImageExtractor.open(
                context = context,
                contentResolver = context.contentResolver,
                uri = Uri.fromFile(pdfFile)
            )

            assertNotNull(extractor)
            extractor!!.use {
                assertTrue(it.extractPage(0, outputDir))
            }
            assertArrayEquals(jpeg, File(outputDir, "0001.jpg").readBytes())
        } finally {
            pdfFile.delete()
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun `long PDF retains horizontal detail with bounded contiguous strips`() {
        val page = PdfImageCodec.planImportPage(281, 93473)
        assertEquals(1171, page.width)
        assertEquals(300f / 72f, page.renderScale, 0.001f)
        val strips = page.strips().toList()
        assertEquals(0, strips.first().top)
        assertEquals(page.height, strips.sumOf { it.height })
        strips.zipWithNext().forEach { (first, next) ->
            assertEquals(first.top + first.height, next.top)
        }
        assertTrue(strips.all { it.height <= 4096 && it.height.toLong() * page.width <= 8_388_608 })
        assertTrue(PdfImageCodec.PdfImportPlan(listOf(page)).peakBytes < 20L * 1024 * 1024)
        val names = strips.map { page.fileName(0, it.index) } + "0002.jpg"
        assertEquals(names, names.sorted())
        assertEquals(names.size, names.toSet().size)
    }

    private fun createJpeg(width: Int, height: Int): ByteArray {
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(0xFFF5F5F5.toInt())
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
