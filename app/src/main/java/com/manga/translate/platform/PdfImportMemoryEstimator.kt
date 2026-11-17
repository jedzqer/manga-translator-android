package com.manga.translate.platform

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Working memory above the already-running app, not an exact process-PSS prediction. */
internal object PdfImportMemoryEstimator {
    // Parser scratch space, native page structures, fonts and allocator/codec slack.
    private const val FIXED_OVERHEAD_BYTES = 64L * 1024 * 1024
    // PDFBox uses disk-backed mixed storage: do not charge an entire multi-GB
    // document as resident memory merely because its compressed streams are large.
    private const val MAX_DOCUMENT_RESERVE_BYTES = 64L * 1024 * 1024

    fun renderBytes(size: PdfImageCodec.ImportPageSize, sourceBytes: Long = 0, documentBytes: Long = 0): Long {
        val bitmap = DeviceResourcePolicy.estimateBitmapBytes(size.width, size.stripHeight)
        // A page is closed before PNG encoding. Budget the larger of rendering with
        // source caches and encoding with an extra destination-sized workspace.
        val working = maxOf(
            DeviceResourcePolicy.saturatingAdd(bitmap, sourceBytes),
            DeviceResourcePolicy.saturatingMultiply(bitmap, 2)
        )
        return withOverhead(working, documentBytes)
    }

    fun withOverhead(workingBytes: Long, documentBytes: Long): Long =
        DeviceResourcePolicy.saturatingAdd(
            workingBytes,
            DeviceResourcePolicy.saturatingAdd(FIXED_OVERHEAD_BYTES,
                documentBytes.coerceIn(0, MAX_DOCUMENT_RESERVE_BYTES))
        )

    suspend fun estimatePage(page: PDPage, size: PdfImageCodec.ImportPageSize, documentBytes: Long): Long {
        val collector = ImageCollector(page, size, currentCoroutineContext())
        collector.processPage(page)
        return renderBytes(size, collector.peakSourceBytes, documentBytes)
    }

    /** Visits drawing operators and image dictionaries without requesting image pixels.
     * Form transforms are handled by PDFBox; ignored clipping deliberately overestimates.
     */
    private class ImageCollector(
        page: PDPage,
        private val size: PdfImageCodec.ImportPageSize,
        private val coroutineContext: CoroutineContext
    ) : PDFGraphicsStreamEngine(page) {
        private val strips = size.strips().toList()
        private val images = strips.map { Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>()) }
        private val decodedBytes = LongArray(strips.size)
        private val largestDecode = LongArray(strips.size)
        private var point = PointF()
        val peakSourceBytes: Long
            get() = decodedBytes.indices.maxOfOrNull {
                DeviceResourcePolicy.saturatingAdd(decodedBytes[it], largestDecode[it])
            } ?: 0L

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            coroutineContext.ensureActive()
            super.processOperator(operator, operands)
        }

        override fun drawImage(image: PDImage) {
            coroutineContext.ensureActive()
            val matrix = graphicsState.currentTransformationMatrix
            val corners = listOf(matrix.transformPoint(0f, 0f), matrix.transformPoint(1f, 0f),
                matrix.transformPoint(0f, 1f), matrix.transformPoint(1f, 1f))
            val crop = page.cropBox
            val rotation = ((page.rotation % 360) + 360) % 360
            val points = corners.map { p ->
                when (rotation) {
                    90 -> PointF((p.y - crop.lowerLeftY) / crop.height, (p.x - crop.lowerLeftX) / crop.width)
                    180 -> PointF((crop.upperRightX - p.x) / crop.width, (p.y - crop.lowerLeftY) / crop.height)
                    270 -> PointF((crop.upperRightY - p.y) / crop.height, (crop.upperRightX - p.x) / crop.width)
                    else -> PointF((p.x - crop.lowerLeftX) / crop.width, (crop.upperRightY - p.y) / crop.height)
                }
            }
            // Unknown geometry must not make a source image disappear from the budget.
            val valid = points.all { it.x.isFinite() && it.y.isFinite() }
            if (valid && (points.maxOf { it.x } < 0f || points.minOf { it.x } > 1f)) return
            val top = if (valid) points.minOf { it.y }.toDouble() * size.height else 0.0
            val bottom = if (valid) points.maxOf { it.y }.toDouble() * size.height else size.height.toDouble()
            var bytes = DeviceResourcePolicy.estimateBitmapBytes(image.width, image.height)
            val dictionary = image.cosObject as? COSDictionary
            for (name in listOf(COSName.SMASK, COSName.MASK)) {
                val mask = dictionary?.getDictionaryObject(name) as? COSDictionary ?: continue
                val width = mask.getInt(COSName.WIDTH)
                val height = mask.getInt(COSName.HEIGHT)
                if (width > 0 && height > 0) {
                    bytes = DeviceResourcePolicy.saturatingAdd(bytes, DeviceResourcePolicy.estimateBitmapBytes(width, height))
                }
            }
            for ((index, strip) in strips.withIndex()) {
                // Include boundaries for native antialiasing/interpolation.
                if (bottom >= strip.top - 1 && top <= strip.top.toLong() + strip.height + 1 &&
                    images[index].add(image.cosObject)) {
                    decodedBytes[index] = DeviceResourcePolicy.saturatingAdd(decodedBytes[index], bytes)
                    largestDecode[index] = maxOf(largestDecode[index], bytes)
                }
            }
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) { point = p0 }
        override fun moveTo(x: Float, y: Float) { point = PointF(x, y) }
        override fun lineTo(x: Float, y: Float) { point = PointF(x, y) }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { point = PointF(x3, y3) }
        override fun getCurrentPoint(): PointF = point
        override fun closePath() = Unit
        override fun endPath() = Unit
        override fun strokePath() = Unit
        override fun fillPath(windingRule: Path.FillType) = Unit
        override fun fillAndStrokePath(windingRule: Path.FillType) = Unit
        override fun clip(windingRule: Path.FillType) = Unit
        override fun shadingFill(shadingName: COSName) = Unit
    }
}
