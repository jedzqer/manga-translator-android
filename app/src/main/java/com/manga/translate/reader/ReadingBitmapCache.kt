package com.manga.translate.reader

import android.util.LruCache
import com.manga.translate.platform.recycleSafely
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-scoped cache for whole-page reading bitmaps.
 *
 * A holder cannot recycle a bitmap directly once it is shared with the cache. Leases keep
 * track of active users so an evicted bitmap is recycled only after the last holder releases it.
 */
class ReadingBitmapCache(
    maxSizeKb: Int = defaultMaxSizeKb()
) : AutoCloseable {
    private data class CacheKey(
        val path: String,
        val lastModified: Long,
        val length: Long,
        val variant: String
    )

    private class Entry(
        val decoded: DecodedReadingBitmap
    ) {
        var references: Int = 0
        var evicted: Boolean = false
    }

    class Lease internal constructor(
        val decoded: DecodedReadingBitmap,
        private val releaseBlock: () -> Unit
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                releaseBlock()
            }
        }
    }

    private val lock = Any()
    private val cache = object : LruCache<CacheKey, Entry>(maxSizeKb.coerceAtLeast(1)) {
        override fun sizeOf(key: CacheKey, value: Entry): Int {
            return (value.decoded.bitmap?.byteCount ?: 0) / 1024
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: CacheKey,
            oldValue: Entry,
            newValue: Entry?
        ) {
            oldValue.evicted = true
            recycleIfUnused(oldValue)
        }
    }

    suspend fun acquire(
        imageFile: File,
        variant: String = "",
        decode: suspend () -> DecodedReadingBitmap?
    ): Lease? {
        val key = CacheKey(
            path = imageFile.absolutePath,
            lastModified = imageFile.lastModified(),
            length = imageFile.length(),
            variant = variant
        )
        synchronized(lock) {
            cache.get(key)?.let { entry ->
                entry.references += 1
                return leaseForCachedEntry(key, entry)
            }
        }

        val decoded = decode() ?: return null
        val bitmap = decoded.bitmap
        if (bitmap == null || decoded.isTiled) {
            return Lease(decoded) { decoded.drawable.close() }
        }

        synchronized(lock) {
            // Another page load may have populated the same entry while this decode was running.
            // Prefer the existing entry and release the duplicate bitmap immediately.
            cache.get(key)?.let { existing ->
                bitmap.recycleSafely()
                existing.references += 1
                return leaseForCachedEntry(key, existing)
            }
            val entry = Entry(decoded).also { it.references = 1 }
            cache.put(key, entry)
            return leaseForCachedEntry(key, entry)
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.evictAll()
        }
    }

    fun retainPaths(activePaths: Set<String>) {
        synchronized(lock) {
            cache.snapshot().keys
                .filter { it.path !in activePaths }
                .forEach(cache::remove)
        }
    }

    override fun close() = clear()

    private fun leaseForCachedEntry(key: CacheKey, entry: Entry): Lease {
        val bitmap = requireNotNull(entry.decoded.bitmap)
        val decoded = entry.decoded.copy(
            drawable = ReadingTiledBitmapDrawable.single(bitmap)
        )
        return Lease(decoded) {
            synchronized(lock) {
                entry.references = (entry.references - 1).coerceAtLeast(0)
                recycleIfUnused(entry)
                // Keep the access order correct only while the entry is still cached.
                if (!entry.evicted) cache.get(key)
            }
        }
    }

    private fun recycleIfUnused(entry: Entry) {
        if (entry.evicted && entry.references == 0) {
            // RenderThread may still reference a bitmap after the last View detaches.
            // Dropping the cache entry lets Android release it when rendering is finished.
        }
    }

    private companion object {
        fun defaultMaxSizeKb(): Int {
            val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L)
                .coerceAtLeast(1L)
            // Keep enough room for one or two useful pages within the reading memory budget.
            return (maxMemoryKb / 10L).toInt().coerceIn(16 * 1024, 64 * 1024)
        }
    }
}
