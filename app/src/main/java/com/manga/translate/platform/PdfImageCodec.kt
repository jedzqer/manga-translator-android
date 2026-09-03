package com.manga.translate.platform

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.manga.translate.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

private const val PDF_POINTS_PER_INCH = 72f
private const val PDF_IMPORT_DPI = 300f
private const val PDF_IMPORT_TARGET_LONG_EDGE = 3508f
private const val PDF_IMPORT_MIN_RENDER_SCALE = 2f

/**
 * PdfRenderer exposes page dimensions in PDF points (72 points per inch). Keep a
 * normal page close to 300 DPI, without dropping below the importer's established 2x
 * minimum when a PDF uses large, image-sized page coordinates.
 */
internal fun resolvePdfImportRenderScale(pageWidth: Int, pageHeight: Int): Float {
    require(pageWidth > 0 && pageHeight > 0)
    val longEdge = maxOf(pageWidth, pageHeight).toFloat()
    return (PDF_IMPORT_TARGET_LONG_EDGE / longEdge)
        .coerceIn(PDF_IMPORT_MIN_RENDER_SCALE, PDF_IMPORT_DPI / PDF_POINTS_PER_INCH)
}

internal object PdfImageCodec {
    private const val IMPORT_FILE_NAME_WIDTH = 4
    private const val MAX_IMPORT_PAGE_COUNT = 750
    private const val MINIMUM_FREE_SPACE_BYTES = 100L * 1024 * 1024
    private const val BYTES_PER_RENDERED_PIXEL = 4L

    // PDF export maps pixels 1:1 onto page points, so pages are encoded at full source
    // resolution. While a page is encoded, the bitmap is held together with its
    // Flate-encoded color buffer, the final toByteArray() copy and, when transparent,
    // an alpha buffer: up to roughly 4x the bitmap size. Require that much headroom
    // before a full decode; otherwise step inSampleSize up so memory-constrained
    // devices degrade page resolution instead of crashing with an OutOfMemoryError
    // (an Error, so the Exception handlers around PDF export do not catch it).
    private const val EXPORT_DECODE_BUDGET_COPIES = 4

    // The transparency probe only needs to know whether any pixel has alpha, which
    // survives downsampling for real-world masks, so cap the probe bitmap by long edge.
    private const val EXPORT_TRANSPARENCY_PROBE_MAX_EDGE = 2048
    private const val EXPORT_PROBE_BUDGET_COPIES = 2

    /**
     * Estimate the peak decode memory a PDF import would need, based on actual page
     * dimensions rather than the source file size (a small PDF can still contain huge
     * pages). Reused by the import memory warning so the estimate matches what
     * [renderPdfToImages] will actually allocate. Returns null if the PDF cannot be opened.
     */
    suspend fun estimateImportPlan(
        contentResolver: ContentResolver,
        uri: Uri
    ): PdfImportPlan? {
        val descriptor = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            return PdfImportPlan(emptyList(), Long.MAX_VALUE)
        } ?: return null
        return descriptor.use { pfd ->
            try {
                PdfRenderer(pfd).use { renderer ->
                    PdfImportPlan(inspectImportPages(renderer))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: OutOfMemoryError) {
                PdfImportPlan(emptyList(), Long.MAX_VALUE)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun estimateImportPeakBytes(
        contentResolver: ContentResolver,
        uri: Uri
    ): Long? = estimateImportPlan(contentResolver, uri)?.peakBytes

    suspend fun renderPdfToImages(
        context: Context,
        contentResolver: ContentResolver,
        uri: Uri,
        outputDir: File,
        importPlan: PdfImportPlan? = null
    ): Int {
        val originalImageExtractor = PdfOriginalImageExtractor.open(context, contentResolver, uri)
        return try {
            val descriptor = originalImageExtractor?.openRendererDescriptor()
                ?: contentResolver.openFileDescriptor(uri, "r")
                ?: throw ImportFileException(R.string.pdf_import_cannot_open)
            descriptor.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageSizes = importPlan
                        ?.takeIf { it.pageSizes.size == renderer.pageCount }
                        ?.pageSizes
                        ?: inspectImportPages(renderer)
                    ensureImportSpace(context, outputDir, pageSizes.sumOf { it.pixels })
                    var imported = 0
                    for (index in pageSizes.indices) {
                        currentCoroutineContext().ensureActive()
                        if (originalImageExtractor?.extractPage(index, outputDir) == true) {
                            imported += 1
                            continue
                        }
                        val page = renderer.openPage(index)
                        try {
                            val size = pageSizes[index]
                            val bitmap = createBitmap(
                                size.width,
                                size.height,
                                Bitmap.Config.ARGB_8888
                            )
                            try {
                                bitmap.eraseColor(0xFFFFFFFF.toInt())
                                val matrix = Matrix().apply {
                                    setScale(size.renderScale, size.renderScale)
                                }
                                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val fileName = "${(index + 1).toString().padStart(IMPORT_FILE_NAME_WIDTH, '0')}.png"
                                val destination = File(outputDir, fileName)
                                val temporary = File(outputDir, ".$fileName.tmp")
                                try {
                                    FileOutputStream(temporary).use { output ->
                                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                            throw IllegalStateException("Failed to encode PDF page ${index + 1}")
                                        }
                                    }
                                    currentCoroutineContext().ensureActive()
                                    if (!temporary.renameTo(destination)) {
                                        throw IllegalStateException("Failed to commit PDF page ${index + 1}")
                                    }
                                } finally {
                                    temporary.delete()
                                }
                                imported += 1
                            } finally {
                                bitmap.recycle()
                            }
                        } finally {
                            page.close()
                        }
                    }
                    imported
                }
            }
        } finally {
            originalImageExtractor?.close()
        }
    }

