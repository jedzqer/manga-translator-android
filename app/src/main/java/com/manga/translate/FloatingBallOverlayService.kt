package com.manga.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingBallOverlayService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private lateinit var windowManager: WindowManager
    private var controllerRoot: LinearLayout? = null
    private var controllerLayoutParams: WindowManager.LayoutParams? = null
    private var detectionOverlayView: FloatingDetectionOverlayView? = null
    private var detectionLayoutParams: WindowManager.LayoutParams? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var textDetector: TextDetector? = null
    private var mangaOcr: MangaOcr? = null
    private var llmClient: LlmClient? = null
    private var detectJob: Job? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = 0
    private var bubbleDragEnabled = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            scope.launch(Dispatchers.Main) {
                releaseProjection()
                Toast.makeText(
                    this@FloatingBallOverlayService,
                    R.string.floating_capture_not_ready,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canDrawOverlays()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()
        bubbleDragEnabled = settingsStore.loadFloatingBubbleDragEnabled()
        if (controllerRoot == null) {
            showControllerOverlay()
        }
        if (detectionOverlayView == null) {
            showDetectionOverlay()
        }
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            val data = intent.getParcelableIntentExtraCompat(EXTRA_RESULT_DATA)
            if (resultCode != Int.MIN_VALUE && data != null) {
                prepareProjection(resultCode, data)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        detectJob?.cancel()
        releaseProjection()
        removeOverlay()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun canDrawOverlays(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        return Settings.canDrawOverlays(this)
    }

    private fun ensureForeground() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.floating_service_title))
            .setContentText(getString(R.string.floating_service_message))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfoForegroundTypes.MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showControllerOverlay() {
        val density = resources.displayMetrics.density
        val ballSize = (56f * density).toInt()
        val margin = (8f * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val clearButton = AppCompatButton(this).apply {
            text = getString(R.string.overlay_clear_button)
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = createOverlayActionButtonBackground(density)
            minimumWidth = 0
            minWidth = 0
            setPadding((10f * density).toInt(), 0, (10f * density).toInt(), 0)
            setOnClickListener { detectionOverlayView?.clearDetections() }
        }
        val translateButton = AppCompatButton(this).apply {
            text = getString(R.string.overlay_translate_button)
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = createOverlayActionButtonBackground(density)
            minimumWidth = 0
            minWidth = 0
            setPadding((10f * density).toInt(), 0, (10f * density).toInt(), 0)
            setOnClickListener { runTextDetection() }
        }
        val exitButton = AppCompatButton(this).apply {
            text = getString(R.string.overlay_exit_button)
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = createOverlayActionButtonBackground(density)
            minimumWidth = 0
            minWidth = 0
            setPadding((10f * density).toInt(), 0, (10f * density).toInt(), 0)
            setOnClickListener { stopSelf() }
        }
        val floatingBall = TextView(this).apply {
            text = "译"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3F51B5.toInt())
            }
        }

        root.addView(
            clearButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (28f * density).toInt()
            )
        )
        root.addView(
            translateButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (28f * density).toInt()
            ).apply {
                topMargin = (4f * density).toInt()
            }
        )
        root.addView(
            exitButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (28f * density).toInt()
            ).apply {
                topMargin = (4f * density).toInt()
            }
        )
        val settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (8f * density).toInt(),
                (8f * density).toInt(),
                (8f * density).toInt(),
                (8f * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0xF5FFFFFF.toInt())
                setStroke((1f * density).toInt(), 0x33222222)
            }
            visibility = View.GONE
        }
        val bubbleDragSwitch = SwitchCompat(this).apply {
            text = getString(R.string.overlay_drag_bubble_option)
            // Some SwitchCompat themes enable showText by default; with null textOn/textOff this can crash on measure.
            showText = false
            isChecked = bubbleDragEnabled
            setOnCheckedChangeListener { _, checked ->
                applyBubbleDragEnabled(checked)
            }
        }
        settingsPanel.addView(
            bubbleDragSwitch,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val settingsButton = AppCompatButton(this).apply {
            text = getString(R.string.overlay_settings_button)
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = createOverlayActionButtonBackground(density)
            minimumWidth = 0
            minWidth = 0
            setPadding((10f * density).toInt(), 0, (10f * density).toInt(), 0)
            setOnClickListener {
                settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            }
        }

        root.addView(
            settingsPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4f * density).toInt()
            }
        )
        root.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (28f * density).toInt()
            )
        )
        root.addView(
            floatingBall,
            LinearLayout.LayoutParams(ballSize, ballSize).apply {
                topMargin = margin
            }
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - ballSize - margin).coerceAtLeast(0)
            y = (180f * density).toInt()
        }

        attachDragGesture(floatingBall, params)
        windowManager.addView(root, params)
        controllerRoot = root
        controllerLayoutParams = params
    }

    private fun showDetectionOverlay() {
        val overlay = FloatingDetectionOverlayView(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            buildDetectionFlags(bubbleDragEnabled),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        overlay.setBubbleDragEnabled(bubbleDragEnabled)
        windowManager.addView(overlay, params)
        detectionOverlayView = overlay
        detectionLayoutParams = params
    }

    private fun buildDetectionFlags(dragEnabled: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!dragEnabled) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun createOverlayActionButtonBackground(density: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(0xCC1B1B1B.toInt())
            setStroke((1f * density).toInt(), 0x66FFFFFF)
        }
    }

    private fun applyBubbleDragEnabled(enabled: Boolean) {
        bubbleDragEnabled = enabled
        settingsStore.saveFloatingBubbleDragEnabled(enabled)
        detectionOverlayView?.setBubbleDragEnabled(enabled)
        val params = detectionLayoutParams ?: return
        val newFlags = buildDetectionFlags(enabled)
        if (params.flags == newFlags) return
        params.flags = newFlags
        try {
            windowManager.updateViewLayout(detectionOverlayView, params)
        } catch (_: Exception) {
        }
    }

    private fun prepareProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        val projection = manager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        val metrics = resources.displayMetrics
        captureWidth = metrics.widthPixels.coerceAtLeast(1)
        captureHeight = metrics.heightPixels.coerceAtLeast(1)
        densityDpi = metrics.densityDpi.coerceAtLeast(1)
        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )
        virtualDisplay = projection.createVirtualDisplay(
            "floating-ocr-capture",
            captureWidth,
            captureHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun runTextDetection() {
        if (detectJob?.isActive == true) return
        val projection = mediaProjection
        if (projection == null || imageReader == null) {
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.floating_detecting, Toast.LENGTH_SHORT).show()
        detectJob = scope.launch(Dispatchers.Default) {
            val bitmap = captureCurrentScreen() ?: return@launch
            val detector = textDetector ?: TextDetector(applicationContext).also { textDetector = it }
            val ocr = mangaOcr ?: runCatching {
                MangaOcr(applicationContext)
            }.getOrNull()?.also {
                mangaOcr = it
            }
            val detections = detector.detect(bitmap)
            val deduplicatedRects = RectGeometryDeduplicator.mergeSupplementRects(
                detections,
                bitmap.width,
                bitmap.height
            )
            if (deduplicatedRects.size < detections.size) {
                AppLogger.log(
                    "FloatingOCR",
                    "Deduplicated overlapping detections: ${detections.size} -> ${deduplicatedRects.size}"
                )
            }
            val bubbles = ArrayList<BubbleTranslation>(deduplicatedRects.size)
            for ((index, rect) in deduplicatedRects.withIndex()) {
                val crop = cropBitmap(bitmap, rect)
                if (crop == null) {
                    bubbles.add(
                        BubbleTranslation(
                            id = index,
                            rect = rect,
                            text = "",
                            source = BubbleSource.TEXT_DETECTOR
                        )
                    )
                    continue
                }
                val text = try {
                    ocr?.recognize(crop)?.trim().orEmpty()
                } catch (e: Exception) {
                    AppLogger.log("FloatingOCR", "MangaOCR recognize failed", e)
                    ""
                } finally {
                    crop.recycle()
                }
                bubbles.add(
                    BubbleTranslation(
                        id = index,
                        rect = rect,
                        text = text,
                        source = BubbleSource.TEXT_DETECTOR
                    )
                )
            }
            val translatedBubbles = translateBubblesIfConfigured(bubbles)
            withContext(Dispatchers.Main) {
                detectionOverlayView?.setDetections(bitmap.width, bitmap.height, translatedBubbles)
                Toast.makeText(
                    this@FloatingBallOverlayService,
                    getString(R.string.floating_detected_count, translatedBubbles.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            bitmap.recycle()
        }
    }

    private suspend fun translateBubblesIfConfigured(
        bubbles: List<BubbleTranslation>
    ): List<BubbleTranslation> {
        if (bubbles.isEmpty()) return bubbles
        val translatable = bubbles.filter { it.text.isNotBlank() }
        if (translatable.isEmpty()) return bubbles
        val client = llmClient ?: LlmClient(applicationContext).also { llmClient = it }
        if (!client.isConfigured()) {
            return bubbles
        }
        return try {
            val text = translatable.joinToString("\n") { "<b>${it.text}</b>" }
            val translated = client.translate(
                text = text,
                glossary = emptyMap(),
                promptAsset = FLOAT_PROMPT_ASSET
            ) ?: return bubbles
            val segments = extractTaggedSegments(
                translated.translation,
                translatable.map { it.text }
            )
            val translatedMap = HashMap<Int, String>(translatable.size)
            for (i in translatable.indices) {
                translatedMap[translatable[i].id] = segments.getOrElse(i) { translatable[i].text }
            }
            bubbles.map { bubble ->
                val translatedText = translatedMap[bubble.id]
                if (translatedText.isNullOrBlank()) {
                    bubble
                } else {
                    bubble.copy(text = translatedText)
                }
            }
        } catch (e: Exception) {
            AppLogger.log("FloatingOCR", "LLM translate failed, fallback to OCR text", e)
            bubbles
        }
    }

    private fun captureCurrentScreen(): Bitmap? {
        val reader = imageReader ?: return null
        var image = reader.acquireLatestImage()
        var retry = 0
        while (image == null && retry < 8) {
            Thread.sleep(50)
            image = reader.acquireLatestImage()
            retry++
        }
        image ?: return null
        image.use { frame ->
            val plane = frame.planes.firstOrNull() ?: return null
            val width = frame.width
            val height = frame.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val fullWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            return Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                bitmap.recycle()
            }
        }
    }

    private fun attachDragGesture(
        target: TextView,
        params: WindowManager.LayoutParams
    ) {
        val touchSlop = (3f * resources.displayMetrics.density)
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    params.x = (downX + dx).toInt().coerceAtLeast(0)
                    params.y = (downY + dy).toInt().coerceAtLeast(0)
                    windowManager.updateViewLayout(controllerRoot, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) > touchSlop ||
                        abs(event.rawY - downRawY) > touchSlop
                    moved
                }

                else -> false
            }
        }
    }

    private fun removeOverlay() {
        val root = controllerRoot
        if (root != null) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        val detection = detectionOverlayView
        if (detection != null) {
            try {
                windowManager.removeView(detection)
            } catch (_: Exception) {
            }
        }
        controllerRoot = null
        controllerLayoutParams = null
        detectionOverlayView = null
        detectionLayoutParams = null
    }

    private fun releaseProjection() {
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
            imageReader?.close()
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

    private object ServiceInfoForegroundTypes {
        const val MEDIA_PROJECTION = 0x00000020
    }

    companion object {
        const val ACTION_START = "com.manga.translate.action.FLOATING_START"
        const val ACTION_STOP = "com.manga.translate.action.FLOATING_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "floating_detect_channel"
        private const val NOTIFICATION_ID = 2002
        private const val FLOAT_PROMPT_ASSET = "float_llm_prompts.json"
    }
}

private fun Intent.getParcelableIntentExtraCompat(key: String): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}
