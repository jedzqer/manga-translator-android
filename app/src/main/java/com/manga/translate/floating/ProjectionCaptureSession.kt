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
import kotlin.coroutines.resume
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
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onProjectionStopped()
        }
    }

    fun isReady(): Boolean {
        return mediaProjection != null && imageReader != null
    }

    fun prepare(
        manager: MediaProjectionManager,
        resultCode: Int,
        data: Intent,
        metrics: DisplayMetrics,
        pixelFormat: Int
    ): Boolean {
        release()
        val projection = manager.getMediaProjection(resultCode, data) ?: return false
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi.coerceAtLeast(1)
        this.pixelFormat = pixelFormat
        val reader = ImageReader.newInstance(width, height, pixelFormat, 2)
        projection.registerCallback(projectionCallback, mainHandler)
        val display = try {
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
        } catch (error: Exception) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                reader.close()
            } catch (_: Exception) {
            }
            try {
                projection.stop()
            } catch (_: Exception) {
            }
            AppLogger.log("FloatingOCR", "Projection virtual display creation failed", error)
            return false
        }
        mediaProjection = projection
        imageReader = reader
        virtualDisplay = display
        AppLogger.log("FloatingOCR", "Projection ready ${width}x${height}@${densityDpi}dpi")
        return true
    }

    /**
     * Rebuilds the [VirtualDisplay] / [ImageReader] pair with the given metrics while
     * keeping the current [MediaProjection] authorization alive. Used after the display
     * size or orientation changed (rotation, split screen, foldables) so captures keep
     * matching the real screen instead of the stale aspect ratio. Serialized with
     * [captureCurrentScreen] through [captureMutex].
     */
    suspend fun reconfigure(metrics: DisplayMetrics): Boolean = captureMutex.withLock {
        val projection = mediaProjection ?: return@withLock false
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
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.let {
                clearImageReaderListener(it)
                it.close()
            }
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        val newReader = ImageReader.newInstance(width, height, pixelFormat, 2)
        val newDisplay = try {
            projection.createVirtualDisplay(
                "floating-ocr-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                null
            )
        } catch (error: Exception) {
            try {
                newReader.close()
            } catch (_: Exception) {
            }
            // The old pipeline is already gone; stop the projection so the host
            // falls back to the not-ready flow instead of capturing stale frames.
            try {
                projection.stop()
            } catch (_: Exception) {
            }
            AppLogger.log("FloatingOCR", "Projection virtual display reconfigure failed", error)
            return@withLock false
        }
        virtualDisplay = newDisplay
        imageReader = newReader
        AppLogger.log("FloatingOCR", "Projection reconfigured ${width}x${height}@${densityDpi}dpi")
        true
    }

    suspend fun captureCurrentScreen(
        timeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS,
        requireFreshFrame: Boolean = false
    ): Bitmap? = captureMutex.withLock {
        val reader = imageReader ?: return@withLock null
        if (!requireFreshFrame) {
            acquireLatestBitmap(reader)?.let { bitmap ->
                AppLogger.log("FloatingOCR", "Captured frame ${bitmap.width}x${bitmap.height} immediately")
                return@withLock bitmap
            }
        } else {
            discardPendingImages(reader)
        }
        val bitmap = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap?> { continuation ->
                val listener = ImageReader.OnImageAvailableListener { availableReader ->
                    val captured = acquireLatestBitmap(availableReader) ?: return@OnImageAvailableListener
                    clearImageReaderListener(availableReader)
                    if (continuation.isActive) {
                        continuation.resume(captured)
                    } else {
                        captured.recycleSafely()
                    }
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
    }

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
        val projection = mediaProjection
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.let {
                clearImageReaderListener(it)
                it.close()
            }
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
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
        fullBitmap.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(fullBitmap, 0, 0, width, height).also {
            fullBitmap.recycleSafely()
        }
    }

    companion object {
        private const val DEFAULT_CAPTURE_TIMEOUT_MS = 400L
    }
}