    private suspend fun inspectImportPages(renderer: PdfRenderer): List<ImportPageSize> {
        if (renderer.pageCount > MAX_IMPORT_PAGE_COUNT) {
            throw ImportFileException(R.string.pdf_import_too_many_pages)
        }
        val pages = ArrayList<ImportPageSize>(renderer.pageCount)
        for (index in 0 until renderer.pageCount) {
            currentCoroutineContext().ensureActive()
            val page = renderer.openPage(index)
            try {
                val scale = resolvePdfImportRenderScale(page.width, page.height)
                val width = scaledDimension(page.width, scale)
                val height = scaledDimension(page.height, scale)
                val pixels = width.toLong() * height.toLong()
                pages += ImportPageSize(width, height, pixels, scale)
            } finally {
                page.close()
            }
        }
        return pages
    }

    private fun scaledDimension(value: Int, scale: Float): Int {
        val scaled = value.toDouble() * scale
        if (scaled < 1.0 || scaled > Int.MAX_VALUE) {
            throw ImportFileException(R.string.pdf_import_invalid_page)
        }
        return scaled.roundToInt().coerceAtLeast(1)
    }

    private fun ensureImportSpace(context: Context, outputDir: File, totalPixels: Long) {
        if (totalPixels > Long.MAX_VALUE / BYTES_PER_RENDERED_PIXEL) {
            throw ImportFileException(R.string.pdf_import_output_too_large)
        }
        val estimatedOutput = totalPixels * BYTES_PER_RENDERED_PIXEL
        if (!StorageSpaceChecker.hasSpaceFor(
                context = context,
                directory = outputDir,
                requiredBytes = estimatedOutput,
                reserveBytes = MINIMUM_FREE_SPACE_BYTES
            )
        ) {
            throw ImportFileException(R.string.pdf_import_storage_insufficient)
        }
    }

    internal data class PdfImportPlan(
        val pageSizes: List<ImportPageSize>,
        private val peakBytesOverride: Long? = null
    ) {
        val reusable: Boolean
            get() = peakBytesOverride == null

        val peakBytes: Long
            get() = peakBytesOverride ?: pageSizes.maxOfOrNull { size ->
                DeviceResourcePolicy.estimateBitmapBytes(size.width, size.height)
            } ?: 0L
    }

    internal data class ImportPageSize(val width: Int, val height: Int, val pixels: Long, val renderScale: Float)

    private data class PdfImageInfo(
        val width: Int,
        val height: Int,
        val hasTransparency: Boolean
    )

    /** Dimensions of the bitmap the bytes were encoded from (may be downsampled). */
    private data class PdfImageData(
        val colorBytes: ByteArray,
        val alphaBytes: ByteArray?,
        val width: Int,
        val height: Int
    )

