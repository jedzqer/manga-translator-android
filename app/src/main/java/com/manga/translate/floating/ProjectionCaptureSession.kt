package com.manga.translate.floating

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.graphics.createBitmap
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.recycleSafely
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class ProjectionCaptureSession(
    context: Context,
    private val onProjectionStopped: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureMutex = Mutex()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var pixelFormat: Int = PixelFormat.RGBA_8888
    private var projectionCallback: MediaProjection.Callback? = null
    private var pendingCapture: CancellableContinuation<Bitmap?>? = null
    internal enum class State { CLOSED, PREPARING, READY, CAPTURING, RESIZING }
    var state: State = State.CLOSED
        private set
    private var generation = 0L

    fun isReady(): Boolean {
        return state == State.READY || state == State.CAPTURING
    }

    fun prepare(
        manager: MediaProjectionManager,
        resultCode: Int,
        data: Intent,
        metrics: DisplayMetrics,
        pixelFormat: Int
    ): Boolean {
        release()
        state = State.PREPARING
        val token = generation
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (error: Exception) {
            AppLogger.log("FloatingOCR", "Projection authorization failed", error)
            null
        }
        if (projection == null) {
            release()
            return false
        }
        mediaProjection = projection
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi.coerceAtLeast(1)
        this.pixelFormat = pixelFormat
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (generation != token || mediaProjection !== projection) return
                release()
                onProjectionStopped()
            }
        }
        projectionCallback = callback
        try {
            projection.registerCallback(callback, mainHandler)
            val reader = ImageReader.newInstance(width, height, pixelFormat, 2)
            imageReader = reader
            virtualDisplay =
            projection.createVirtualDisplay(
                "floating-ocr-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            checkNotNull(virtualDisplay)
        } catch (error: Exception) {
            release()
            AppLogger.log("FloatingOCR", "Projection virtual display creation failed", error)
            return false
        }
        state = State.READY
        AppLogger.log("FloatingOCR", "Projection ready ${width}x${height}@${densityDpi}dpi")
        return true
    }

    /**
     * Resizes the existing [VirtualDisplay] and replaces its [ImageReader] while
     * keeping the current [MediaProjection] authorization alive. Used after the display
     * size or orientation changed (rotation, split screen, foldables) so captures keep
     * matching the real screen instead of the stale aspect ratio. Serialized with
     * [captureCurrentScreen] through [captureMutex].
     */
    suspend fun reconfigure(metrics: DisplayMetrics): Boolean = withContext(Dispatchers.Main.immediate) {
      captureMutex.withLock {
        val display = virtualDisplay ?: return@withLock false
        val reader = imageReader
        if (reader != null &&
            reader.width == metrics.widthPixels &&
            reader.height == metrics.heightPixels
        ) {
            return@withLock true
        }
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi.coerceAtLeast(1)
        state = State.RESIZING
        try {
            // Android 14+ permits only one createVirtualDisplay call per grant.
            display.surface = null
            reader?.let { clearImageReaderListener(it); it.close() }
            imageReader = null
            val newReader = ImageReader.newInstance(width, height, pixelFormat, 2)
            imageReader = newReader
            display.resize(width, height, densityDpi)
            display.surface = newReader.surface
        } catch (error: Exception) {
            release()
            AppLogger.log("FloatingOCR", "Projection virtual display reconfigure failed", error)
            return@withLock false
        }
        state = State.READY
        AppLogger.log("FloatingOCR", "Projection reconfigured ${width}x${height}@${densityDpi}dpi")
        true
      }
    }

    suspend fun captureCurrentScreen(
        timeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS,
        requireFreshFrame: Boolean = false,
        prepareFreshFrame: (suspend () -> Unit)? = null
    ): Bitmap? = withContext(Dispatchers.Main.immediate) { captureMutex.withLock {
        val reader = imageReader ?: return@withLock null
        val token = generation
        state = State.CAPTURING
        try {
        if (!requireFreshFrame) {
            acquireLatestBitmap(reader)?.let { bitmap ->
                AppLogger.log("FloatingOCR", "Captured frame ${bitmap.width}x${bitmap.height} immediately")
                return@withLock bitmap
            }
        } else {
            discardPendingImages(reader)
            // Drain before changing overlay visibility; keep the resulting frame even
            // when a static screen produces no further updates.
            if (prepareFreshFrame != null) {
                prepareFreshFrame()
                if (token != generation) return@withLock null
                acquireLatestBitmap(reader)?.let { return@withLock it }
            }
        }
        val bitmap = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap?> { continuation ->
                pendingCapture = continuation
                val listener = ImageReader.OnImageAvailableListener { availableReader ->
                    if (token != generation || !continuation.isActive) return@OnImageAvailableListener
                    val captured = acquireLatestBitmap(availableReader) ?: return@OnImageAvailableListener
                    clearImageReaderListener(availableReader)
                    pendingCapture = null
                    continuation.resume(captured) { _, value, _ -> value?.recycleSafely() }
                }
                reader.setOnImageAvailableListener(listener, mainHandler)
                continuation.invokeOnCancellation {
                    clearImageReaderListener(reader)
                }
            }
        }
        clearImageReaderListener(reader)
        if (bitmap == null) {
            AppLogger.log("FloatingOCR", "Capture frame timeout after ${timeoutMs}ms")
        } else {
            AppLogger.log("FloatingOCR", "Captured frame ${bitmap.width}x${bitmap.height} after wait")
        }
        return@withLock bitmap
        } finally {
            clearImageReaderListener(reader)
            if (token == generation) {
                pendingCapture = null
                state = State.READY
            }
        }
    } }

    private fun discardPendingImages(reader: ImageReader) {
        while (true) {
            val image = try {
                reader.acquireLatestImage()
            } catch (_: Exception) {
                null
            } ?: break
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun clearImageReaderListener(reader: ImageReader) {
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
            // The projection may close the reader concurrently with cancellation.
        }
    }

    fun release() {
        // Invalidate callbacks and detach ownership before invoking platform cleanup.
        generation++
        state = State.CLOSED
        val projection = mediaProjection
        val callback = projectionCallback
        val display = virtualDisplay
        val reader = imageReader
        mediaProjection = null
        projectionCallback = null
        virtualDisplay = null
        imageReader = null
        pendingCapture?.cancel()
        pendingCapture = null
        reader?.let(::clearImageReaderListener)
        if (projection != null && callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        try {
            display?.release()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
    }

    private fun acquireLatestBitmap(reader: ImageReader): Bitmap? {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null
        return image.use(::imageToBitmap)
    }

    private fun imageToBitmap(frame: Image): Bitmap? {
        val plane = frame.planes.firstOrNull() ?: return null
        val width = frame.width
        val height = frame.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val fullWidth = width + rowPadding / pixelStride
        val fullBitmap = createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
        try {
            fullBitmap.copyPixelsFromBuffer(plane.buffer)
            if (fullWidth == width) return fullBitmap
            return Bitmap.createBitmap(fullBitmap, 0, 0, width, height).also {
                fullBitmap.recycleSafely()
            }
        } catch (error: Throwable) {
            fullBitmap.recycleSafely()
            throw error
        }
    }

    companion object {
        private const val DEFAULT_CAPTURE_TIMEOUT_MS = 400L
    }
}
