package com.manga.translate.floating

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import com.manga.translate.R
import com.manga.translate.detection.PageRegion
import com.manga.translate.detection.PageRegionDetector
import com.manga.translate.detection.mapPageLineRectsToCrop
import com.manga.translate.di.appContainer
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.FloatingBallGestureAction
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.textOrEmpty
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.ErrorDialogFormatter
import com.manga.translate.platform.createAlertDialogBuilder
import com.manga.translate.platform.createWithScrollableMessage
import com.manga.translate.platform.showModelErrorDialog
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class FloatingBallOverlayService : Service() {
    private class FloatingBallView(context: android.content.Context) : AppCompatTextView(context) {
        var onPerformClick: (() -> Unit)? = null

        override fun performClick(): Boolean {
            onPerformClick?.invoke()
            return super.performClick()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val appContainer by lazy(LazyThreadSafetyMode.NONE) { applicationContext.appContainer }
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { appContainer.settingsStore }
    private val floatingTranslationCacheStore by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.floatingTranslationCacheStore
    }
    private val emptyBubbleCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.createFloatingEmptyBubbleCoordinator()
    }
    private val floatingBubbleTranslationCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.createFloatingBubbleTranslationCoordinator()
    }
    private val bubbleTextRecognizer by lazy(LazyThreadSafetyMode.NONE) {
        appContainer.bubbleTextRecognizer
    }
    private val llmClient by lazy(LazyThreadSafetyMode.NONE) { appContainer.llmClient }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var controllerRoot: LinearLayout? = null
    private var controllerLayoutParams: WindowManager.LayoutParams? = null
    private var controllerMenuPanel: LinearLayout? = null
    private var controllerBallView: View? = null
    private var detectionOverlayView: FloatingDetectionOverlayView? = null
    private var detectionLayoutParams: WindowManager.LayoutParams? = null
    private val screenCaptureSession by lazy {
        ProjectionCaptureSession(applicationContext) {
            scope.launch(Dispatchers.Main) {
                clearCurrentSession()
                releaseProjection()
                Toast.makeText(
                    this@FloatingBallOverlayService,
                    R.string.floating_capture_not_ready,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    private var pageRegionDetector: PageRegionDetector? = null
    private val displayManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(DisplayManager::class.java)
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handleDisplayMetricsChange()
            }
        }
    }
    @Volatile
    private var lastKnownDisplayMetrics: DisplayMetrics? = null
    private var detectJob: Job? = null
    @Volatile
    private var detectionGeneration: Long = 0L
    private var editModeToggleButton: AppCompatButton? = null
    private var swipeTranslateButton: AppCompatButton? = null
    private var addBubbleButton: AppCompatButton? = null
    private var confirmEditButton: AppCompatButton? = null
    private var cancelEditButton: AppCompatButton? = null
    private var progressStatusView: TextView? = null
    private var currentSession: TranslationResult? = null
    private val bitmapController = FloatingBitmapController()
    private val editConfirmCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        EditConfirmCoordinator(
            emptyBubbleCoordinator = emptyBubbleCoordinator,
            settingsStore = settingsStore,
            localModelMemoryManager = appContainer.localModelMemoryManager,
            retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
            floatPromptAsset = FLOAT_PROMPT_ASSET,
            floatVlPromptAsset = FLOAT_VL_PROMPT_ASSET,
            maxVlConcurrency = MAX_FLOATING_TASK_CONCURRENCY
        )
    }
    private var autoCloseCheckJob: Job? = null
    private var blankBubbleErrorDialog: AlertDialog? = null
    private var localModelReleaseCallback: AutoCloseable? = null
    private var activeTranslationLanguage: TranslationLanguage? = null
    private val hideProgressStatusRunnable = Runnable {
        progressStatusView?.visibility = View.GONE
    }
    private val autoCloseCheckRunnable = object : Runnable {
        override fun run() {
            if (!shouldRunAutoCloseDetection()) return
            if (autoCloseCheckJob?.isActive == true) {
                mainHandler.postDelayed(this, AUTO_CLOSE_SCREEN_CHECK_INTERVAL_MS)
                return
            }
            autoCloseCheckJob = scope.launch(Dispatchers.Default) {
                try {
                    val changed = detectScreenChangeAgainstReference()
                    withContext(Dispatchers.Main) {
                        if (changed) {
                            AppLogger.log("FloatingOCR", "Auto close triggered by screen change")
                            clearCurrentSession()
                        } else {
                            scheduleNextAutoCloseCheck()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.log("FloatingOCR", "Auto close screen check failed", e)
                    withContext(Dispatchers.Main) {
                        scheduleNextAutoCloseCheck()
                    }
                }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        localModelReleaseCallback = appContainer.localModelMemoryManager.registerReleaseCallback {
            pageRegionDetector?.releaseLoadedDetectors()
            pageRegionDetector = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.log("FloatingOCR", "Service onStartCommand action=${intent?.action ?: "null"}")
        if (intent?.action == ACTION_STOP) {
            AppLogger.log("FloatingOCR", "Received stop action")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canDrawOverlays()) {
            AppLogger.log("FloatingOCR", "Overlay permission missing, stop service")
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action
        if (action != ACTION_START && !screenCaptureSession.isReady()) {
            AppLogger.log("FloatingOCR", "Reject sticky restart without projection state")
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()
        ensureWindowManager()
        if (detectionOverlayView == null) {
            showDetectionOverlay()
        }
        if (controllerRoot == null) {
            showControllerOverlay()
        }
        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
            val data = intent.getParcelableIntentExtraCompat(EXTRA_RESULT_DATA)
            activeTranslationLanguage = intent.getStringExtra(EXTRA_LANGUAGE)?.let {
                TranslationLanguage.fromPref(it)
            } ?: TranslationLanguage.resolveForOcr(
                TranslationLanguage.JA_TO_ZH,
                settingsStore.loadOcrApiSettings().useLocalOcr
            )
            if (resultCode != Int.MIN_VALUE && data != null) {
                AppLogger.log("FloatingOCR", "Prepare projection from start intent")
                prepareProjection(resultCode, data)
            } else {
                AppLogger.log("FloatingOCR", "Start intent missing projection extras")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        detectJob?.cancel()
        autoCloseCheckJob?.cancel()
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        mainHandler.removeCallbacks(hideProgressStatusRunnable)
        blankBubbleErrorDialog?.dismiss()
        blankBubbleErrorDialog = null
        localModelReleaseCallback?.close()
        localModelReleaseCallback = null
        clearCurrentSession()
        releaseProjection()
        removeOverlay()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun recognizeFloatingBubbleText(
        crop: Bitmap,
        language: TranslationLanguage,
        bubbleSource: BubbleSource,
        detectedLineRects: List<RectF>? = null
    ): String = withContext(Dispatchers.Default) {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        val resolvedLanguage = TranslationLanguage.resolveForOcr(language, ocrSettings.useLocalOcr)
        bubbleTextRecognizer.recognizeCrop(
            crop = crop,
            language = resolvedLanguage,
            useLocalOcr = ocrSettings.useLocalOcr && resolvedLanguage.supportsLocalOcr(),
            logTag = "FloatingOCR",
            bubbleSource = bubbleSource,
            detectedLineRects = detectedLineRects
        ).textOrEmpty()
    }

    private fun currentTranslationLanguage(): TranslationLanguage {
        return activeTranslationLanguage ?: TranslationLanguage.resolveForOcr(
            TranslationLanguage.JA_TO_ZH,
            settingsStore.loadOcrApiSettings().useLocalOcr
        )
    }

    private fun advanceDetectionGeneration(): Long {
        detectJob?.cancel()
        editConfirmCoordinator.acknowledgeConfirmEnded()
        detectionGeneration += 1
        return detectionGeneration
    }

    private fun isDetectionGenerationCurrent(generation: Long): Boolean {
        return detectionGeneration == generation
    }

    private suspend fun runOnMainForDetectionGeneration(
        generation: Long,
        block: () -> Unit
    ) {
        withContext(Dispatchers.Main) {
            if (isDetectionGenerationCurrent(generation)) {
                block()
            }
        }
    }

    private fun canDrawOverlays(): Boolean {
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showControllerOverlay() {
        ensureWindowManager()
        val density = resources.displayMetrics.density
        val ballSize = (56f * density).toInt()
        val margin = (8f * density).toInt()
        val menuButtonWidth = (156f * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val progressView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(
                (10f * density).toInt(),
                (6f * density).toInt(),
                (10f * density).toInt(),
                (6f * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0xCC1B1B1B.toInt())
                setStroke((1f * density).toInt(), 0x44FFFFFF)
            }
            visibility = View.GONE
        }
        val floatingBall = FloatingBallView(this).apply {
            text = "译"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setShadowLayer(4f * density, 0f, 1f * density, 0x66000000)
            background = createFloatingBallBackground(pressed = false)
            elevation = 10f * density
            setPadding(
                (10f * density).toInt(),
                (10f * density).toInt(),
                (10f * density).toInt(),
                (10f * density).toInt()
            )
            contentDescription = getString(R.string.floating_service_message)
        }

        root.addView(
            progressView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4f * density).toInt()
            }
        )
        val menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        val editButton = createMenuButton().apply {
            setOnClickListener { toggleEditMode() }
        }
        val swipeTranslateMenuButton = createMenuButton().apply {
            text = getString(R.string.overlay_swipe_translate_button)
            setOnClickListener {
                controllerMenuPanel?.let { setMenuVisibility(it, false) }
                startSwipeTranslateMode()
            }
        }
        val addButton = createMenuButton().apply {
            text = getString(R.string.overlay_add_bubble_button)
            setOnClickListener { toggleCreateBubbleMode() }
        }
        val confirmButton = createMenuButton().apply {
            text = getString(R.string.overlay_confirm_button)
            setOnClickListener {
                controllerMenuPanel?.let { setMenuVisibility(it, false) }
                confirmEditSession()
            }
        }
        val cancelButton = createMenuButton().apply {
            text = getString(R.string.overlay_cancel_button)
            setOnClickListener {
                controllerMenuPanel?.let { setMenuVisibility(it, false) }
                cancelEditSession()
            }
        }
        val exitButton = createMenuButton().apply {
            text = getString(R.string.overlay_exit_button)
            setOnClickListener { stopSelf() }
        }
        editModeToggleButton = editButton
        swipeTranslateButton = swipeTranslateMenuButton
        addBubbleButton = addButton
        confirmEditButton = confirmButton
        cancelEditButton = cancelButton
        updateEditModeToggleButton()
        updateEditButtons()
        menuPanel.addView(
            editButton,
            createMenuButtonLayoutParams(menuButtonWidth)
        )
        menuPanel.addView(
            swipeTranslateMenuButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )
        menuPanel.addView(
            addButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )
        menuPanel.addView(
            confirmButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )
        menuPanel.addView(
            cancelButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )
        menuPanel.addView(
            exitButton,
            createMenuButtonLayoutParams(menuButtonWidth, topMargin = 6f * density)
        )

        root.addView(
            menuPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4f * density).toInt()
            }
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

        attachBallGesture(floatingBall, menuPanel, params)
        windowManager.addView(root, params)
        AppLogger.log("FloatingOCR", "Controller overlay added")
        controllerRoot = root
        controllerLayoutParams = params
        controllerMenuPanel = menuPanel
        controllerBallView = floatingBall
        progressStatusView = progressView
    }

    private fun showDetectionOverlay() {
        ensureWindowManager()
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
            buildDetectionFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        overlay.setFloatingBubbleRenderSettings(settingsStore.loadFloatingBubbleRenderSettings())
        overlay.setEditMode(editConfirmCoordinator.isEditing)
        overlay.setCreateBubbleMode(editConfirmCoordinator.createBubbleModeEnabled)
        overlay.onBubblesChanged = { bubbles ->
            val session = currentSession
            if (session != null) {
                currentSession = session.copy(bubbles = bubbles)
            }
        }
        overlay.onBubbleDelete = { bubbleId ->
            val session = currentSession
            if (session != null) {
                currentSession = session.copy(bubbles = session.bubbles.filterNot { it.id == bubbleId })
                syncOverlaySession()
            }
        }
        overlay.onManualBubbleCreated = { rect ->
            appendManualBubble(rect)
        }
        overlay.onEditDirtyChanged = { dirty ->
            editConfirmCoordinator.setDirty(dirty)
            updateEditButtons()
        }
        overlay.onCreateBubbleTouchActiveChanged = { active ->
            setFloatingBallHidden(active)
        }
        windowManager.addView(overlay, params)
        AppLogger.log("FloatingOCR", "Detection overlay added")
        detectionOverlayView = overlay
        detectionLayoutParams = params
        syncOverlaySession()
    }

    private fun ensureWindowManager() {
        if (!this::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
    }

    private fun buildDetectionFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!editConfirmCoordinator.isEditing) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }

    private fun showProgressStatus(messageResId: Int, autoHide: Boolean = false) {
        showProgressStatus(getString(messageResId), autoHide)
    }

    private fun showProgressStatus(message: String, autoHide: Boolean = false) {
        val statusView = progressStatusView ?: return
        mainHandler.removeCallbacks(hideProgressStatusRunnable)
        statusView.text = message
        statusView.visibility = View.VISIBLE
        if (autoHide) {
            mainHandler.postDelayed(hideProgressStatusRunnable, FLOATING_PROGRESS_HIDE_DELAY_MS)
        }
    }

    private fun setFloatingBallHidden(hidden: Boolean) {
        controllerBallView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (hidden) {
            controllerMenuPanel?.visibility = View.GONE
        }
    }

    private fun ensureControllerOnTop() {
        val root = controllerRoot ?: return
        val params = controllerLayoutParams ?: return
        try {
            windowManager.removeView(root)
            windowManager.addView(root, params)
        } catch (e: Exception) {
            AppLogger.log("FloatingOCR", "ensureControllerOnTop failed", e)
        }
    }

    private fun updateEditModeToggleButton() {
        editModeToggleButton?.text = getString(
            R.string.overlay_edit_mode_option_format,
            if (editConfirmCoordinator.isEditing) getString(R.string.common_on) else getString(R.string.common_off)
        )
    }

    private fun updateEditButtons() {
        val isEditing = editConfirmCoordinator.isEditing
        swipeTranslateButton?.visibility = if (isEditing) View.GONE else View.VISIBLE
        addBubbleButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        confirmEditButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        cancelEditButton?.visibility = if (isEditing) View.VISIBLE else View.GONE
        addBubbleButton?.isEnabled = isEditing && currentSession != null
        confirmEditButton?.isEnabled = isEditing && currentSession != null
        cancelEditButton?.isEnabled = isEditing
        addBubbleButton?.alpha = if (addBubbleButton?.isEnabled == true) 1f else 0.5f
        confirmEditButton?.alpha = if (confirmEditButton?.isEnabled == true) 1f else 0.5f
        cancelEditButton?.alpha = if (cancelEditButton?.isEnabled == true) 1f else 0.5f
        addBubbleButton?.text = if (editConfirmCoordinator.createBubbleModeEnabled) {
            getString(R.string.overlay_add_bubble_mode_active)
        } else {
            getString(R.string.overlay_add_bubble_button)
        }
    }

    private fun createFloatingBallBackground(pressed: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (pressed) {
                intArrayOf(
                    0xFF1AA7FF.toInt(),
                    0xFF6A5CFF.toInt()
                )
            } else {
                intArrayOf(
                    0xFF39C5FF.toInt(),
                    0xFF4F7BFF.toInt()
                )
            }
        ).apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.LINEAR_GRADIENT
            setStroke(
                (1.5f * density).toInt().coerceAtLeast(1),
                if (pressed) 0x99FFFFFF.toInt() else 0x66FFFFFF
            )
        }
    }

    private fun updateFloatingBallPressedState(
        target: FloatingBallView,
        pressed: Boolean
    ) {
        target.isPressed = pressed
        target.background = createFloatingBallBackground(pressed)
        target.scaleX = if (pressed) 0.94f else 1f
        target.scaleY = if (pressed) 0.94f else 1f
        target.alpha = if (pressed) 0.96f else 1f
    }

    private fun createMenuButton(): AppCompatButton {
        val density = resources.displayMetrics.density
        return AppCompatButton(
            ContextThemeWrapper(this, R.style.Widget_MangaTranslator_DialogActionButton)
        ).apply {
            gravity = Gravity.CENTER
            elevation = 6f * density
            minimumWidth = 0
            minWidth = 0
            background = createMenuButtonBackground()
            setTextColor(0xFF000000.toInt())
        }
    }

    private fun createMenuButtonBackground(): StateListDrawable {
        val density = resources.displayMetrics.density
        val cornerRadius = 24f * density
        val strokeWidth = (1f * density).toInt().coerceAtLeast(1)
        val normal = GradientDrawable().apply {
            this.cornerRadius = cornerRadius
            setColor(0xFFFFFFFF.toInt())
            setStroke(strokeWidth, 0xFFE0E0E0.toInt())
        }
        val pressed = GradientDrawable().apply {
            this.cornerRadius = cornerRadius
            setColor(0xFFE8E8E8.toInt())
            setStroke(strokeWidth, 0xFFD6D6D6.toInt())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun createMenuButtonLayoutParams(
        width: Int,
        topMargin: Float = 0f
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            width,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            this.topMargin = topMargin.toInt()
        }
    }

    private fun toggleEditMode() {
        if (editConfirmCoordinator.isEditing) {
            cancelEditSession()
            controllerMenuPanel?.let { setMenuVisibility(it, false) }
            return
        }
        if (!enterEditMode()) {
            Toast.makeText(this, R.string.overlay_edit_requires_detection, Toast.LENGTH_SHORT).show()
        }
    }

    private fun enterEditMode(showToast: Boolean = true): Boolean {
        val session = currentSession ?: return false
        editConfirmCoordinator.beginEdit(session)
        setFloatingBallHidden(false)
        detectionOverlayView?.setEditMode(true)
        detectionOverlayView?.setCreateBubbleMode(false)
        refreshDetectionOverlayTouchability()
        updateAutoCloseDetectionState()
        updateEditModeToggleButton()
        updateEditButtons()
        if (showToast) {
            Toast.makeText(this, R.string.overlay_edit_mode_enabled, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun startSwipeTranslateMode() {
        if (!screenCaptureSession.isReady()) {
            AppLogger.log("FloatingOCR", "Swipe translate blocked: projection not ready")
            showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val generation = advanceDetectionGeneration()
        showProgressStatus(R.string.floating_progress_capturing)
        detectJob = scope.launch(Dispatchers.Default) {
            val runningJob = currentCoroutineContext()[Job]
            var bitmap: Bitmap? = null
            try {
                bitmap = screenCaptureSession.captureCurrentScreen()
                if (bitmap == null) {
                    runOnMainForDetectionGeneration(generation) {
                        showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
                        Toast.makeText(
                            this@FloatingBallOverlayService,
                            R.string.floating_capture_not_ready,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                val capturedBitmap = requireNotNull(bitmap)
                runOnMainForDetectionGeneration(generation) {
                    currentSession = TranslationResult(
                        imageName = "",
                        width = capturedBitmap.width,
                        height = capturedBitmap.height,
                        bubbles = emptyList()
                    )
                    syncOverlaySession()
                    replaceCurrentSessionBitmap(capturedBitmap)
                    rebuildAutoCloseReferenceFromCurrentSession()
                    bitmap = null
                    enterEditMode(showToast = false)
                    toggleCreateBubbleMode()
                    showProgressStatus(R.string.overlay_swipe_translate_ready, autoHide = true)
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        R.string.overlay_swipe_translate_ready,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                AppLogger.log("FloatingOCR", "Swipe translate mode ready")
            } catch (e: CancellationException) {
                AppLogger.log("FloatingOCR", "Swipe translate mode cancelled")
                throw e
            } catch (e: Exception) {
                AppLogger.log("FloatingOCR", "Swipe translate mode failed", e)
                runOnMainForDetectionGeneration(generation) {
                    showProgressStatus(R.string.floating_detect_failed, autoHide = true)
                    Toast.makeText(
                        this@FloatingBallOverlayService,
                        R.string.floating_detect_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                bitmapController.discardTransientCapture(bitmap)
                withContext(Dispatchers.Main + NonCancellable) {
                    if (detectJob === runningJob) {
                        detectJob = null
                    }
                    if (isDetectionGenerationCurrent(generation)) {
                        updateAutoCloseDetectionState()
                    }
                }
            }
        }
    }

    private fun toggleCreateBubbleMode() {
        if (!editConfirmCoordinator.isEditing) return
        editConfirmCoordinator.toggleCreateBubbleMode()
        val createBubbleEnabled = editConfirmCoordinator.createBubbleModeEnabled
        detectionOverlayView?.setCreateBubbleMode(createBubbleEnabled)
        if (!createBubbleEnabled) {
            setFloatingBallHidden(false)
        }
        updateAutoCloseDetectionState()
        updateEditButtons()
        if (createBubbleEnabled) {
            Toast.makeText(this, R.string.overlay_create_bubble_hint, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelEditSession() {
        if (!editConfirmCoordinator.isEditing) return
        val restored = editConfirmCoordinator.cancelEdit()
        currentSession = restored ?: currentSession
        setFloatingBallHidden(false)
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        syncOverlaySession()
        refreshDetectionOverlayTouchability()
        updateAutoCloseDetectionState()
        updateEditModeToggleButton()
        updateEditButtons()
        Toast.makeText(this, R.string.overlay_edit_canceled, Toast.LENGTH_SHORT).show()
    }

    private fun finishEditSession(showToast: Boolean) {
        setFloatingBallHidden(false)
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        refreshDetectionOverlayTouchability()
        updateAutoCloseDetectionState()
        updateEditModeToggleButton()
        updateEditButtons()
        if (showToast) {
            Toast.makeText(this, R.string.overlay_edit_applied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmEditSession() {
        if (!editConfirmCoordinator.isEditing) return
        if (editConfirmCoordinator.confirmInFlight) return
        val session = currentSession ?: run {
            editConfirmCoordinator.reset()
            finishEditSession(showToast = false)
            return
        }
        val bitmapSnapshot = bitmapController.createEditSnapshot() ?: run {
            showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val generation = advanceDetectionGeneration()
        showProgressStatus(R.string.overlay_empty_bubble_translating)
        if (!editConfirmCoordinator.startConfirm()) {
            bitmapController.releaseBitmap(bitmapSnapshot)
            return
        }
        detectJob = scope.launch(Dispatchers.Default) {
            val runningJob = currentCoroutineContext()[Job]
            try {
                editConfirmCoordinator.confirm(
                    bitmapSnapshot = bitmapSnapshot,
                    session = session,
                    language = currentTranslationLanguage(),
                    effects = object : EditConfirmCoordinator.ConfirmEffects {
                        override suspend fun awaitModelErrorRetry(responseContent: String): Boolean {
                            return this@FloatingBallOverlayService.awaitModelErrorRetry(
                                generation,
                                responseContent
                            )
                        }

                        override fun onApiError(errorCode: LlmErrorCode, detail: String?) {
                            if (isDetectionGenerationCurrent(generation)) {
                                showApiErrorDialog(errorCode, detail)
                            }
                        }

                        override fun onRequiresVlModel() {
                            if (isDetectionGenerationCurrent(generation)) {
                                showProgressStatus(R.string.floating_vl_model_required, autoHide = true)
                                Toast.makeText(
                                    this@FloatingBallOverlayService,
                                    R.string.floating_vl_model_required,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onTimedOut() {
                            if (isDetectionGenerationCurrent(generation)) {
                                showProgressStatus(R.string.floating_translate_timeout, autoHide = true)
                                Toast.makeText(
                                    this@FloatingBallOverlayService,
                                    R.string.floating_translate_timeout,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onCommitted(translation: TranslationResult) {
                            if (!isDetectionGenerationCurrent(generation) ||
                                !editConfirmCoordinator.isConfirmPending()
                            ) {
                                return
                            }
                            currentSession = translation
                            editConfirmCoordinator.completeConfirm()
                            syncOverlaySession()
                            finishEditSession(showToast = false)
                            rebuildAutoCloseReferenceFromCurrentSession()
                            showProgressStatus(R.string.overlay_empty_bubble_translated, autoHide = true)
                            Toast.makeText(
                                this@FloatingBallOverlayService,
                                R.string.overlay_empty_bubble_translated,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            } finally {
                bitmapController.releaseBitmap(bitmapSnapshot)
                withContext(Dispatchers.Main + NonCancellable) {
                    if (detectJob === runningJob) {
                        detectJob = null
                    }
                    if (isDetectionGenerationCurrent(generation)) {
                        editConfirmCoordinator.acknowledgeConfirmEnded()
                        updateAutoCloseDetectionState()
                    }
                }
            }
        }
    }

    private fun appendManualBubble(rect: RectF) {
        val session = currentSession ?: return
        val nextId = (session.bubbles.maxOfOrNull { it.id } ?: -1) + 1
        val bubble = BubbleTranslation.pending(nextId, RectF(rect), "", BubbleSource.MANUAL)
        currentSession = session.copy(bubbles = session.bubbles + bubble)
        editConfirmCoordinator.setCreateBubbleMode(false)
        setFloatingBallHidden(false)
        syncOverlaySession()
        detectionOverlayView?.setCreateBubbleMode(false)
        updateAutoCloseDetectionState()
        updateEditButtons()
    }

    private fun syncOverlaySession() {
        val session = currentSession
        if (session == null) {
            detectionOverlayView?.clearDetections()
            detectionOverlayView?.setSourceBitmap(null)
            updateEditButtons()
            return
        }
        detectionOverlayView?.setTranslationSession(
            session.width,
            session.height,
            session.bubbles
        )
        detectionOverlayView?.setSourceBitmap(bitmapController.sessionBitmap)
        updateEditButtons()
    }

    private fun refreshDetectionOverlayTouchability() {
        val params = detectionLayoutParams ?: return
        val newFlags = buildDetectionFlags()
        if (params.flags == newFlags) return
        params.flags = newFlags
        try {
            windowManager.updateViewLayout(detectionOverlayView, params)
        } catch (_: Exception) {
        }
        ensureControllerOnTop()
    }

    private fun replaceCurrentSessionBitmap(bitmap: Bitmap?) {
        if (bitmap != null) {
            bitmapController.adoptSessionFrame(bitmap)
        } else {
            bitmapController.clearSessionBitmap()
        }
        detectionOverlayView?.setSourceBitmap(bitmapController.sessionBitmap)
    }

    private fun clearCurrentSession() {
        advanceDetectionGeneration()
        blankBubbleErrorDialog?.dismiss()
        blankBubbleErrorDialog = null
        currentSession = null
        editConfirmCoordinator.reset()
        detectionOverlayView?.setEditMode(false)
        detectionOverlayView?.setCreateBubbleMode(false)
        detectionOverlayView?.clearDetections()
        setFloatingBallHidden(false)
        replaceCurrentSessionBitmap(null)
        clearAutoCloseReference()
        refreshDetectionOverlayTouchability()
        updateEditModeToggleButton()
        updateEditButtons()
    }

    private fun toggleMenuVisibility(menuPanel: View) {
        setMenuVisibility(menuPanel, !menuPanel.isVisible)
    }

    private fun setMenuVisibility(menuPanel: View, visible: Boolean) {
        menuPanel.isVisible = visible
        // Menu actions may be invoked while the ball is hidden by an edit gesture.
        // Restore it whenever the menu state changes so it remains the menu toggle target.
        setFloatingBallHidden(false)
        if (visible) updateEditButtons()
    }

    private fun filterDetectionRegions(
        regions: List<PageRegion>,
        height: Int,
        settings: com.manga.translate.settings.FloatingTranslateApiSettings
    ): List<PageRegion> {
        val top = height * settings.detectionTopInsetPercent / 100f
        val bottom = height * (100 - settings.detectionBottomInsetPercent) / 100f
        if (top <= 0f && bottom >= height) return regions
        return regions.filter { region ->
            val centerY = region.rect.centerY()
            centerY >= top && centerY <= bottom
        }
    }

    private fun performFloatingBallGestureAction(
        action: FloatingBallGestureAction,
        menuPanel: View
    ) {
        when (action) {
            FloatingBallGestureAction.START_TRANSLATE -> {
                setMenuVisibility(menuPanel, false)
                runTextDetection()
            }

            FloatingBallGestureAction.OPEN_MENU -> {
                toggleMenuVisibility(menuPanel)
            }

            FloatingBallGestureAction.CLEAR_SCREEN -> {
                setMenuVisibility(menuPanel, false)
                clearCurrentSession()
            }

            FloatingBallGestureAction.NONE -> Unit

            FloatingBallGestureAction.SWIPE_TRANSLATE -> {
                setMenuVisibility(menuPanel, false)
                startSwipeTranslateMode()
            }
        }
    }

    private fun prepareProjection(resultCode: Int, data: Intent) {
        AppLogger.log("FloatingOCR", "Preparing projection")
        releaseProjection()
        val metrics = currentDisplayMetrics() ?: return
        lastKnownDisplayMetrics = metrics
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        if (!screenCaptureSession.prepare(manager, resultCode, data, metrics, PixelFormat.RGBA_8888)) {
            AppLogger.log("FloatingOCR", "Projection preparation failed")
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): DisplayMetrics? {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }

    private fun handleDisplayMetricsChange() {
        val metrics = currentDisplayMetrics() ?: return
        val last = lastKnownDisplayMetrics
        if (last != null &&
            last.widthPixels == metrics.widthPixels &&
            last.heightPixels == metrics.heightPixels &&
            last.densityDpi == metrics.densityDpi
        ) {
            return
        }
        lastKnownDisplayMetrics = metrics
        if (!screenCaptureSession.isReady()) {
            return
        }
        AppLogger.log(
            "FloatingOCR",
            "Display metrics changed to ${metrics.widthPixels}x${metrics.heightPixels}; rebuilding capture"
        )
        // Captured frames, detection coordinates and the overlay session were all
        // produced for the previous display size: drop them and rebuild the capture
        // pipeline with the new metrics. The projection authorization stays valid,
        // so no new consent flow is required.
        scope.launch(Dispatchers.Main) {
            clearCurrentSession()
            if (!screenCaptureSession.reconfigure(metrics)) {
                AppLogger.log("FloatingOCR", "Projection reconfigure failed after display change")
            }
        }
    }

    private fun runTextDetection() {
        blankBubbleErrorDialog?.dismiss()
        blankBubbleErrorDialog = null
        if (editConfirmCoordinator.isEditing) {
            editConfirmCoordinator.reset()
            finishEditSession(showToast = false)
        }
        if (!screenCaptureSession.isReady()) {
            AppLogger.log("FloatingOCR", "Run detection blocked: projection not ready")
            showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
            Toast.makeText(this, R.string.floating_capture_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val generation = advanceDetectionGeneration()
        stopAutoCloseDetection()
        AppLogger.log("FloatingOCR", "Run detection started")
        showProgressStatus(R.string.floating_progress_capturing)
        detectJob = scope.launch(Dispatchers.Default) {
            val runningJob = currentCoroutineContext()[Job]
            val localModelLease = appContainer.localModelMemoryManager.acquire("FloatingOCR")
            try {
                while (true) {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = screenCaptureSession.captureCurrentScreen()
                        if (bitmap == null) {
                            AppLogger.log("FloatingOCR", "Capture screen returned null")
                            runOnMainForDetectionGeneration(generation) {
                                showProgressStatus(R.string.floating_capture_not_ready, autoHide = true)
                                Toast.makeText(
                                    this@FloatingBallOverlayService,
                                    R.string.floating_capture_not_ready,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@launch
                        }
                        val capturedBitmap = requireNotNull(bitmap)
                        if (!isDetectionGenerationCurrent(generation)) return@launch
                        runOnMainForDetectionGeneration(generation) {
                            showProgressStatus(R.string.floating_progress_detecting)
                        }
                        val detector = pageRegionDetector ?: PageRegionDetector(
                            applicationContext,
                            settingsStore = settingsStore
                        ).also { pageRegionDetector = it }
                        val pageRegions = detector.detect(capturedBitmap, logTag = "FloatingOCR")
                        if (!isDetectionGenerationCurrent(generation)) return@launch
                        if (pageRegions == null) {
                            AppLogger.log("FloatingOCR", "Page region detection returned null")
                            runOnMainForDetectionGeneration(generation) {
                                showProgressStatus(R.string.floating_detect_failed, autoHide = true)
                                Toast.makeText(
                                    this@FloatingBallOverlayService,
                                    R.string.floating_detect_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@launch
                        }
                        val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
                        val regions = filterDetectionRegions(
                            pageRegions.regions,
                            capturedBitmap.height,
                            floatingSettings
                        )
                        val balloonCount = regions.count { it.source == BubbleSource.BUBBLE_DETECTOR }
                        val freeTextCount = regions.count { it.source == BubbleSource.TEXT_DETECTOR }
                        AppLogger.log(
                            "FloatingOCR",
                            "Detected regions=${regions.size} balloons=$balloonCount freeText=$freeTextCount"
                        )
                        val floatingApiSettings = settingsStore.loadResolvedFloatingTranslateApiSettings()
                        val floatingTimeoutMs = floatingSettings.timeoutSeconds * 1000
                        val useVlDirectTranslate =
                            floatingSettings.useVlDirectTranslate &&
                                llmClient.isConfigured(floatingApiSettings)
                        val regionBubbles = regions.map { region ->
                            BubbleTranslation.pending(
                                id = region.id,
                                rect = region.rect,
                                originalText = "",
                                source = region.source,
                                maskContour = region.maskContour
                            )
                        }
                        val vlOutcome = if (useVlDirectTranslate) {
                            runOnMainForDetectionGeneration(generation) {
                                showProgressStatus(
                                    getString(R.string.floating_progress_vl_translating, regionBubbles.size)
                                )
                            }
                            floatingBubbleTranslationCoordinator.translateImageBubbles(
                                bitmap = capturedBitmap,
                                bubbles = regionBubbles,
                                timeoutMs = floatingTimeoutMs,
                                retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                                promptAsset = FLOAT_VL_PROMPT_ASSET,
                                apiSettings = floatingApiSettings,
                                language = currentTranslationLanguage(),
                                concurrency = floatingSettings.aiApiConcurrencyLimit,
                                maxConcurrency = MAX_FLOATING_TASK_CONCURRENCY
                            )
                        } else {
                            null
                        }
                        if (!isDetectionGenerationCurrent(generation)) return@launch
                        if (vlOutcome?.requiresVlModel == true) {
                            runOnMainForDetectionGeneration(generation) {
                                showProgressStatus(R.string.floating_vl_model_required, autoHide = true)
                                Toast.makeText(
                                    this@FloatingBallOverlayService,
                                    R.string.floating_vl_model_required,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return@launch
                        }
                        val floatingLanguage = currentTranslationLanguage()
                        val translatedBubbles = if (useVlDirectTranslate) {
                            if (vlOutcome?.timedOut == true) {
                                null
                            } else {
                                vlOutcome?.bubbles ?: emptyList()
                            }
                        } else {
                            runOnMainForDetectionGeneration(generation) {
                                showProgressStatus(
                                    getString(R.string.floating_progress_recognizing, regionBubbles.size)
                                )
                            }
                            val bubbles = recognizeFloatingTextBubbles(
                                bitmap = capturedBitmap,
                                regions = regions,
                                language = floatingLanguage,
                                concurrency = floatingSettings.ocrConcurrencyLimit
                            )
                            if (!isDetectionGenerationCurrent(generation)) return@launch
                            runOnMainForDetectionGeneration(generation) {
                                showProgressStatus(R.string.floating_progress_translating)
                            }
                            floatingBubbleTranslationCoordinator.translateTextBubbles(
                                bubbles = bubbles,
                                timeoutMs = floatingTimeoutMs,
                                retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                                promptAsset = FLOAT_PROMPT_ASSET,
                                apiSettings = floatingApiSettings,
                                language = floatingLanguage
                            )
                        }
                        if (!isDetectionGenerationCurrent(generation)) return@launch
                        if (translatedBubbles == null) {
                            AppLogger.log("FloatingOCR", "Translate timeout")
                            runOnMainForDetectionGeneration(generation) {
                                showProgressStatus(R.string.floating_translate_timeout, autoHide = true)
                                Toast.makeText(
                                    this@FloatingBallOverlayService,
                                    R.string.floating_translate_timeout,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@launch
                        }
                        if (!isDetectionGenerationCurrent(generation)) return@launch
                        val resolvedTranslation = executeWithModelResponseRetries("FloatingOCR") {
                            val firstPass = TranslationResult(
                                imageName = "",
                                width = capturedBitmap.width,
                                height = capturedBitmap.height,
                                bubbles = translatedBubbles
                            )
                            if (firstPass.bubbles.any { it.needsTranslationRetry() }) {
                                emptyBubbleCoordinator.process(
                                    bitmap = capturedBitmap,
                                    baseTranslation = firstPass,
                                    timeoutMs = floatingTimeoutMs,
                                    retryCount = FLOATING_TRANSLATE_RETRY_COUNT,
                                    floatPromptAsset = FLOAT_PROMPT_ASSET,
                                    floatVlPromptAsset = FLOAT_VL_PROMPT_ASSET,
                                    maxVlConcurrency = MAX_FLOATING_TASK_CONCURRENCY,
                                    language = floatingLanguage
                                ).let { outcome ->
                                    if (outcome.requiresVlModel || outcome.timedOut) {
                                        return@let firstPass
                                    }
                                    outcome.translation
                                }
                            } else {
                                firstPass
                            }
                        }
                        runOnMainForDetectionGeneration(generation) {
                            val proofreadingModeEnabled = settingsStore
                                .loadFloatingTranslateApiSettings()
                                .proofreadingModeEnabled
                            currentSession = TranslationResult(
                                imageName = "",
                                width = resolvedTranslation.width,
                                height = resolvedTranslation.height,
                                bubbles = resolvedTranslation.bubbles
                            )
                            syncOverlaySession()
                            replaceCurrentSessionBitmap(capturedBitmap)
                            rebuildAutoCloseReferenceFromCurrentSession()
                            bitmap = null
                            if (proofreadingModeEnabled) {
                                enterEditMode(showToast = true)
                                controllerMenuPanel?.let { setMenuVisibility(it, true) }
                                updateEditButtons()
                                showProgressStatus(
                                    getString(R.string.floating_progress_done, resolvedTranslation.bubbles.size),
                                    autoHide = false
                                )
                            } else {
                                showProgressStatus(
                                    getString(R.string.floating_progress_done, resolvedTranslation.bubbles.size),
                                    autoHide = true
                                )
                                Toast.makeText(
                                    this@FloatingBallOverlayService,
                                    getString(R.string.floating_detected_count, resolvedTranslation.bubbles.size),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        AppLogger.log(
                            "FloatingOCR",
                            "Run detection finished bubbles=${resolvedTranslation.bubbles.size}"
                        )
                        break
                    } catch (e: LlmResponseException) {
                        AppLogger.log("FloatingOCR", "Floating detection model response invalid", e)
                        if (!awaitModelErrorRetry(generation, e.responseContent)) {
                            break
                        }
                    } catch (e: LlmRequestException) {
                        AppLogger.log("FloatingOCR", "Floating detection request failed", e)
                        runOnMainForDetectionGeneration(generation) {
                            showApiErrorDialog(e.errorCode, e.responseBody)
                        }
                        break
                    } catch (e: CancellationException) {
                        AppLogger.log("FloatingOCR", "Floating detection cancelled")
                        throw e
                    } catch (e: Exception) {
                        AppLogger.log("FloatingOCR", "Floating detection failed", e)
                        runOnMainForDetectionGeneration(generation) {
                            showProgressStatus(R.string.floating_detect_failed, autoHide = true)
                            Toast.makeText(
                                this@FloatingBallOverlayService,
                                R.string.floating_detect_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        break
                    } finally {
                        bitmapController.discardTransientCapture(bitmap)
                    }
                }
            } finally {
                localModelLease.close()
                withContext(Dispatchers.Main + NonCancellable) {
                    if (detectJob === runningJob) {
                        detectJob = null
                    }
                    if (isDetectionGenerationCurrent(generation)) {
                        updateAutoCloseDetectionState()
                    }
                }
            }
        }
    }

    private suspend fun recognizeFloatingTextBubbles(
        bitmap: Bitmap,
        regions: List<PageRegion>,
        language: TranslationLanguage,
        concurrency: Int
    ): List<BubbleTranslation> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, MAX_FLOATING_TASK_CONCURRENCY))
        regions.map { region ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    val bubble = BubbleTranslation.pending(
                        id = region.id,
                        rect = region.rect,
                        originalText = "",
                        source = region.source,
                        maskContour = region.maskContour
                    )
                    val crop = bitmapController.cropRegion(bitmap, region.rect)
                    if (crop == null) {
                        return@withPermit bubble
                    }
                    val text = try {
                        recognizeFloatingBubbleText(
                            crop = crop,
                            language = language,
                            bubbleSource = region.source,
                            detectedLineRects = mapPageLineRectsToCrop(
                                region.textLineRects,
                                region.rect,
                                crop.width,
                                crop.height
                            )
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.log(
                            "FloatingOCR",
                            "Floating OCR recognize failed language=${language.name}",
                            e
                        )
                        ""
                    } finally {
                        bitmapController.releaseBitmap(crop)
                    }
                    if (text.isBlank() && region.source == BubbleSource.TEXT_DETECTOR) {
                        null
                    } else {
                        bubble.withRecognizedOriginalText(text)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun showModelErrorDialog(
        responseContent: String,
        onContinue: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        blankBubbleErrorDialog?.dismiss()
        var resolved = false
        val dialog = com.manga.translate.platform.showModelErrorDialog(
            context = this,
            responseContent = responseContent,
            onRetry = {
                resolved = true
                onContinue?.invoke()
            },
            onSkip = {
                resolved = true
                onCancel?.invoke()
            },
            windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        dialog.setOnDismissListener {
            if (blankBubbleErrorDialog === dialog) {
                blankBubbleErrorDialog = null
            }
            if (!resolved) {
                onCancel?.invoke()
            }
        }
        blankBubbleErrorDialog = dialog
    }

    private suspend fun awaitModelErrorRetry(generation: Long, responseContent: String): Boolean {
        return withContext(Dispatchers.Main) {
            if (!isDetectionGenerationCurrent(generation)) {
                return@withContext false
            }
            val decision = CompletableDeferred<Boolean>()
            showModelErrorDialog(
                responseContent = responseContent,
                onContinue = { decision.complete(true) },
                onCancel = { decision.complete(false) }
            )
            decision.await()
        }
    }

    private fun showApiErrorDialog(errorCode: LlmErrorCode, detail: String?) {
        showApiErrorDialog(errorCode.value, detail)
    }

    private fun showApiErrorDialog(
        errorCode: String,
        detail: String?
    ) {
        blankBubbleErrorDialog?.dismiss()
        val message = getString(
            R.string.api_request_failed_message,
            ErrorDialogFormatter.formatApiErrorMessage(this, errorCode, detail)
        )
        val dialog = createAlertDialogBuilder(this)
            .setTitle(R.string.api_request_failed_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .createWithScrollableMessage()
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        )
        dialog.setOnDismissListener {
            if (blankBubbleErrorDialog === dialog) {
                blankBubbleErrorDialog = null
            }
        }
        blankBubbleErrorDialog = dialog
        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBallGesture(
        target: FloatingBallView,
        menuPanel: View,
        params: WindowManager.LayoutParams
    ) {
        val touchSlop = (3f * resources.displayMetrics.density)
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var dragging = false
        var isPointerDown = false
        var longPressTriggered = false
        var tapCount = 0
        val commitTapRunnable = Runnable {
            if (isPointerDown) {
                return@Runnable
            }
            val gestureSettings = settingsStore.loadFloatingTranslateApiSettings()
            when (tapCount.coerceAtMost(3)) {
                1 -> target.performClick()
                2 -> performFloatingBallGestureAction(gestureSettings.doubleTapAction, menuPanel)
                3 -> performFloatingBallGestureAction(gestureSettings.tripleTapAction, menuPanel)
            }
            tapCount = 0
        }
        val longPressRunnable = Runnable {
            if (!isPointerDown || dragging || tapCount != 0) {
                return@Runnable
            }
            longPressTriggered = true
            performFloatingBallGestureAction(
                settingsStore.loadFloatingTranslateApiSettings().longPressAction,
                menuPanel
            )
        }
        target.onPerformClick = {
            performFloatingBallGestureAction(
                settingsStore.loadFloatingTranslateApiSettings().singleTapAction,
                menuPanel
            )
        }
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    dragging = false
                    isPointerDown = true
                    longPressTriggered = false
                    updateFloatingBallPressedState(target, pressed = true)
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (tapCount == 0) {
                        mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    val shouldStartDragging = abs(dx) > touchSlop || abs(dy) > touchSlop
                    if (menuPanel.isVisible && shouldStartDragging) {
                        setMenuVisibility(menuPanel, false)
                    }
                    if (!dragging && shouldStartDragging) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        mainHandler.removeCallbacks(commitTapRunnable)
                        tapCount = 0
                        dragging = true
                        updateFloatingBallPressedState(target, pressed = false)
                    }
                    if (!dragging) {
                        return@setOnTouchListener true
                    }
                    params.x = (downX + dx).toInt().coerceAtLeast(0)
                    params.y = (downY + dy).toInt().coerceAtLeast(0)
                    windowManager.updateViewLayout(controllerRoot, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    isPointerDown = false
                    updateFloatingBallPressedState(target, pressed = false)
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        dragging = false
                        return@setOnTouchListener true
                    }
                    if (longPressTriggered) {
                        longPressTriggered = false
                        return@setOnTouchListener true
                    }
                    tapCount = (tapCount + 1).coerceAtMost(3)
                    mainHandler.removeCallbacks(commitTapRunnable)
                    if (tapCount >= 3) {
                        performFloatingBallGestureAction(
                            settingsStore.loadFloatingTranslateApiSettings().tripleTapAction,
                            menuPanel
                        )
                        tapCount = 0
                    } else {
                        mainHandler.postDelayed(commitTapRunnable, doubleTapTimeout)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isPointerDown = false
                    tapCount = 0
                    longPressTriggered = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.removeCallbacks(commitTapRunnable)
                    dragging = false
                    updateFloatingBallPressedState(target, pressed = false)
                    false
                }

                else -> true
            }
        }
    }

    private fun removeOverlay() {
        stopAutoCloseDetection()
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
        controllerMenuPanel = null
        controllerBallView = null
        detectionOverlayView = null
        detectionLayoutParams = null
        editModeToggleButton = null
        swipeTranslateButton = null
        addBubbleButton = null
        confirmEditButton = null
        cancelEditButton = null
        progressStatusView = null
    }

    private fun releaseProjection() {
        advanceDetectionGeneration()
        screenCaptureSession.release()
    }

    private fun shouldRunAutoCloseDetection(): Boolean {
        val settings = settingsStore.loadFloatingTranslateApiSettings()
        return settings.autoCloseOnScreenChangeEnabled &&
            currentSession != null &&
            !editConfirmCoordinator.isEditing &&
            !editConfirmCoordinator.createBubbleModeEnabled &&
            detectJob?.isActive != true &&
            screenCaptureSession.isReady() &&
            bitmapController.autoCloseReferenceFrame != null
    }

    private fun updateAutoCloseDetectionState() {
        if (shouldRunAutoCloseDetection()) {
            startAutoCloseDetection()
        } else {
            stopAutoCloseDetection()
        }
    }

    private fun startAutoCloseDetection() {
        if (!shouldRunAutoCloseDetection()) return
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        scheduleNextAutoCloseCheck()
    }

    private fun stopAutoCloseDetection() {
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        autoCloseCheckJob?.cancel()
        autoCloseCheckJob = null
    }

    private fun scheduleNextAutoCloseCheck() {
        if (!shouldRunAutoCloseDetection()) return
        mainHandler.removeCallbacks(autoCloseCheckRunnable)
        mainHandler.postDelayed(autoCloseCheckRunnable, AUTO_CLOSE_SCREEN_CHECK_INTERVAL_MS)
    }

    private fun rebuildAutoCloseReferenceFromCurrentSession() {
        bitmapController.rebuildAutoCloseReference(bitmapController.sessionBitmap)
        updateAutoCloseDetectionState()
    }

    private fun clearAutoCloseReference() {
        bitmapController.clearAutoCloseReference()
        stopAutoCloseDetection()
    }

    private suspend fun detectScreenChangeAgainstReference(): Boolean {
        val reference = bitmapController.autoCloseReferenceFrame ?: return false
        val currentScreen = screenCaptureSession.captureCurrentScreen(
            timeoutMs = AUTO_CLOSE_CAPTURE_TIMEOUT_MS,
            requireFreshFrame = true
        ) ?: return false
        try {
            val current = bitmapController.createScreenChangeReferenceFrame(currentScreen) ?: return false
            try {
                return bitmapController.hasMeaningfulScreenChange(reference, current)
            } finally {
                bitmapController.releaseBitmap(current.bitmap)
            }
        } finally {
            bitmapController.releaseBitmap(currentScreen)
        }
    }

    private object ServiceInfoForegroundTypes {
        const val MEDIA_PROJECTION = 0x00000020
    }

    companion object {
        const val ACTION_START = "com.manga.translate.action.FLOATING_START"
        const val ACTION_STOP = "com.manga.translate.action.FLOATING_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_LANGUAGE = "extra_language"
        private const val FLOATING_PROGRESS_HIDE_DELAY_MS = 2_000L
        private const val CHANNEL_ID = "floating_detect_channel"
        private const val NOTIFICATION_ID = 2002
        private const val FLOAT_PROMPT_ASSET = "prompts/float_llm_prompts.json"
        private const val FLOAT_VL_PROMPT_ASSET = "prompts/vl_bubble_prompts.json"
        private const val FLOATING_TRANSLATE_RETRY_COUNT = 1
        private const val MAX_FLOATING_TASK_CONCURRENCY = 50
        private const val AUTO_CLOSE_SCREEN_CHECK_INTERVAL_MS = 900L
        private const val AUTO_CLOSE_CAPTURE_TIMEOUT_MS = 1200L
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