    fun writeImagesToPdf(
        images: List<File>,
        outputStream: OutputStream
    ): Boolean {
        if (images.isEmpty()) return false
        return try {
            streamPdfWithOutlines(emptyList(), images, outputStream)
            true
        } catch (e: Exception) {
            AppLogger.log("PdfImageCodec", "Write PDF failed", e)
            false
        }
    }

    fun writeImagesToPdfWithOutlines(
        chapterOutlines: List<Pair<String, Int>>,
        images: List<File>,
        outputStream: OutputStream
    ): Boolean {
        if (images.isEmpty()) return false
        return try {
            streamPdfWithOutlines(chapterOutlines, images, outputStream)
            true
        } catch (e: Exception) {
            AppLogger.log("PdfImageCodec", "Write PDF with outlines failed", e)
            false
        }
    }

    private fun streamPdfWithOutlines(
        chapterOutlines: List<Pair<String, Int>>,
        images: List<File>,
        out: OutputStream
    ) {
        val n = images.size
        val m = chapterOutlines.size

        val imageInfos = images.map(::inspectPdfImage)
        val alphaImageCount = imageInfos.count { it.hasTransparency }
        val maxObjectNumber = 3 + 3 * n + alphaImageCount + m
        val offsets = LongArray(maxObjectNumber + 1) { -1L }
        var byteCount = 0L

        val counter = object : OutputStream() {
            override fun write(b: Int) { out.write(b); byteCount++ }
            override fun write(b: ByteArray) { out.write(b); byteCount += b.size }
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                byteCount += len
            }
        }

        fun writeln(s: String) {
            val bytes = "$s\n".toByteArray()
            counter.write(bytes)
        }

        fun beginObj(objNum: Int) {
            offsets[objNum] = byteCount
            counter.write("$objNum 0 obj\n".toByteArray())
        }

        fun endObj() {
            counter.write("\nendobj\n".toByteArray())
        }

        fun indirectRef(objNum: Int): String = "$objNum 0 R"

        fun pdfString(s: String): String {
            val sb = StringBuilder("<FEFF")
            for (c in s) {
                val code = c.code
                if (code <= 0xFFFF) {
                    sb.append(String.format(Locale.ROOT, "%04X", code))
                } else {
                    val high = 0xD800 + ((code - 0x10000) shr 10)
                    val low = 0xDC00 + ((code - 0x10000) and 0x3FF)
                    sb.append(String.format(Locale.ROOT, "%04X%04X", high, low))
                }
            }
            sb.append(">")
            return sb.toString()
        }

        // Object layout (object numbers are deterministic):
        // 1: Catalog
        // 2: Pages root
        // 3: Outlines root
        // 4..3+n: Color image XObjects
        // 4+n..3+n+alphaImageCount: Optional alpha-mask XObjects
        // Remaining objects: content streams, page objects, then outline items.
        val imageObjBase = 4
        val alphaObjBase = imageObjBase + n
        val contentObjBase = alphaObjBase + alphaImageCount
        val pageObjBase = contentObjBase + n
        val outlineObjBase = pageObjBase + n

        writeln("%PDF-1.4")
        writeln("%âãÏÓ")

        // 1: Catalog
        beginObj(1)
        writeln("<< /Type /Catalog /Pages ${indirectRef(2)} /Outlines ${indirectRef(3)} >>")
        endObj()

        // 2: Pages root
        beginObj(2)
        val kids = (0 until n).joinToString(" ") { indirectRef(pageObjBase + it) }
        writeln("<< /Type /Pages /Kids [$kids] /Count $n >>")
        endObj()

        // 3: Outlines root
        beginObj(3)
        val firstOutlineRef = if (m > 0) "/First ${indirectRef(outlineObjBase)}" else ""
        val lastOutlineRef = if (m > 0) "/Last ${indirectRef(outlineObjBase + m - 1)}" else ""
        writeln("<< /Type /Outlines $firstOutlineRef $lastOutlineRef >>")
        endObj()

