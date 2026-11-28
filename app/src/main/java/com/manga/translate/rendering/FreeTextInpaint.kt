package com.manga.translate.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.manga.translate.di.appContainer
import com.manga.translate.platform.AppLogger
import java.io.File
import kotlin.math.ceil
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.photo.Photo
import org.opencv.imgproc.Imgproc

internal object FreeTextInpaint {
    private const val TIGHT_SELECTION_MAX_SIDE = 96

    private val ready by lazy {
        try {
            OpenCVLoader.initLocal()
        } catch (error: LinkageError) {
            AppLogger.log("FreeTextInpaint", "OpenCV unavailable", error)
            false
        }
    }

    // Transparent outside the stroke mask, so overlapping overlays keep their contents.
    class Patch(val bitmap: Bitmap, val samplingBitmap: Bitmap, val normalizedBounds: RectF) {
        fun draw(canvas: Canvas, page: RectF, alpha: Int) {
            val r = normalizedBounds
            val target = RectF(page.left + r.left * page.width(), page.top + r.top * page.height(),
                page.left + r.right * page.width(), page.top + r.bottom * page.height())
            canvas.drawBitmap(bitmap, null, target, Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
        }

        fun drawText(page: RectF, dx: Float, dy: Float, paint: android.text.TextPaint,
            opacity: Int, draw: () -> Unit) {
            val bounds = RectF(page.left + normalizedBounds.left * page.width() - dx,
                page.top + normalizedBounds.top * page.height() - dy,
                page.left + normalizedBounds.right * page.width() - dx,
                page.top + normalizedBounds.bottom * page.height() - dy)
            FreeTextBackground.draw(samplingBitmap, bounds, paint, opacity, draw)
        }

    }

