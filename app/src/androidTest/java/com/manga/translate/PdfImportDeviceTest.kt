package com.manga.translate

import android.graphics.BitmapFactory
import android.graphics.Color
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
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class PdfImportDeviceTest {
    /** Opt-in regression for an externally staged PDF and reference PNG hashes. */
    @Test
    fun stagedPdfImportMatchesReference() = runBlocking {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("stagedPdfImport") == "true"
        )
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".qa"))
        val pdf = File(context.cacheDir, "pdf-memory-input.pdf")
        val reference = File(context.cacheDir, "pdf-memory-reference.sha256")
        check(pdf.isFile && reference.isFile)
        val expected = reference.readLines().filter { it.isNotBlank() }.associate { line ->
            val (hash, name) = line.trim().split(Regex("\\s+"), limit = 2)
            name to hash
        }
        assertTrue(expected.isNotEmpty())
        val output = File(context.cacheDir, "pdf-import-regression-output")
        check(!output.exists()) { "Previous regression output must be removed explicitly" }
        check(output.mkdir())
        try {
            val plan = PdfImageCodec.estimateImportPlan(context, context.contentResolver, Uri.fromFile(pdf))
            assertNotNull(plan)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putLong("estimatedImportWorkingBytes", plan!!.peakBytes)
            })
            val count = PdfImageCodec.renderPdfToImages(
                context, context.contentResolver, Uri.fromFile(pdf), output, plan
            )
            assertEquals(expected.size, count)
            assertEquals(expected.keys, output.listFiles()!!.map { it.name }.toSet())
            for ((name, hash) in expected) {
                val digest = MessageDigest.getInstance("SHA-256")
                File(output, name).inputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val size = stream.read(buffer)
                        if (size < 0) break
                        digest.update(buffer, 0, size)
                    }
                }
                assertEquals(name, hash, digest.digest().joinToString("") { "%02x".format(it) })
            }
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun stripRenderingMatchesFullPageAndPreservesFollowingScan() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".qa"))
        PDFBoxResourceLoader.init(context)
        val pdf = File(context.cacheDir, "strip-render.pdf")
        val output = File(context.cacheDir, "strip-render-output").apply { mkdirs() }
        val jpeg = createJpeg(120, 180)
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(30f, 4200f))
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    for (y in 0 until 4200 step 20) {
                        stream.setNonStrokingColor(if (y % 40 == 0) 1f else 0f, 0f, if (y % 40 == 0) 0f else 1f)
                        stream.addRect(0f, y.toFloat(), 30f, 20f)
                        stream.fill()
                    }
                }
                val scan = PDPage(PDRectangle(60f, 90f))
                document.addPage(scan)
                PDPageContentStream(document, scan).use { stream ->
                    stream.drawImage(JPEGFactory.createFromStream(document, ByteArrayInputStream(jpeg)), 0f, 0f, 60f, 90f)
                }
                document.save(pdf)
            }
            val plan = PdfImageCodec.planImportPage(30, 4200)
            val count = PdfImageCodec.renderPdfToImages(context, context.contentResolver, Uri.fromFile(pdf), output)
            assertEquals(plan.strips().count() + 1, count)
            assertArrayEquals(jpeg, File(output, "0002.jpg").readBytes())
            android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val full = createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
                        try {
                            full.eraseColor(Color.WHITE)
                            page.render(full, null, plan.renderMatrix(0), android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            for (strip in plan.strips()) {
                                val actual = BitmapFactory.decodeFile(File(output, plan.fileName(0, strip.index)).path)!!
                                try {
                                    assertEquals(strip.height, actual.height)
                                    assertEquals(plan.width, actual.width)
                                    for (y in 0 until strip.height) {
                                        assertEquals("row ${strip.top + y}", full.getPixel(plan.width / 2, strip.top + y), actual.getPixel(plan.width / 2, y))
                                    }
                                } finally { actual.recycle() }
                            }
                        } finally { full.recycle() }
                    }
                }
            }
        } finally {
            pdf.delete()
            output.deleteRecursively()
        }
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