        // Pages — process one image at a time to keep memory low
        var nextAlphaObject = alphaObjBase
        for (i in 0 until n) {
            val imageFile = images[i]
            val imageInfo = imageInfos[i]
            val imageData = encodeImageForPdf(imageFile, imageInfo)
                ?: throw IllegalStateException("Cannot encode image for PDF: ${imageFile.name}")
            // Take dimensions from the actually encoded bitmap: the memory guard may
            // have downsampled the source, so the XObject and MediaBox must match it.
            val w = imageData.width
            val h = imageData.height
            val alphaObject = if (imageData.alphaBytes != null) nextAlphaObject++ else null

            // Image XObject
            beginObj(imageObjBase + i)
            val imageFilter = if (isJpeg(imageFile)) "/DCTDecode" else "/FlateDecode"
            val softMask = alphaObject?.let { " /SMask ${indirectRef(it)}" }.orEmpty()
            writeln("<< /Type /XObject /Subtype /Image /Width $w /Height $h " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter $imageFilter" +
                "$softMask /Length ${imageData.colorBytes.size} >>")
            writeln("stream")
            counter.write(imageData.colorBytes)
            writeln("")
            writeln("endstream")
            endObj()

            imageData.alphaBytes?.let { alphaBytes ->
                beginObj(alphaObject!!)
                writeln("<< /Type /XObject /Subtype /Image /Width $w /Height $h " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode " +
                    "/Length ${alphaBytes.size} >>")
                writeln("stream")
                counter.write(alphaBytes)
                writeln("")
                writeln("endstream")
                endObj()
            }

            // Content stream
            beginObj(contentObjBase + i)
            val content = "q $w 0 0 $h 0 0 cm /Im0 Do Q"
            val contentBytes = content.toByteArray()
            writeln("<< /Length ${contentBytes.size} >>")
            writeln("stream")
            counter.write(contentBytes)
            writeln("")
            writeln("endstream")
            endObj()

            // Page object
            beginObj(pageObjBase + i)
            writeln("<< /Type /Page /Parent ${indirectRef(2)} " +
                "/MediaBox [0 0 $w $h] " +
                "/Contents ${indirectRef(contentObjBase + i)} " +
                "/Resources << /XObject << /Im0 ${indirectRef(imageObjBase + i)} >> >> >>")
            endObj()
        }

        // Outline items
        for (i in 0 until m) {
            val (chapterName, firstPage) = chapterOutlines[i]
            val objNum = outlineObjBase + i
            val nextRef = if (i < m - 1) "/Next ${indirectRef(objNum + 1)}" else ""
            val prevRef = if (i > 0) "/Prev ${indirectRef(objNum - 1)}" else ""
            val destPage = firstPage.coerceAtMost(n - 1)

            beginObj(objNum)
            writeln("<< /Title ${pdfString(chapterName)} " +
                "/Parent ${indirectRef(3)} " +
                "/Dest [${indirectRef(pageObjBase + destPage)} /Fit] " +
                "$nextRef $prevRef >>")
            endObj()
        }

        val startxref = byteCount

        // Cross-reference table
        writeln("xref")
        writeln("0 ${maxObjectNumber + 1}")
        writeln("0000000000 65535 f ")
        for (objNum in 1..maxObjectNumber) {
            val offset = offsets[objNum]
            if (offset < 0) {
                throw IllegalStateException("Missing PDF object offset: $objNum")
            }
            writeln(String.format(Locale.ROOT, "%010d 00000 n ", offset))
        }

