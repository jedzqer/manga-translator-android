package com.manga.translate.platform

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.OperatorName
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** Extracts full-page scan images without rasterizing them at an arbitrary PDF DPI. */
internal class PdfOriginalImageExtractor private constructor(
    private val sourceFile: File,
    private val document: PDDocument
) : Closeable {

    fun openRendererDescriptor(): ParcelFileDescriptor =
        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)

    suspend fun estimatePagePeakBytes(pageIndex: Int, size: PdfImageCodec.ImportPageSize): Long {
        currentCoroutineContext().ensureActive()
        return try {
            val page = document.getPage(pageIndex)
            val image = findFullPageImage(page)
            if (image != null && canCopyOriginalJpeg(image)) {
                // Stream copying a scan does not allocate a bitmap at its source resolution.
                PdfImportMemoryEstimator.withOverhead(0, sourceFile.length())
            } else {
                val rendered = PdfImportMemoryEstimator.estimatePage(page, size, sourceFile.length())
                if (image == null) rendered else maxOf(rendered,
                    PdfImportMemoryEstimator.withOverhead(
                        DeviceResourcePolicy.saturatingMultiply(
                            DeviceResourcePolicy.estimateBitmapBytes(image.width, image.height), 3
                        ), sourceFile.length()
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.log("PdfImageCodec", "PDF image memory inspection unavailable on page ${pageIndex + 1}", error)
            PdfImportMemoryEstimator.renderBytes(size, documentBytes = sourceFile.length())
        }
    }

    suspend fun extractPage(pageIndex: Int, outputDir: File): Boolean {
        currentCoroutineContext().ensureActive()
        val image = findFullPageImage(document.getPage(pageIndex)) ?: return false
        val copyJpeg = canCopyOriginalJpeg(image)
        val extension = if (copyJpeg) "jpg" else "png"
        val fileName = pageFileName(pageIndex, extension)
        val destination = File(outputDir, fileName)
        val temporary = File(outputDir, ".$fileName.tmp")
        return try {
            FileOutputStream(temporary).use { output ->
                if (copyJpeg) {
                    image.stream.createInputStream(listOf(COSName.DCT_DECODE.name)).use { input ->
                        input.copyTo(output)
                    }
                } else {
                    val bitmap = image.image ?: return false
                    try {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return false
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!isValidExtractedImage(temporary, image.width, image.height)) return false
            if (!temporary.renameTo(destination)) return false
            AppLogger.log(
                "PdfImageCodec",
                "Page ${pageIndex + 1} imported from embedded ${image.width}x${image.height} image"
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.log(
                "PdfImageCodec",
                "Embedded image extraction failed on page ${pageIndex + 1}",
                error
            )
            false
        } catch (error: OutOfMemoryError) {
            AppLogger.log(
                "PdfImageCodec",
                "Embedded image extraction ran out of memory on page ${pageIndex + 1}",
                error
            )
            false
        } finally {
            temporary.delete()
        }
    }

    override fun close() {
        runCatching { document.close() }
        sourceFile.delete()
    }

    companion object {
        private const val PDFBOX_MAIN_MEMORY_BYTES = 16L * 1024L * 1024L
        private const val FULL_PAGE_TOLERANCE = 0.01f
        private const val IMPORT_FILE_NAME_WIDTH = 4

        suspend fun open(
            context: Context,
            contentResolver: ContentResolver,
            uri: Uri
        ): PdfOriginalImageExtractor? {
            val sourceFile = File.createTempFile("pdf_import_", ".pdf", context.cacheDir)
            return try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: return null.also { sourceFile.delete() }
                PDFBoxResourceLoader.init(context.applicationContext)
                val memoryUsage = MemoryUsageSetting.setupMixed(PDFBOX_MAIN_MEMORY_BYTES)
                    .setTempDir(context.cacheDir)
                PdfOriginalImageExtractor(sourceFile, PDDocument.load(sourceFile, memoryUsage))
            } catch (error: CancellationException) {
                sourceFile.delete()
                throw error
            } catch (error: Exception) {
                AppLogger.log("PdfImageCodec", "PDF structure parsing unavailable; using renderer", error)
                sourceFile.delete()
                null
            } catch (error: OutOfMemoryError) {
                AppLogger.log("PdfImageCodec", "PDF structure parsing ran out of memory; using renderer", error)
                sourceFile.delete()
                null
            }
        }

        internal fun findFullPageImage(page: PDPage): PDImageXObject? {
            if (!page.hasContents() || page.rotation != 0) return null
            val tokens = try {
                PDFStreamParser(page).apply { parse() }.tokens
            } catch (_: IOException) {
                return null
            }
            val operands = ArrayList<Any>(6)
            val matrixStack = ArrayDeque<FloatArray>()
            var matrix = identityMatrix()
            var imageMatrix: FloatArray? = null
            var imageName: COSName? = null
            var imageCount = 0
            for (token in tokens) {
                when (token) {
                    is COSNumber, is COSName -> operands += token
                    is Operator -> {
                        when (token.name) {
                            OperatorName.SAVE -> {
                                if (operands.isNotEmpty()) return null
                                matrixStack.addLast(matrix.copyOf())
                            }
                            OperatorName.RESTORE -> {
                                if (operands.isNotEmpty() || matrixStack.isEmpty()) return null
                                matrix = matrixStack.removeLast()
                            }
                            OperatorName.CONCAT -> {
                                if (operands.size != 6 || operands.any { it !is COSNumber }) return null
                                val concat = FloatArray(6) { index ->
                                    (operands[index] as COSNumber).floatValue()
                                }
                                matrix = multiply(matrix, concat)
                            }
                            OperatorName.DRAW_OBJECT -> {
                                if (operands.size != 1 || operands[0] !is COSName) return null
                                imageName = operands[0] as COSName
                                imageMatrix = matrix.copyOf()
                                imageCount += 1
                            }
                            else -> return null
                        }
                        operands.clear()
                    }
                    else -> return null
                }
            }
            if (operands.isNotEmpty() || matrixStack.isNotEmpty() || imageCount != 1) return null
            val resolvedImageName = imageName ?: return null
            val resolvedImageMatrix = imageMatrix ?: return null
            val crop = page.cropBox
            // Direct extraction is only safe for an unrotated, axis-aligned image. A
            // rotated or clipped image still falls back to PdfRenderer, preserving its
            // visual orientation instead of returning a wrongly oriented source file.
            val coversPage = resolvedImageMatrix[0] > 0f && resolvedImageMatrix[3] > 0f &&
                approximately(resolvedImageMatrix[0], crop.width) &&
                approximately(resolvedImageMatrix[1], 0f) &&
                approximately(resolvedImageMatrix[2], 0f) &&
                approximately(resolvedImageMatrix[3], crop.height) &&
                approximately(resolvedImageMatrix[4], crop.lowerLeftX) &&
                approximately(resolvedImageMatrix[5], crop.lowerLeftY)
            if (!coversPage) return null
            val image = try {
                page.resources?.getXObject(resolvedImageName) as? PDImageXObject
            } catch (_: IOException) {
                null
            } ?: return null
            return image.takeIf { !it.isStencil && it.width > 0 && it.height > 0 }
        }

        private fun canCopyOriginalJpeg(image: PDImageXObject): Boolean {
            return try {
                val filters = image.stream.filters.orEmpty()
                filters.lastOrNull() == COSName.DCT_DECODE &&
                    image.softMask == null &&
                    image.mask == null &&
                    image.colorKeyMask == null &&
                    !image.cosObject.containsKey(COSName.DECODE) &&
                    (image.colorSpace is PDDeviceRGB || image.colorSpace is PDDeviceGray)
            } catch (_: IOException) {
                false
            }
        }

        private fun isValidExtractedImage(file: File, width: Int, height: Int): Boolean {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            return options.outWidth == width && options.outHeight == height
        }

        private fun identityMatrix(): FloatArray =
            floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

        /** Returns the affine transform obtained by applying [right] then [left]. */
        private fun multiply(left: FloatArray, right: FloatArray): FloatArray =
            floatArrayOf(
                left[0] * right[0] + left[2] * right[1],
                left[1] * right[0] + left[3] * right[1],
                left[0] * right[2] + left[2] * right[3],
                left[1] * right[2] + left[3] * right[3],
                left[0] * right[4] + left[2] * right[5] + left[4],
                left[1] * right[4] + left[3] * right[5] + left[5]
            )

        private fun approximately(actual: Float, expected: Float): Boolean {
            val tolerance = maxOf(1f, abs(expected) * FULL_PAGE_TOLERANCE)
            return abs(actual - expected) <= tolerance
        }

        private fun pageFileName(pageIndex: Int, extension: String): String =
            "${(pageIndex + 1).toString().padStart(IMPORT_FILE_NAME_WIDTH, '0')}.$extension"
    }
}
