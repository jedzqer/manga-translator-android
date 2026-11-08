package com.manga.translate.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.os.Build
import android.os.Build.VERSION_CODES
import com.manga.translate.detection.shouldUseLongImageTiling
import com.manga.translate.platform.ImageProcessingGuards
import kotlin.math.max

data class DecodedReadingBitmap(
    val drawable: ReadingTiledBitmapDrawable,
    val bitmap: Bitmap?,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val isTiled: Boolean
)

object ReadingBitmapDecoder {
    // Keep extra detail for zooming, within whole-image memory and texture limits.
    private const val INITIAL_DETAIL_MULTIPLIER = 3
    private const val MAX_LONG_EDGE = 8192
    private const val MAX_TOTAL_PIXELS = 16_777_216 // ~16MP hard cap for whole-image decode

    suspend fun decode(imageFile: java.io.File, targetWidth: Int, targetHeight: Int): DecodedReadingBitmap? {
        val safeTargetWidth = targetWidth.coerceAtLeast(1)
        val safeTargetHeight = targetHeight.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        if (shouldUseLongImageTiling(sourceWidth, sourceHeight)) {
            val decoder = runCatching { createRegionDecoder(imageFile) }.getOrNull()
            if (decoder != null) {
                val tileSample = calculateTileSampleSize(sourceWidth, safeTargetWidth)
                val displayWidth = ceilDiv(sourceWidth, tileSample)
                val displayHeight = ceilDiv(sourceHeight, tileSample)
                return DecodedReadingBitmap(
                    drawable = ReadingTiledBitmapDrawable.regionBacked(
                        decoder, sourceWidth, sourceHeight, tileSample
                    ),
                    bitmap = null,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight,
                    isTiled = true
                )
            }
        }

        val sampleSize = calculateInSampleSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = safeTargetWidth * INITIAL_DETAIL_MULTIPLIER,
            targetHeight = safeTargetHeight * INITIAL_DETAIL_MULTIPLIER
        )
        val bitmap = decodeWholeImage(imageFile, sourceWidth, sourceHeight, sampleSize)
        if (bitmap != null) {
            return toDecodedReadingBitmap(bitmap, sourceWidth, sourceHeight)
        }
        return null
    }

    /** Decode one bounded webtoon segment; source dimensions always describe the original page. */
    suspend fun decodeRegion(
        imageFile: java.io.File,
        top: Int,
        bottom: Int,
        targetWidth: Int
    ): DecodedReadingBitmap? {
        val decoder = runCatching { createRegionDecoder(imageFile) }.getOrNull() ?: return null
        try {
            val width = decoder.width
            val height = decoder.height
            if (top < 0 || bottom > height || bottom <= top) return null
            val sample = calculateInSampleSize(width, bottom - top,
                targetWidth.coerceAtLeast(1) * INITIAL_DETAIL_MULTIPLIER, MAX_LONG_EDGE)
            val bitmap = ImageProcessingGuards.withDecodePermit(
                ceilDiv(width, sample), ceilDiv(bottom - top, sample), "WebtoonSegment"
            ) {
                runCatching {
                    decoder.decodeRegion(android.graphics.Rect(0, top, width, bottom), BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                }.getOrNull()
            } ?: return null
            return toDecodedReadingBitmap(bitmap, width, height)
        } finally {
            decoder.recycle()
        }
    }

    private suspend fun decodeWholeImage(
        imageFile: java.io.File,
        sourceWidth: Int,
        sourceHeight: Int,
        sampleSize: Int
    ): Bitmap? {
        val decodedWidth = ceilDiv(sourceWidth, sampleSize)
        val decodedHeight = ceilDiv(sourceHeight, sampleSize)
        val preferArgb = ImageProcessingGuards.hasMemoryBudgetForBitmap(
            width = decodedWidth,
            height = decodedHeight,
            copies = 2
        )
        val configs = if (preferArgb) {
            listOf(Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565)
        } else {
            listOf(Bitmap.Config.RGB_565, Bitmap.Config.ARGB_8888)
        }
        for (config in configs) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = config
            }
            val bitmap = ImageProcessingGuards.withDecodePermit(
                width = decodedWidth,
                height = decodedHeight,
                tag = "ReadingDecoder"
            ) {
                runCatching {
                    BitmapFactory.decodeFile(imageFile.absolutePath, options)
                }.getOrNull()
            }
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun toDecodedReadingBitmap(
        bitmap: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int
    ): DecodedReadingBitmap {
        bitmap.density = Bitmap.DENSITY_NONE
        return DecodedReadingBitmap(
            drawable = ReadingTiledBitmapDrawable.single(bitmap),
            bitmap = bitmap,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            displayWidth = bitmap.width,
            displayHeight = bitmap.height,
            isTiled = false
        )
    }

    internal fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        while (
            ceilDiv(sourceWidth, sample) > MAX_LONG_EDGE ||
            ceilDiv(sourceHeight, sample) > MAX_LONG_EDGE
        ) {
            sample *= 2
        }
        while (
            sourceWidth.toLong() * sourceHeight.toLong() / (sample.toLong() * sample.toLong())
            > MAX_TOTAL_PIXELS
        ) {
            sample *= 2
        }
        return max(sample, 1)
    }

    private fun calculateTileSampleSize(sourceWidth: Int, targetWidth: Int): Int {
        val desiredWidth = (targetWidth * INITIAL_DETAIL_MULTIPLIER).coerceAtLeast(1)
        var sample = 1
        while (ceilDiv(sourceWidth, sample * 2) >= desiredWidth) sample *= 2
        return sample
    }

    private fun createRegionDecoder(imageFile: java.io.File): BitmapRegionDecoder {
        return if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(imageFile.absolutePath)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(imageFile.absolutePath, false)
        }
    }

    private fun ceilDiv(value: Int, divisor: Int): Int {
        return ((value.toLong() + divisor - 1) / divisor).toInt()
    }

}