        // Trailer
        writeln("trailer")
        writeln("<< /Size ${maxObjectNumber + 1} /Root ${indirectRef(1)} >>")
        writeln("startxref")
        writeln("$startxref")
        counter.write("%%EOF".toByteArray())
    }

    private fun inspectPdfImage(imageFile: File): PdfImageInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            throw IllegalStateException("Cannot decode image: ${imageFile.name}")
        }
        if (isJpeg(imageFile)) {
            return PdfImageInfo(width, height, hasTransparency = false)
        }
        val bitmap = decodeTransparencyProbe(imageFile, width, height)
            ?: throw IllegalStateException("Cannot decode image: ${imageFile.name}")
        return try {
            PdfImageInfo(width, height, bitmapHasTransparency(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeImageForPdf(imageFile: File, imageInfo: PdfImageInfo): PdfImageData? {
        if (isJpeg(imageFile)) {
            return runCatching {
                PdfImageData(
                    colorBytes = imageFile.readBytes(),
                    alphaBytes = null,
                    width = imageInfo.width,
                    height = imageInfo.height
                )
            }.getOrNull()
        }
        val bitmap = decodeExportBitmap(imageFile, imageInfo.width, imageInfo.height) ?: return null
        try {
            val colorOutput = ByteArrayOutputStream()
            val alphaOutput = if (imageInfo.hasTransparency) ByteArrayOutputStream() else null
            DeflaterOutputStream(colorOutput).use { colorStream ->
                val alphaStream = alphaOutput?.let(::DeflaterOutputStream)
                try {
                    val row = IntArray(bitmap.width)
                    for (y in 0 until bitmap.height) {
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                        for (pixel in row) {
                            colorStream.write((pixel shr 16) and 0xFF)
                            colorStream.write((pixel shr 8) and 0xFF)
                            colorStream.write(pixel and 0xFF)
                            alphaStream?.write(pixel ushr 24)
                        }
                    }
                } finally {
                    alphaStream?.close()
                }
            }
            // Recycle bitmap immediately after encoding to reduce peak memory before toByteArray()
            bitmap.recycle()
            return PdfImageData(
                colorOutput.toByteArray(),
                alphaOutput?.toByteArray(),
                bitmap.width,
                bitmap.height
            )
        } catch (e: Exception) {
            bitmap.recycle()
            return null
        }
    }

    /**
     * Decodes a page for PDF export, trading resolution for memory only when a full
     * decode does not fit the heap (see [EXPORT_DECODE_BUDGET_COPIES]). Returns null
     * when the decode fails outright, like the previous unguarded decode did.
     */
    private fun decodeExportBitmap(imageFile: File, sourceWidth: Int, sourceHeight: Int): Bitmap? {
        var sampleSize = 1
        while (!ImageProcessingGuards.hasMemoryBudgetForBitmap(
                ceilDivide(sourceWidth, sampleSize),
                ceilDivide(sourceHeight, sampleSize),
                copies = EXPORT_DECODE_BUDGET_COPIES
            )
        ) {
            if (sourceWidth / sampleSize <= 2 && sourceHeight / sampleSize <= 2) break
            sampleSize *= 2
        }
        if (sampleSize > 1) {
            AppLogger.log(
                "PdfImageCodec",
                "PDF export memory guard: decoding ${sourceWidth}x${sourceHeight} with " +
                    "inSampleSize=$sampleSize (free heap ${freeHeapBytes()} bytes)"
            )
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(imageFile.absolutePath, options) }.getOrNull()
    }

    /**
     * Decodes a bounded bitmap for transparency detection (see
     * [EXPORT_TRANSPARENCY_PROBE_MAX_EDGE]), falling back to ever larger sample sizes
     * while the heap cannot hold even the capped probe. Returns null only when the
     * smallest probe cannot be decoded either; callers fail the export in that case,
     * exactly as the previous unguarded full decode did.
     */
    private fun decodeTransparencyProbe(imageFile: File, sourceWidth: Int, sourceHeight: Int): Bitmap? {
        var sampleSize = 1
        while (maxOf(sourceWidth, sourceHeight) / sampleSize > EXPORT_TRANSPARENCY_PROBE_MAX_EDGE &&
            sampleSize <= Int.MAX_VALUE / 2
        ) {
            sampleSize *= 2
        }
        while (!ImageProcessingGuards.hasMemoryBudgetForBitmap(
                ceilDivide(sourceWidth, sampleSize),
                ceilDivide(sourceHeight, sampleSize),
                copies = EXPORT_PROBE_BUDGET_COPIES
            )
        ) {
            if (sourceWidth / sampleSize <= 2 && sourceHeight / sampleSize <= 2) break
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(imageFile.absolutePath, options) }.getOrNull()
    }

    private fun ceilDivide(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt().coerceAtLeast(1)

    private fun freeHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()))
            .coerceAtLeast(0L)
    }

    private fun bitmapHasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { it ushr 24 != 0xFF }) return true
        }
        return false
    }

    private fun isJpeg(imageFile: File): Boolean {
        val ext = imageFile.name.substringAfterLast('.', "").lowercase()
        return ext == "jpg" || ext == "jpeg"
    }

}