    @Suppress("DEPRECATION")
    fun create(context: Context, bitmap: Bitmap?, file: File?, normalized: RectF): Patch? {
        if (!ready) return null
        val decoder = if (file != null) {
            runCatching { BitmapRegionDecoder.newInstance(file.absolutePath, false) }.getOrNull()
        } else null
        try {
            val source = bitmap?.takeUnless { it.isRecycled }
            val w = decoder?.width ?: source?.width ?: return null
            val h = decoder?.height ?: source?.height ?: return null
            val box = Rect((normalized.left * w).toInt(), (normalized.top * h).toInt(),
                ceil(normalized.right * w).toInt(), ceil(normalized.bottom * h).toInt())
            if (!box.intersect(0, 0, w, h) || minOf(box.width(), box.height()) < 5) return null
            val crop = Rect(box).apply {
                // Include enough context for the line detector on thin captions.
                inset(-maxOf(4, (32 - width()).coerceAtLeast(0)),
                    -maxOf(4, (32 - height()).coerceAtLeast(0)))
                intersect(0, 0, w, h)
            }
            // Bound the working image even for large hand-drawn boxes.
            var sample = 1
            while (ceil(crop.width().toDouble() / sample) *
                ceil(crop.height().toDouble() / sample) > 262144
            ) sample *= 2
            val width = ceil(crop.width().toDouble() / sample).toInt()
            val height = ceil(crop.height().toDouble() / sample).toInt()
            val local = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val target = Rect(0, 0, width, height)
            if (decoder == null) {
                Canvas(local).drawBitmap(source ?: return null, crop, target, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = decoder.decodeRegion(crop, options) ?: return null
                try { Canvas(local).drawBitmap(decoded, null, target, null) }
                finally { decoded.recycle() }
            }
            val pixels = IntArray(width * height)
            local.getPixels(pixels, 0, width, 0, 0, width, height)
            val sx = width.toFloat() / crop.width()
            val sy = height.toFloat() / crop.height()
            val strokesBox = Rect(((box.left - crop.left) * sx).toInt(), ((box.top - crop.top) * sy).toInt(),
                ceil((box.right - crop.left) * sx).toInt(), ceil((box.bottom - crop.top) * sy).toInt()).apply { intersect(target) }
            // Small selections already localize the strokes. A large selection may contain
            // whole panels, so require text-line evidence before touching their artwork.
            val tightSelection = maxOf(strokesBox.width(), strokesBox.height()) <= TIGHT_SELECTION_MAX_SIDE && sample == 1
            val lines = if (tightSelection) {
                listOf(strokesBox)
            } else {
                context.appContainer.ocrEngineRegistry.getEnglishLineDetector("FreeTextInpaint")
                    ?.detectLines(local).orEmpty().mapNotNull { line ->
                        val bounds = Rect(line.left.toInt(), line.top.toInt(),
                            ceil(line.right).toInt(), ceil(line.bottom).toInt())
                        // A clipped detection lacks the context to distinguish letters
                        // from texture continuing on the other side of the crop.
                        bounds.takeIf { it.left > 0 && it.top > 0 && it.right < width &&
                            it.bottom < height && it.intersect(strokesBox) }
                    }
            }
            val repairedPixels = IntArray(pixels.size)
            if (lines.isEmpty() || minOf(width, height) < 5 ||
                !repair(pixels, repairedPixels, width, height, strokesBox, lines, tightSelection)) {
                local.recycle()
                return null
            }
            local.setPixels(pixels, 0, width, 0, 0, width, height)
            val samplingBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            samplingBitmap.setPixels(repairedPixels, 0, width, 0, 0, width, height)
            return Patch(local, samplingBitmap, RectF(crop.left.toFloat()/w, crop.top.toFloat()/h,
                crop.right.toFloat()/w, crop.bottom.toFloat()/h))
        } catch (e: RuntimeException) {
            AppLogger.log("FreeTextInpaint", "Could not repair text region", e)
            return null
        } finally {
            decoder?.recycle()
        }
    }

    private fun repair(pixels: IntArray, repairedPixels: IntArray, w: Int, h: Int, box: Rect,
        lines: List<Rect>, tightSelection: Boolean): Boolean {
        val rgb = Mat(h, w, CvType.CV_8UC3)
        val gray = Mat()
        val mask = Mat()
        val result = Mat()
        val kernel = Mat.ones(3, 3, CvType.CV_8U)
        try {
            val bytes = ByteArray(pixels.size * 3)
            for (i in pixels.indices) {
                val color = pixels[i]
                bytes[3*i] = Color.red(color).toByte()
                bytes[3*i+1] = Color.green(color).toByte()
                bytes[3*i+2] = Color.blue(color).toByte()
            }
            rgb.put(0, 0, bytes)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            // Suppress fine screentone dots before selecting strokes. Repair uses the original RGB.
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
            val luminance = ByteArray(pixels.size)
            gray.get(0, 0, luminance)
            val lightRegions = ByteArray(pixels.size)
            val darkRegions = ByteArray(pixels.size)
            for (line in lines) {
                var sum = 0L
                for (y in line.top until line.bottom) for (x in line.left until line.right) {
                    sum += luminance[y * w + x].toInt() and 255
                }
                val region = if (sum >= 128L * line.width() * line.height()) lightRegions else darkRegions
                for (y in line.top until line.bottom) {
                    region.fill(1, y * w + line.left, y * w + line.right)
                }
            }
            val selected = ByteArray(pixels.size)
            collectStrokes(gray, lightRegions, true, tightSelection, selected)
            collectStrokes(gray, darkRegions, false, tightSelection, selected)
            mask.create(h, w, CvType.CV_8U)
            mask.put(0, 0, selected)
            Imgproc.dilate(mask, mask, kernel)
            val strokes = ByteArray(pixels.size)
            mask.get(0, 0, strokes)
            for (i in strokes.indices) if (!box.contains(i % w, i / w)) strokes[i] = 0
            mask.put(0, 0, strokes)
            val ink = Core.countNonZero(mask)
            val coverageLimit = if (tightSelection) 0.35 else 0.65
            if (ink == 0 || ink > box.width() * box.height() * coverageLimit) return false
            Photo.inpaint(rgb, mask, result, 3.0, Photo.INPAINT_TELEA)
            result.get(0, 0, bytes)
            for (i in pixels.indices) {
                val repaired = Color.argb(Color.alpha(pixels[i]), bytes[3*i].toInt() and 255,
                    bytes[3*i+1].toInt() and 255, bytes[3*i+2].toInt() and 255)
                pixels[i] = if (strokes[i].toInt() == 0) Color.TRANSPARENT else repaired
            }
            // Smooth the complete repaired crop once, before discarding pixels outside the ink mask.
            val sigma = (minOf(box.width(), box.height()) * 0.06).coerceIn(1.0, 8.0)
            Imgproc.GaussianBlur(result, result, Size(0.0, 0.0), sigma)
            result.get(0, 0, bytes)
            for (i in repairedPixels.indices) {
                repairedPixels[i] = Color.rgb(bytes[3*i].toInt() and 255,
                    bytes[3*i+1].toInt() and 255, bytes[3*i+2].toInt() and 255)
            }
            return true
        } finally {
            rgb.release()
            gray.release()
            mask.release()
            result.release()
            kernel.release()
        }
    }

    private fun collectStrokes(
        gray: Mat, region: ByteArray, lightBackground: Boolean,
        tightSelection: Boolean, selected: ByteArray
    ) {
        if (region.none { it != 0.toByte() }) return
        val candidates = Mat()
        val labels = Mat()
        try {
            val block = minOf(31, gray.cols(), gray.rows()).let { if (it % 2 == 0) it - 1 else it }
            val contrast = if (tightSelection) 8.0 else 16.0
            Imgproc.adaptiveThreshold(gray, candidates, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                if (lightBackground) Imgproc.THRESH_BINARY_INV else Imgproc.THRESH_BINARY,
                block, if (lightBackground) contrast else -contrast)
            if (tightSelection) {
                val strokes = ByteArray(region.size)
                candidates.get(0, 0, strokes)
                for (i in region.indices) if (region[i] != 0.toByte()) selected[i] = strokes[i]
                return
            }
            // Label before clipping. A contour that continues into hair, a panel rule or
            // a balloon outline must survive even where it runs through a detected line.
            val count = Imgproc.connectedComponents(candidates, labels)
            val ids = IntArray(region.size)
            labels.get(0, 0, ids)
            val contained = BooleanArray(count) { true }
            contained[0] = false
            for (i in ids.indices) if (region[i] == 0.toByte()) contained[ids[i]] = false
            for (i in ids.indices) if (contained[ids[i]]) selected[i] = 0xff.toByte()
        } finally {
            candidates.release()
            labels.release()
        }
    }
}
