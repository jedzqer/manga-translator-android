package com.manga.translate.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.LruCache
import androidx.core.graphics.scale
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val DETECTION_MAX_EDGE = 1920

internal data class PipelineImageSize(
    val width: Int,
    val height: Int
)

data class PipelineDetectionBitmap(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    val scaleX: Float
        get() = sourceWidth / bitmap.width.toFloat().coerceAtLeast(1f)

    val scaleY: Float
        get() = sourceHeight / bitmap.height.toFloat().coerceAtLeast(1f)
}

internal object PipelineBitmapDecoder {
    private const val OCR_CROP_MAX_EDGE = 2048
    private const val IMAGE_SIZE_CACHE_ENTRIES = 2048

    private data class ImageSizeCacheEntry(
        val lastModified: Long,
        val fileSize: Long,
        val size: PipelineImageSize
    )

    private val imageSizeCache =
        LruCache<String, ImageSizeCacheEntry>(IMAGE_SIZE_CACHE_ENTRIES)

    suspend fun decodeForDetection(
        imageFile: File,
        maxEdge: Int = DETECTION_MAX_EDGE
    ): PipelineDetectionBitmap? {
        if (ImageFileSupport.isAvifFile(imageFile.name)) {
            val size = AvifBitmapDecoder.getSize(imageFile) ?: return null
            val target = targetSize(size.width, size.height, maxEdge)
            val (bitmap, sourceSize) = AvifBitmapDecoder.decodeSampled(
                imageFile,
                target.first,
                target.second
            )
            val source = sourceSize ?: return null
            bitmap ?: return null
            return PipelineDetectionBitmap(
                bitmap = bitmap,
                sourceWidth = source.width,
                sourceHeight = source.height
            )
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val sampleSize = calculateInSampleSizeForMaxEdge(sourceWidth, sourceHeight, maxEdge)
        val bitmap = ImageProcessingGuards.withDecodePermit(
            width = sourceWidth,
            height = sourceHeight,
            tag = "PipelineDecoder"
        ) {
            BitmapFactory.decodeFile(
                imageFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } ?: return null
        return PipelineDetectionBitmap(bitmap, sourceWidth, sourceHeight)
    }

    /**
     * Reads image dimensions, caching by path plus file fingerprint.
     *
     * Batch translation reads the size of every page at least twice: once while
     * scanning a folder for pages that still need work (via the OCR cache mode in
     * the expected metadata) and again while processing the page. Each read was a
     * separate bounds decode, which on a 1000-page folder meant a long unresponsive
     * stretch before the first page started. The cache is invalidated by
     * `lastModified` and length, so an edited or replaced image is re-read.
     */
    fun readImageSize(imageFile: File): PipelineImageSize? {
        val path = imageFile.absolutePath
        val lastModified = imageFile.lastModified()
        val length = imageFile.length()
        synchronized(imageSizeCache) {
            imageSizeCache.get(path)?.let { cached ->
                if (cached.lastModified == lastModified && cached.fileSize == length) {
                    return cached.size
                }
            }
        }
        val size = decodeImageSize(imageFile) ?: return null
        synchronized(imageSizeCache) {
            imageSizeCache.put(path, ImageSizeCacheEntry(lastModified, length, size))
        }
        return size
    }

    private fun decodeImageSize(imageFile: File): PipelineImageSize? {
        if (ImageFileSupport.isAvifFile(imageFile.name)) {
            val size = AvifBitmapDecoder.getSize(imageFile) ?: return null
            return PipelineImageSize(size.width, size.height)
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        return PipelineImageSize(sourceWidth, sourceHeight)
    }

    suspend fun prepareDetectionBitmap(
        source: Bitmap,
        maxEdge: Int = DETECTION_MAX_EDGE
    ): PipelineDetectionBitmap {
        val maxSourceEdge = max(source.width, source.height)
        if (maxSourceEdge <= maxEdge) {
            return PipelineDetectionBitmap(source, source.width, source.height)
        }
        val scale = maxEdge / maxSourceEdge.toFloat()
        val targetWidth = max(1, (source.width * scale).roundToInt())
        val targetHeight = max(1, (source.height * scale).roundToInt())
        val scaled = ImageProcessingGuards.withDecodePermit(
            width = source.width,
            height = source.height,
            tag = "PipelineDecoder"
        ) {
            source.scale(targetWidth, targetHeight)
        }
        return PipelineDetectionBitmap(
            bitmap = scaled,
            sourceWidth = source.width,
            sourceHeight = source.height
        )
    }

    suspend fun openCropSource(imageFile: File): BitmapCropSource? {
        return if (ImageFileSupport.isAvifFile(imageFile.name)) {
            AvifBitmapCropSource(imageFile)
        } else {
            try {
                FileBitmapRegionCropSource(imageFile)
            } catch (error: Exception) {
                // Region decoding is not available for every WebP variant. Preserve source
                // coordinates and use a bounded whole-image decode through the compatible path.
                AppLogger.log(
                    "PipelineDecoder",
                    "BitmapRegionDecoder rejected ${imageFile.name}; using sampled whole-image " +
                        "fallback (${error::class.java.simpleName}: ${error.message.orEmpty()})"
                )
                SampledFileBitmapCropSource.open(imageFile)
            }
        }
    }

    fun openCropSource(bitmap: Bitmap): BitmapCropSource {
        return InMemoryBitmapCropSource(bitmap)
    }

    private fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val longestEdge = max(width, height).coerceAtLeast(1)
        if (longestEdge <= maxEdge) return width to height
        val scale = maxEdge / longestEdge.toFloat()
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun calculateInSampleSizeForMaxEdge(
        sourceWidth: Int,
        sourceHeight: Int,
        maxEdge: Int
    ): Int {
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= maxEdge ||
            sourceHeight / (sample * 2) >= maxEdge
        ) {
            sample *= 2
        }
        return max(sample, 1)
    }

    internal fun calculateFallbackSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxPixels: Long = 12_000_000L
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxPixels <= 0L) return 1
        var sample = 1
        while (
            ceilDiv(sourceWidth, sample).toLong() * ceilDiv(sourceHeight, sample) > maxPixels &&
            sample <= Int.MAX_VALUE / 2
        ) {
            sample *= 2
        }
        return sample
    }

