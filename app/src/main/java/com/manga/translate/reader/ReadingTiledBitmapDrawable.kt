package com.manga.translate.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.graphics.withTranslation
import com.manga.translate.platform.ImageProcessingGuards
import com.manga.translate.platform.recycleSafely
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

data class ReadingBitmapTile(
    val bitmap: Bitmap,
    val top: Int
)

class ReadingTiledBitmapDrawable private constructor(
    private val tiles: List<ReadingBitmapTile>,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val source: TileSource? = null
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val destRect = RectF()
    private val tileLock = Any()
    private val tileCache = object : LruCache<Long, Bitmap>(TILE_CACHE_KB) {
        override fun sizeOf(key: Long, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
            // Hardware display lists may still retain this bitmap; let GC release it.
        }
    }
    private val pendingTiles = mutableSetOf<Long>()
    private val failedTiles = mutableSetOf<Long>()
    private val scope = CoroutineScope(SupervisorJob() + tileDispatcher)
    private var closed = false

    override fun draw(canvas: Canvas) {
        if (imageWidth <= 0 || imageHeight <= 0 || (source == null && tiles.isEmpty())) return
        val bounds = bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        canvas.withTranslation(bounds.left.toFloat(), bounds.top.toFloat()) {
            scale(
                bounds.width() / imageWidth.toFloat(),
                bounds.height() / imageHeight.toFloat()
            )
            if (source == null) {
                for (tile in tiles) {
                    val bitmap = tile.bitmap
                    if (bitmap.isRecycled) continue
                    destRect.set(0f, tile.top.toFloat(), bitmap.width.toFloat(), (tile.top + bitmap.height).toFloat())
                    drawBitmap(bitmap, null, destRect, paint)
                }
            } else {
                drawVisibleTiles(this)
            }
        }
    }

    override fun getIntrinsicWidth(): Int = imageWidth

    override fun getIntrinsicHeight(): Int = imageHeight

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (source == null) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

    fun close() {
        synchronized(tileLock) {
            if (closed) return
            closed = true
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            tileCache.evictAll()
            source?.close()
        }
    }

    private fun drawVisibleTiles(canvas: Canvas) {
        val tileSource = source ?: return
        val clip = Rect()
        if (!canvas.getClipBounds(clip)) return
        val left = clip.left.coerceAtLeast(0)
        val top = clip.top.coerceAtLeast(0)
        val right = clip.right.coerceAtMost(imageWidth)
        val bottom = clip.bottom.coerceAtMost(imageHeight)
        if (right <= left || bottom <= top) return
        val firstColumn = left / tileSource.displayTileSize
        val lastColumn = (right - 1) / tileSource.displayTileSize
        val firstRow = top / tileSource.displayTileSize
        val lastRow = (bottom - 1) / tileSource.displayTileSize
        val keys = ArrayList<Long>()
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) keys += tileKey(row, column)
        }
        // Decode the visible area first, then one tile beyond each vertical edge for smooth scrolling.
        if (firstRow > 0) for (column in firstColumn..lastColumn) keys += tileKey(firstRow - 1, column)
        val rowCount = ceil(imageHeight.toDouble() / tileSource.displayTileSize).toInt()
        if (lastRow + 1 < rowCount) for (column in firstColumn..lastColumn) keys += tileKey(lastRow + 1, column)
        synchronized(tileLock) {
            if (closed) return
            for (key in keys.distinct()) {
                val row = (key ushr 32).toInt()
                val column = key.toInt()
                val bitmap = tileCache.get(key)
                if (bitmap == null) {
                    requestTile(row, column)
                } else if (!bitmap.isRecycled) {
                    val x = column * tileSource.displayTileSize
                    val y = row * tileSource.displayTileSize
                    destRect.set(
                        x.toFloat(),
                        y.toFloat(),
                        (x + bitmap.width).toFloat(),
                        (y + bitmap.height).toFloat()
                    )
                    canvas.drawBitmap(bitmap, null, destRect, paint)
                }
            }
        }
    }

    private fun requestTile(row: Int, column: Int) {
        val tileSource = source ?: return
        val key = tileKey(row, column)
        if (key in pendingTiles || key in failedTiles || closed) return
        pendingTiles += key
        scope.launch {
            val bitmap = tileSource.decode(row, column)
            synchronized(tileLock) {
                pendingTiles -= key
                if (closed) bitmap.recycleSafely()
                else if (bitmap != null) tileCache.put(key, bitmap)
                else failedTiles += key
            }
            mainHandler.post {
                synchronized(tileLock) {
                    if (!closed) invalidateSelf()
                }
            }
        }
    }

    private fun tileKey(row: Int, column: Int): Long {
        return (row.toLong() shl 32) or (column.toLong() and 0xffffffffL)
    }

    private class TileSource(
        private val decoder: BitmapRegionDecoder,
        private val width: Int,
        private val height: Int,
        private val sampleSize: Int
    ) {
        val displayTileSize = TILE_SOURCE_SIZE / sampleSize

        suspend fun decode(row: Int, column: Int): Bitmap? {
            val left = column * TILE_SOURCE_SIZE
            val top = row * TILE_SOURCE_SIZE
            val rect = Rect(
                left,
                top,
                (left + TILE_SOURCE_SIZE).coerceAtMost(width),
                (top + TILE_SOURCE_SIZE).coerceAtMost(height)
            )
            if (rect.isEmpty) return null
            val outWidth = ceil(rect.width().toDouble() / sampleSize).toInt()
            val outHeight = ceil(rect.height().toDouble() / sampleSize).toInt()
            return ImageProcessingGuards.withDecodePermit(outWidth, outHeight, "ReadingTile") {
                runCatching {
                    synchronized(decoder) {
                        if (decoder.isRecycled) return@synchronized null
                        decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        })?.apply { density = Bitmap.DENSITY_NONE }
                    }
                }.getOrNull()
            }
        }

        fun close() {
            synchronized(decoder) {
                if (!decoder.isRecycled) decoder.recycle()
            }
        }
    }

    companion object {
        fun empty(width: Int, height: Int): ReadingTiledBitmapDrawable {
            return ReadingTiledBitmapDrawable(
                tiles = emptyList(),
                imageWidth = width.coerceAtLeast(1),
                imageHeight = height.coerceAtLeast(1)
            )
        }

        fun single(bitmap: Bitmap): ReadingTiledBitmapDrawable {
            return ReadingTiledBitmapDrawable(
                tiles = listOf(ReadingBitmapTile(bitmap = bitmap, top = 0)),
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        }

        fun regionBacked(
            decoder: BitmapRegionDecoder,
            width: Int,
            height: Int,
            sampleSize: Int
        ): ReadingTiledBitmapDrawable {
            val displayWidth = ceil(width.toDouble() / sampleSize).toInt().coerceAtLeast(1)
            val displayHeight = ceil(height.toDouble() / sampleSize).toInt().coerceAtLeast(1)
            return ReadingTiledBitmapDrawable(
                emptyList(), displayWidth, displayHeight,
                TileSource(decoder, width, height, sampleSize)
            )
        }

        private const val TILE_SOURCE_SIZE = 1024
        private val TILE_CACHE_KB = (Runtime.getRuntime().maxMemory() / 1024L / 12L)
            .toInt().coerceIn(32 * 1024, 96 * 1024)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val tileDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ReadingTileDecoder").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
