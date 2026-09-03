package com.manga.translate.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
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
    val isTiled: Boolean,
    val regionSource: ReadingRegionImageSource? = null
)

data class ReadingRegionImageSource(
    val imageFile: java.io.File,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /**
     * Coordinate-system sample for ImageView display space.
     * Tiled path prefers 1 so zoom can request full-resolution tiles.
     */
    val layoutSampleSize: Int
)

internal data class ReadingSourceTile(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val height: Int
        get() = bottom - top

    fun toRect(): Rect = Rect(left, top, right, bottom)
}

object ReadingBitmapDecoder {
    // Keep extra detail for the first paint. Tiled paths can request sharper data later,
    // but whole-image pages have no later refinement pass once their bitmap is displayed.
    private const val INITIAL_DETAIL_MULTIPLIER = 3
    private const val MAX_LONG_EDGE = 8192
    private const val MAX_FULL_DECODE_PIXELS = 12_000_000
    private const val MAX_TOTAL_PIXELS = 16_777_216 // ~16MP hard cap for whole-image decode
    private const val TILE_DECODE_MIN_SOURCE_HEIGHT = 6144
    private const val TILE_OUTPUT_PIXEL_BUDGET = 4_194_304 // ~4MP per tile plan unit
    private const val TILE_DETAIL_MULTIPLIER = 1.5f

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

        if (shouldUseTiledDecode(sourceWidth, sourceHeight) && canOpenRegionDecoder(imageFile)) {
            return DecodedReadingBitmap(
                drawable = ReadingTiledBitmapDrawable.empty(sourceWidth, sourceHeight),
                bitmap = null,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                displayWidth = sourceWidth,
                displayHeight = sourceHeight,
                isTiled = true,
                regionSource = ReadingRegionImageSource(
                    imageFile = imageFile,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    layoutSampleSize = 1
                )
            )
        }

        val sampleSize = calculateInSampleSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = safeTargetWidth * INITIAL_DETAIL_MULTIPLIER,
            targetHeight = safeTargetHeight * INITIAL_DETAIL_MULTIPLIER
        )
        val bitmap = decodeWholeImage(imageFile, sourceWidth, sourceHeight, sampleSize) ?: return null
        return toDecodedReadingBitmap(
            bitmap = bitmap,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
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
        val preserveReadableWidth = shouldUseLongImageTiling(sourceWidth, sourceHeight)
        val minReadableWidth = (targetWidth / INITIAL_DETAIL_MULTIPLIER).coerceAtLeast(1)
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        while (
            sourceWidth / (sample * 2) >= MAX_LONG_EDGE ||
            sourceHeight / (sample * 2) >= MAX_LONG_EDGE
        ) {
            if (preserveReadableWidth && sourceWidth / (sample * 2) < minReadableWidth) {
                break
            }
            sample *= 2
        }
        while (
            sourceWidth.toLong() * sourceHeight.toLong() / (sample.toLong() * sample.toLong())
            > MAX_TOTAL_PIXELS
        ) {
            if (preserveReadableWidth && sourceWidth / (sample * 2) < minReadableWidth) {
                break
            }
            sample *= 2
        }
        return max(sample, 1)
    }

    /**
     * Decode sample for region tiles with modest detail headroom under current zoom.
     * layout maps source -> display by layoutSampleSize; displayScale is screen px per display px.
     */
    fun calculateDecodeSampleSize(layoutSampleSize: Int, displayScale: Float): Int {
        val safeLayout = layoutSampleSize.coerceAtLeast(1)
        val scale = displayScale.coerceAtLeast(0.05f)
        val target = safeLayout / (scale * TILE_DETAIL_MULTIPLIER)
        var sample = 1
        while (sample * 2 <= target + 0.001f) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    internal fun planSourceTiles(
        sourceWidth: Int,
        sourceHeight: Int,
        sampleSize: Int
    ): List<ReadingSourceTile> {
        if (sourceWidth <= 0 || sourceHeight <= 0 || sampleSize <= 0) return emptyList()
        val sourceTileHeight = computeSourceTileHeight(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sampleSize = sampleSize
        )
        val tiles = ArrayList<ReadingSourceTile>()
        var sourceTop = 0
        while (sourceTop < sourceHeight) {
            val sourceBottom = minOf(sourceTop + sourceTileHeight, sourceHeight)
            tiles += ReadingSourceTile(
                left = 0,
                top = sourceTop,
                right = sourceWidth,
                bottom = sourceBottom
            )
            sourceTop = sourceBottom
        }
        return tiles
    }

    internal fun shouldUseTiledDecode(sourceWidth: Int, sourceHeight: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        if (shouldUseLongImageTiling(sourceWidth, sourceHeight)) return true
        if (sourceHeight >= TILE_DECODE_MIN_SOURCE_HEIGHT) return true
        if (max(sourceWidth, sourceHeight) > MAX_LONG_EDGE) return true
        val pixels = sourceWidth.toLong() * sourceHeight.toLong()
        return pixels > MAX_FULL_DECODE_PIXELS
    }

    private fun canOpenRegionDecoder(imageFile: java.io.File): Boolean {
        return runCatching {
            createBitmapRegionDecoder(imageFile).use { true }
        }.getOrDefault(false)
    }

    private fun computeSourceTileHeight(
        sourceWidth: Int,
        sourceHeight: Int,
        sampleSize: Int
    ): Int {
        val safeWidth = sourceWidth.coerceAtLeast(1)
        val sampledBudget = TILE_OUTPUT_PIXEL_BUDGET.toLong() * sampleSize.toLong() * sampleSize.toLong()
        val rawHeight = (sampledBudget / safeWidth).toInt()
        val roundedHeight = (rawHeight / sampleSize).coerceAtLeast(1) * sampleSize
        return roundedHeight.coerceAtLeast(sampleSize * 256).coerceAtMost(sourceHeight)
    }

    private fun createBitmapRegionDecoder(imageFile: java.io.File): BitmapRegionDecoder {
        val path = imageFile.absolutePath
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(path, false)
        }
    }

    private fun ceilDiv(value: Int, divisor: Int): Int {
        return (value + divisor - 1) / divisor
    }

    private inline fun <T> BitmapRegionDecoder.use(block: (BitmapRegionDecoder) -> T): T {
        try {
            return block(this)
        } finally {
            if (!isRecycled) recycle()
        }
    }
}