    private fun ceilDiv(value: Int, divisor: Int): Int {
        return ((value.toLong() + divisor - 1L) / divisor).toInt()
    }

    private class FileBitmapRegionCropSource(
        imageFile: File
    ) : BitmapCropSource {
        private val decoder = createBitmapRegionDecoder(imageFile)
        private val decodeLock = Any()

        override val width: Int = decoder.width
        override val height: Int = decoder.height

        override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap? {
            val bounds = rect.toDecodeRect(width, height) ?: return null
            val sampleSize = calculateInSampleSizeForMaxEdge(bounds.width(), bounds.height(), maxEdge)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = ImageProcessingGuards.withDecodePermit(
                width = bounds.width(),
                height = bounds.height(),
                tag = "PipelineDecoder"
            ) {
                synchronized(decodeLock) {
                    runCatching { decoder.decodeRegion(bounds, options) }.getOrNull()
                }
            }
            return decoded?.let { scaleDownIfNeeded(it, maxEdge) }
        }

        override fun close() {
            decoder.recycle()
        }

        private fun createBitmapRegionDecoder(imageFile: File): BitmapRegionDecoder {
            val path = imageFile.absolutePath
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(path)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(path, false)
            }
        }
    }

    private class SampledFileBitmapCropSource(
        private val imageFile: File,
        override val width: Int,
        override val height: Int,
        private val sampleSize: Int,
        initialBitmap: Bitmap? = null
    ) : BitmapCropSource {
        private var bitmap: Bitmap? = initialBitmap

        override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap? {
            val source = ensureBitmap() ?: return null
            val scaleX = source.width / width.toFloat().coerceAtLeast(1f)
            val scaleY = source.height / height.toFloat().coerceAtLeast(1f)
            val sampledRect = RectF(
                rect.left * scaleX,
                rect.top * scaleY,
                rect.right * scaleX,
                rect.bottom * scaleY
            )
            val crop = cropBitmap(source, sampledRect) ?: return null
            if (crop === source && max(source.width, source.height) > maxEdge) {
                val scale = maxEdge / max(source.width, source.height).toFloat()
                val targetWidth = max(1, (source.width * scale).roundToInt())
                val targetHeight = max(1, (source.height * scale).roundToInt())
                return ImageProcessingGuards.withDecodePermit(
                    width = targetWidth,
                    height = targetHeight,
                    tag = "PipelineDecoder"
                ) {
                    source.scale(targetWidth, targetHeight)
                }
            }
            val ownedCrop = ensureOwnedCrop(crop, source) ?: return null
            return scaleDownIfNeeded(ownedCrop, maxEdge)
        }

        override fun close() {
            bitmap.recycleSafely()
            bitmap = null
        }

        private suspend fun ensureBitmap(): Bitmap? {
            if (bitmap != null && bitmap?.isRecycled == false) return bitmap
            val decodedWidth = ceilDiv(width, sampleSize)
            val decodedHeight = ceilDiv(height, sampleSize)
            val config = if (
                ImageProcessingGuards.hasMemoryBudgetForBitmap(
                    decodedWidth,
                    decodedHeight,
                    copies = 2
                )
            ) {
                Bitmap.Config.ARGB_8888
            } else {
                Bitmap.Config.RGB_565
            }
            bitmap = ImageProcessingGuards.withDecodePermit(
                width = decodedWidth,
                height = decodedHeight,
                tag = "PipelineDecoder"
            ) {
                runCatching {
                    BitmapFactory.decodeFile(
                        imageFile.absolutePath,
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = config
                            inScaled = false
                        }
                    )
                }.getOrNull()
            }
            if (bitmap == null) {
                val fallback = decodeFileWithImageDecoder(
                    imageFile = imageFile,
                    expectedWidth = width,
                    expectedHeight = height,
                    requestedSampleSize = sampleSize
                )
                bitmap = fallback?.bitmap
                if (bitmap != null) {
                    AppLogger.log(
                        "PipelineDecoder",
                        "ImageDecoder pixel fallback opened ${imageFile.name} as " +
                            "${bitmap?.width}x${bitmap?.height}"
                    )
                }
            }
            return bitmap
        }

        companion object {
            suspend fun open(imageFile: File): BitmapCropSource? {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
                val sourceWidth = bounds.outWidth
                val sourceHeight = bounds.outHeight
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    return openImageDecoderCropSource(imageFile)
                }
                return SampledFileBitmapCropSource(
                    imageFile = imageFile,
                    width = sourceWidth,
                    height = sourceHeight,
                    sampleSize = calculateFallbackSampleSize(sourceWidth, sourceHeight)
                )
            }
        }
    }

    private suspend fun openImageDecoderCropSource(imageFile: File): BitmapCropSource? {
        val fallback = decodeFileWithImageDecoder(imageFile) ?: return null
        val fallbackSampleSize = calculateFallbackSampleSize(
            fallback.sourceWidth,
            fallback.sourceHeight
        )
        AppLogger.log(
            "PipelineDecoder",
            "ImageDecoder fallback opened ${imageFile.name} as " +
                "${fallback.sourceWidth}x${fallback.sourceHeight} -> " +
                "${fallback.bitmap.width}x${fallback.bitmap.height}"
        )
        return SampledFileBitmapCropSource(
            imageFile = imageFile,
            width = fallback.sourceWidth,
            height = fallback.sourceHeight,
            sampleSize = fallbackSampleSize,
            initialBitmap = fallback.bitmap
        )
    }

    private data class ImageDecoderResult(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    private suspend fun decodeFileWithImageDecoder(
        imageFile: File,
        expectedWidth: Int = 0,
        expectedHeight: Int = 0,
        requestedSampleSize: Int? = null
    ): ImageDecoderResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val guardSampleSize = requestedSampleSize ?: calculateFallbackSampleSize(
            expectedWidth,
            expectedHeight
        )
        val guardWidth = if (expectedWidth > 0) ceilDiv(expectedWidth, guardSampleSize) else 0
        val guardHeight = if (expectedHeight > 0) ceilDiv(expectedHeight, guardSampleSize) else 0
        var sourceWidth = expectedWidth
        var sourceHeight = expectedHeight
        val bitmap = ImageProcessingGuards.withDecodePermit(
            width = guardWidth,
            height = guardHeight,
            tag = "PipelineDecoder"
        ) {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(imageFile)) { decoder, info, _ ->
                    sourceWidth = info.size.width
                    sourceHeight = info.size.height
                    val sampleSize = requestedSampleSize ?: calculateFallbackSampleSize(
                        sourceWidth,
                        sourceHeight
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sampleSize)
                }
            } catch (error: Exception) {
                AppLogger.log(
                    "PipelineDecoder",
                    "ImageDecoder fallback rejected ${imageFile.name}",
                    error
                )
                null
            }
        } ?: return null
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            bitmap.recycleSafely()
            return null
        }
        return ImageDecoderResult(bitmap, sourceWidth, sourceHeight)
    }

    private class AvifBitmapCropSource(
        private val imageFile: File
    ) : BitmapCropSource {
        private var bitmap: Bitmap? = null

        override val width: Int by lazy {
            AvifBitmapDecoder.getSize(imageFile)?.width ?: 0
        }

        override val height: Int by lazy {
            AvifBitmapDecoder.getSize(imageFile)?.height ?: 0
        }

        override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap? {
            val source = ensureBitmap() ?: return null
            val crop = cropBitmap(source, rect) ?: return null
            val ownedCrop = ensureOwnedCrop(crop, source) ?: return null
            return scaleDownIfNeeded(ownedCrop, maxEdge)
        }

        override fun close() {
            bitmap.recycleSafely()
            bitmap = null
        }

        private suspend fun ensureBitmap(): Bitmap? {
            if (bitmap != null && bitmap?.isRecycled == false) return bitmap
            bitmap = AvifBitmapDecoder.decode(imageFile)
            return bitmap
        }
    }

    private class InMemoryBitmapCropSource(
        private val bitmap: Bitmap
    ) : BitmapCropSource {
        override val width: Int
            get() = bitmap.width
        override val height: Int
            get() = bitmap.height

        override suspend fun decodeRegion(rect: RectF, maxEdge: Int): Bitmap? {
            val crop = cropBitmap(bitmap, rect) ?: return null
            val ownedCrop = ensureOwnedCrop(crop, bitmap) ?: return null
            return scaleDownIfNeeded(ownedCrop, maxEdge)
        }

        override fun close() = Unit
    }

    private fun ensureOwnedCrop(crop: Bitmap, source: Bitmap): Bitmap? {
        if (crop !== source) return crop
        val copyConfig = source.config?.let { config ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                config == Bitmap.Config.HARDWARE
            ) {
                Bitmap.Config.ARGB_8888
            } else {
                config
            }
        } ?: Bitmap.Config.ARGB_8888
        return runCatching { source.copy(copyConfig, false) }.getOrNull()
    }

    internal suspend fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int = OCR_CROP_MAX_EDGE): Bitmap {
        val longestEdge = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        if (longestEdge <= maxEdge) return bitmap
        val scale = maxEdge / longestEdge.toFloat()
        val targetWidth = max(1, (bitmap.width * scale).roundToInt())
        val targetHeight = max(1, (bitmap.height * scale).roundToInt())
        val scaled = ImageProcessingGuards.withDecodePermit(
            width = bitmap.width,
            height = bitmap.height,
            tag = "PipelineDecoder"
        ) {
            bitmap.scale(targetWidth, targetHeight)
        }
        if (scaled !== bitmap) {
            bitmap.recycleSafely()
        }
        return scaled
    }

    internal fun clampRect(rect: RectF, width: Int, height: Int): RectF? {
        if (width <= 0 || height <= 0) return null
        val left = rect.left.coerceIn(0f, width.toFloat())
        val top = rect.top.coerceIn(0f, height.toFloat())
        val right = rect.right.coerceIn(0f, width.toFloat())
        val bottom = rect.bottom.coerceIn(0f, height.toFloat())
        if (right - left < 2f || bottom - top < 2f) return null
        return RectF(left, top, right, bottom)
    }
}

internal interface BitmapCropSource : Closeable {
    val width: Int
    val height: Int

    suspend fun decodeRegion(rect: RectF, maxEdge: Int = 2048): Bitmap?
}

private fun RectF.toDecodeRect(bitmapWidth: Int, bitmapHeight: Int): Rect? {
    val left = left.toInt().coerceIn(0, max(0, bitmapWidth - 1))
    val top = top.toInt().coerceIn(0, max(0, bitmapHeight - 1))
    val right = max(left + 1, min(bitmapWidth, right.toInt().coerceAtLeast(1)))
    val bottom = max(top + 1, min(bitmapHeight, bottom.toInt().coerceAtLeast(1)))
    if (right <= left || bottom <= top) return null
    return Rect(left, top, right, bottom)
}
