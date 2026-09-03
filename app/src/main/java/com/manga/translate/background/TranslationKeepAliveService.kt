package com.manga.translate.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.manga.translate.R
import com.manga.translate.app.MainActivity
import com.manga.translate.detection.LocalModelMemoryManager
import com.manga.translate.di.appContainer
import com.manga.translate.library.LibraryUiBridge
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.GlobalTaskProgressStore
import com.manga.translate.platform.GlobalTaskProgressStage
import com.manga.translate.platform.TranslationCancellationRegistry
import com.manga.translate.storage.TranslationTaskDescriptor
import com.manga.translate.storage.TranslationTaskPersistence
import com.manga.translate.storage.parseTranslationTaskDescriptor
import com.manga.translate.storage.toFolderTasks
import com.manga.translate.storage.toJsonString
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TranslationKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        AppLogger.logFatal("TranslationKeepAlive", "Uncaught coroutine exception", throwable)
        applicationContext.appContainer.crashStateStore.markCrashed()
        taskPersistence.clear()
    }
    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + serviceExceptionHandler)
    private val taskPersistence by lazy(LazyThreadSafetyMode.NONE) {
        TranslationTaskPersistence(applicationContext)
    }
    private var translationJob: Job? = null
    private val taskRegistry = KeepAliveTaskRegistry()
    private var localModelLease: LocalModelMemoryManager.LocalModelLease? = null
    private var currentTaskLabel: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_TRANSLATION) {
            handleCancelTranslation()
            return START_NOT_STICKY
        }
        ensureForegroundNotificationChannel()
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.translation_keepalive_title)
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.translation_keepalive_message)
        val content = intent?.getStringExtra(EXTRA_CONTENT)
            ?: getString(R.string.translation_preparing)
        when (intent?.action) {
            ACTION_START_TRANSLATION_TASK -> {
                loadDescriptor(intent)?.let { descriptor ->
                    currentTaskLabel = describeTaskLabel(this, descriptor)
                }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(this, title, message, content, null, null)
                )
                val descriptor = loadDescriptor(intent)
                if (descriptor == null) {
                    finishIdleTask(
                        clearPersistedTask = true,
                        reEnableTranslationActions = true
                    )
                    return START_NOT_STICKY
                }
                startTranslationTask(descriptor)
                // Do not ask the system to restart this translation after process death.
                return START_NOT_STICKY
            }
            ACTION_START_EXPORT_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                taskRegistry.startExport(
                    ExportKeepAliveTask(
                        id = taskId,
                        title = title,
                        message = message,
                        content = content
                    )
                )
                if (!taskRegistry.translationActive) {
                    showExportProgress(taskRegistry.foregroundExportTask!!)
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_EXPORT_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                val updatedTask = taskRegistry.updateExport(
                    taskId = taskId,
                    content = content,
                    progress = intent.getIntExtra(EXTRA_PROGRESS, -1).takeIf { it >= 0 },
                    total = intent.getIntExtra(EXTRA_TOTAL, -1).takeIf { it >= 0 }
                )
                if (updatedTask != null && !taskRegistry.translationActive) {
                    showExportProgress(updatedTask)
                }
                return START_NOT_STICKY
            }
            ACTION_FINISH_EXPORT_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                taskRegistry.updateExport(taskId = taskId, content = content)
                val finishedTask = taskRegistry.finishExport(taskId) ?: return START_NOT_STICKY
                if (!taskRegistry.translationActive) {
                    finishExportTask(finishedTask, intent.getBooleanExtra(EXTRA_FAILED, false))
                }
                return START_NOT_STICKY
            }
            else -> {
                // Keep an in-flight translation running; unknown starts must not kill it.
                if (taskRegistry.translationActive) {
                    return START_NOT_STICKY
                }
                if (intent == null) {
                    // System may still restart a previous sticky instance — never auto-resume.
                    AppLogger.log("Library", "Service restart with null intent — discarding pending translation")
                    finishIdleTask(
                        clearPersistedTask = true,
                        reEnableTranslationActions = true
                    )
                    return START_NOT_STICKY
                }
                finishIdleTask(
                    clearPersistedTask = false,
                    reEnableTranslationActions = false
                )
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val activeJob = translationJob
        translationJob = null
        taskRegistry.finishTranslation()
        if (activeJob?.isActive == true) {
            // Service teardown is not a user cancel; still free UI and drop this process's job.
            activeJob.cancel()
            LibraryUiBridge.setTranslationActionsEnabled(true)
        }
        releaseLocalModelLease()
        serviceScope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun handleCancelTranslation() {
        if (TranslationCancellationRegistry.requestCancel()) {
            cancelActionEnabled = false
            updateStatus(this, getString(R.string.translation_canceling))
        } else {
            // A cancel request arrived with nothing to cancel (e.g. a stale
            // intent); stop the service only when no other task keeps it alive.
            if (!taskRegistry.hasActiveTasks) {
                stopSelf()
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MangaTranslator:TranslationKeepAlive"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun loadDescriptor(intent: Intent): TranslationTaskDescriptor? {
        val raw = intent.getStringExtra(EXTRA_TASK_DESCRIPTOR) ?: return null
        return runCatching {
            parseTranslationTaskDescriptor(org.json.JSONObject(raw))
        }.getOrNull()
    }

    private fun finishIdleTask(
        clearPersistedTask: Boolean,
        reEnableTranslationActions: Boolean
    ) {
        // Never tear down while a translation job is still running.
        if (taskRegistry.translationActive) return
        if (clearPersistedTask) {
            taskPersistence.clear()
        }
        if (reEnableTranslationActions) {
            LibraryUiBridge.setTranslationActionsEnabled(true)
        }
        releaseLocalModelLease()
        releaseWakeLock()
        val exportTask = taskRegistry.foregroundExportTask
        if (exportTask != null) {
            showExportProgress(exportTask)
        } else {
            cancelActionEnabled = false
            GlobalTaskProgressStore.hide()
            stopSelf()
        }
    }

    private fun startTranslationTask(descriptor: TranslationTaskDescriptor) {
        if (translationJob?.isActive == true) return
        taskPersistence.save(descriptor)
        acquireWakeLock()
        localModelLease = applicationContext.appContainer.localModelMemoryManager.acquire("TranslationKeepAlive")
        val translationActionsCallback: (Boolean) -> Unit = { enabled ->
            LibraryUiBridge.setTranslationActionsEnabled(enabled)
        }
        translationActionsCallback(false)
        val coordinator = applicationContext.appContainer.createFolderTranslationCoordinator(
            translationPipeline = applicationContext.appContainer.createTranslationPipeline(),
            ui = ServiceLibraryUiCallbacks
        )
        val tasks = descriptor.toFolderTasks()
        if (tasks.isEmpty()) {
            GlobalTaskProgressStore.fail(
                getString(R.string.translation_keepalive_title),
                getString(R.string.translation_failed)
            )
            finishIdleTask(
                clearPersistedTask = true,
                reEnableTranslationActions = true
            )
            return
        }
        translationJob = when (descriptor.mode) {
            TranslationTaskPersistence.MODE_COLLECTION -> {
                val collectionFolder = descriptor.collectionFolderPath?.let(::File)
                if (collectionFolder == null || !collectionFolder.exists()) {
                    GlobalTaskProgressStore.fail(
                        getString(R.string.translation_keepalive_title),
                        getString(R.string.translation_failed)
                    )
                    finishIdleTask(
                        clearPersistedTask = true,
                        reEnableTranslationActions = true
                    )
                    return
                }
                coordinator.translateCollection(
                    scope = serviceScope,
                    collectionFolder = collectionFolder,
                    tasks = tasks,
                    onTranslateEnabled = translationActionsCallback
                )
            }
            TranslationTaskPersistence.MODE_BATCH -> {
                coordinator.translateBatch(
                    scope = serviceScope,
                    tasks = tasks,
                    onTranslateEnabled = translationActionsCallback
                )
            }
            else -> {
                val first = tasks.first()
                coordinator.translateFolder(
                    scope = serviceScope,
                    folder = first.folder,
                    images = first.images,
                    force = first.force,
                    fullTranslate = first.fullTranslate,
                    glossaryProcessingEnabled = first.glossaryProcessingEnabled,
                    useVlDirectTranslate = first.useVlDirectTranslate,
                    language = first.language,
                    onTranslateEnabled = translationActionsCallback
                )
            }
        }
        if (translationJob == null) {
            // The coordinator may return null when everything is already done,
            // inputs are empty, or startup validation fails before launching a job.
            finishIdleTask(
                clearPersistedTask = true,
                reEnableTranslationActions = true
            )
            return
        }
        val startedJob = translationJob ?: return
        taskRegistry.startTranslation()
        startedJob.invokeOnCompletion {
            serviceScope.launch {
                if (translationJob !== startedJob) return@launch
                translationJob = null
                taskRegistry.finishTranslation()
                translationActionsCallback(true)
                maybeNotifyTranslationFinished()
                taskPersistence.clear()
                releaseLocalModelLease()
                releaseWakeLock()
                taskRegistry.foregroundExportTask?.let(::showExportProgress) ?: run {
                    cancelActionEnabled = false
                    stopSelf()
                }
            }
        }
    }

    private fun showExportProgress(task: ExportKeepAliveTask) {
        cancelActionEnabled = false
        GlobalTaskProgressStore.show(
            title = task.title,
            detail = task.content,
            progress = task.progress,
            total = task.total
        )
        startForeground(
            NOTIFICATION_ID,
            buildNotification(this, task.title, task.message, task.content, task.progress, task.total)
        )
    }

    private fun finishExportTask(task: ExportKeepAliveTask, failed: Boolean) {
        if (failed) {
            GlobalTaskProgressStore.fail(task.title, task.content)
        } else {
            GlobalTaskProgressStore.complete(task.title, task.content)
        }
        taskRegistry.foregroundExportTask?.let(::showExportProgress) ?: run {
            cancelActionEnabled = false
            clearModelErrorAttention(this)
            stopSelf()
        }
    }

    private fun releaseLocalModelLease() {
        localModelLease?.close()
        localModelLease = null
    }

    private fun ensureForegroundNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(this, manager)
    }

    private fun maybeNotifyTranslationFinished() {
        val terminalState = GlobalTaskProgressStore.state.value.takeIf { it.terminal } ?: return
        showResultNotification(
            context = this,
            resultTitle = terminalState.detail.ifBlank {
                getString(R.string.translation_done)
            },
            taskLabel = currentTaskLabel.ifBlank {
                getString(R.string.translation_result_task_fallback_label)
            },
            isError = terminalState.error,
            isCanceled = terminalState.detail == getString(R.string.translation_canceled)
        )
    }

    companion object {
        private const val CHANNEL_ID = "translation_keepalive"
        private const val TRANSLATION_STOP_TIMEOUT_MS = 30_000L
        private const val CANCEL_RESEND_INTERVAL_MS = 2_000L
        private const val TRANSLATION_STOP_POLL_INTERVAL_MS = 250L
        private const val ALERT_CHANNEL_ID = "translation_alerts"
        private const val RESULT_CHANNEL_ID = "translation_results"
        private const val SUCCESS_RESULT_CHANNEL_ID = "translation_success_results"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val RESULT_NOTIFICATION_ID = 1003
        private const val NOTIFICATION_REQUEST_CODE = 0
        private const val ALERT_NOTIFICATION_REQUEST_CODE = 2
        private const val RESULT_NOTIFICATION_REQUEST_CODE = 3
        private const val CANCEL_REQUEST_CODE = 1
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 30 * 60 * 1000L
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_MESSAGE = "extra_message"
        private const val EXTRA_CONTENT = "extra_content"
        private const val EXTRA_TASK_DESCRIPTOR = "extra_task_descriptor"
        private const val EXTRA_TASK_ID = "extra_task_id"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_TOTAL = "extra_total"
        private const val EXTRA_FAILED = "extra_failed"
        private const val ACTION_CANCEL_TRANSLATION = "com.manga.translate.action.CANCEL_TRANSLATION"
        private const val ACTION_START_TRANSLATION_TASK = "com.manga.translate.action.START_TRANSLATION_TASK"
        private const val ACTION_START_EXPORT_TASK = "com.manga.translate.action.START_EXPORT_TASK"
        private const val ACTION_UPDATE_EXPORT_TASK = "com.manga.translate.action.UPDATE_EXPORT_TASK"
        private const val ACTION_FINISH_EXPORT_TASK = "com.manga.translate.action.FINISH_EXPORT_TASK"

        /**
         * Asks the service to cancel the active translation task (same routing
         * as the notification cancel action). Safe to call when no translation
         * is running; also safe while the app is in the foreground only — a
         * background start would throw and is swallowed and logged here.
         */
        fun requestCancelTranslation(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, TranslationKeepAliveService::class.java).apply {
                        action = ACTION_CANCEL_TRANSLATION
                    }
                )
            }.onFailure {
                AppLogger.log("TranslationKeepAlive", "Failed to deliver translation cancel request", it)
            }
        }

        /**
         * Cancels the active background translation task, if any, and suspends
         * until the service has fully wound it down (the persisted task
         * descriptor is cleared when the job completes). Callers that read or
         * rewrite the whole library — e.g. app backup import/export — use this
         * so they never race translation writes.
         *
         * The cancel request is re-sent periodically: between persisting the
         * task descriptor and registering the cancellation handler there is a
         * short window where a single request could be lost.
         *
         * @return false when a task was still running after [timeoutMs].
         */
        suspend fun awaitTranslationStopped(
            context: Context,
            timeoutMs: Long = TRANSLATION_STOP_TIMEOUT_MS
        ): Boolean {
            val persistence = TranslationTaskPersistence(context)
            if (persistence.load() == null) {
                // The service persists the descriptor as its very first step;
                // one settle re-check closes the start-up race without delaying
                // the normal path.
                delay(TRANSLATION_STOP_POLL_INTERVAL_MS)
                if (persistence.load() == null) return true
            }
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var lastCancelRequestAt = -CANCEL_RESEND_INTERVAL_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (persistence.load() == null) return true
                val now = SystemClock.elapsedRealtime()
                if (now - lastCancelRequestAt >= CANCEL_RESEND_INTERVAL_MS) {
                    requestCancelTranslation(context)
                    lastCancelRequestAt = now
                }
                delay(TRANSLATION_STOP_POLL_INTERVAL_MS)
            }
            return persistence.load() == null
        }

        const val EXTRA_OPEN_LIBRARY_TAB = "extra_open_library_tab"
        @Volatile
        private var cancelActionEnabled: Boolean = false

        fun startExportTask(
            context: Context,
            title: String,
            message: String,
            content: String
        ): String {
            val taskId = UUID.randomUUID().toString()
            val intent = Intent(context, TranslationKeepAliveService::class.java).apply {
                action = ACTION_START_EXPORT_TASK
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_CONTENT, content)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return taskId
        }

        fun updateExportProgress(
            context: Context,
            taskId: String,
            progress: Int,
            total: Int,
            content: String
        ) {
            sendExportTaskUpdate(context, taskId, content, progress, total)
        }

        fun finishExportTask(
            context: Context,
            taskId: String,
            failed: Boolean,
            content: String
        ) {
            context.startService(
                Intent(context, TranslationKeepAliveService::class.java).apply {
                    action = ACTION_FINISH_EXPORT_TASK
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_FAILED, failed)
                    putExtra(EXTRA_CONTENT, content)
                }
            )
        }

        private fun sendExportTaskUpdate(
            context: Context,
            taskId: String,
            content: String,
            progress: Int?,
            total: Int?
        ) {
            context.startService(
                Intent(context, TranslationKeepAliveService::class.java).apply {
                    action = ACTION_UPDATE_EXPORT_TASK
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_CONTENT, content)
                    progress?.let { putExtra(EXTRA_PROGRESS, it) }
                    total?.let { putExtra(EXTRA_TOTAL, it) }
                }
            )
        }

        internal fun updateStatus(
            context: Context,
            status: String,
            stage: GlobalTaskProgressStage? = null
        ) {
            GlobalTaskProgressStore.show(
                title = context.getString(R.string.translation_keepalive_title),
                detail = status,
                stage = stage
            )
            notifyProgress(
                context,
                context.getString(R.string.translation_keepalive_title),
                context.getString(R.string.translation_keepalive_message),
                status,
                null,
                null
            )
        }

        fun notifyModelErrorNeedsAttention(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureAlertChannel(context, manager)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_LIBRARY_TAB, true)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                ALERT_NOTIFICATION_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.model_response_failed_title))
                .setContentText(context.getString(R.string.model_error_attention_message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        }

        fun clearModelErrorAttention(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(ALERT_NOTIFICATION_ID)
        }

        internal fun startTranslationTask(
            context: Context,
            descriptor: TranslationTaskDescriptor,
            title: String = context.getString(R.string.translation_keepalive_title),
            message: String = context.getString(R.string.translation_keepalive_message),
            content: String = context.getString(R.string.translation_preparing)
        ) {
            cancelActionEnabled = true
            GlobalTaskProgressStore.show(title = title, detail = content)
            val intent = Intent(context, TranslationKeepAliveService::class.java).apply {
                action = ACTION_START_TRANSLATION_TASK
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_CONTENT, content)
                putExtra(EXTRA_TASK_DESCRIPTOR, descriptor.toJsonString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateStatus(context: Context, status: String, title: String, message: String) {
            GlobalTaskProgressStore.show(title = title, detail = status)
            notifyProgress(context, title, message, status, null, null)
        }

        fun updateProgress(context: Context, progress: Int, total: Int) {
            GlobalTaskProgressStore.show(
                title = context.getString(R.string.translation_keepalive_title),
                detail = "$progress/$total",
                progress = progress,
                total = total
            )
            notifyProgress(
                context,
                context.getString(R.string.translation_keepalive_title),
                context.getString(R.string.translation_keepalive_message),
                "$progress/$total",
                progress,
                total
            )
        }

        internal fun updateProgress(
            context: Context,
            progress: Int,
            total: Int,
            content: String,
            title: String,
            message: String,
            failedCount: Int? = null,
            stage: GlobalTaskProgressStage? = null
        ) {
            GlobalTaskProgressStore.show(
                title = title,
                detail = content,
                progress = progress,
                total = total,
                failedCount = failedCount,
                stage = stage
            )
            notifyProgress(context, title, message, content, progress, total)
        }

        private fun notifyProgress(
            context: Context,
            title: String,
            message: String,
            content: String,
            progress: Int?,
            total: Int?
        ) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(context, manager)
            val notification = buildNotification(context, title, message, content, progress, total)
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun buildNotification(
            context: Context,
            title: String,
            message: String,
            content: String,
            progress: Int?,
            total: Int?
        ): Notification {
            val openIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title)
                .setContentText(content)
                .setSubText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (cancelActionEnabled) {
                val cancelIntent = Intent(context, TranslationKeepAliveService::class.java).apply {
                    action = ACTION_CANCEL_TRANSLATION
                }
                val cancelPendingIntent = PendingIntent.getService(
                    context,
                    CANCEL_REQUEST_CODE,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.translation_cancel_action),
                    cancelPendingIntent
                )
            }
            if (progress != null && total != null && total > 0) {
                builder.setProgress(total, progress.coerceAtMost(total), false)
            } else {
                builder.setProgress(0, 0, false)
            }
            return builder.build()
        }

        private fun showResultNotification(
            context: Context,
            resultTitle: String,
            taskLabel: String,
            isError: Boolean,
            isCanceled: Boolean
        ) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureResultChannel(context, manager)
            ensureSuccessResultChannel(context, manager)
            val pendingIntent = buildOpenLibraryPendingIntent(
                context = context,
                requestCode = RESULT_NOTIFICATION_REQUEST_CODE
            )
            val isSuccess = !isError && !isCanceled
            val icon = when {
                isError -> android.R.drawable.stat_notify_error
                isCanceled -> android.R.drawable.ic_menu_close_clear_cancel
                else -> android.R.drawable.stat_sys_upload_done
            }
            val category = when {
                isError -> NotificationCompat.CATEGORY_ERROR
                else -> NotificationCompat.CATEGORY_STATUS
            }
            val priority = when {
                isError || isSuccess -> NotificationCompat.PRIORITY_HIGH
                else -> NotificationCompat.PRIORITY_DEFAULT
            }
            val notification = NotificationCompat.Builder(
                context,
                if (isSuccess) SUCCESS_RESULT_CHANNEL_ID else RESULT_CHANNEL_ID
            )
                .setSmallIcon(icon)
                .setContentTitle(resultTitle)
                .setContentText(context.getString(R.string.translation_result_message, taskLabel))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        context.getString(R.string.translation_result_message, taskLabel)
                    )
                )
                .setPriority(priority)
                .setCategory(category)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .apply {
                    if (isSuccess && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        setDefaults(Notification.DEFAULT_SOUND)
                    }
                }
                .build()
            manager.notify(RESULT_NOTIFICATION_ID, notification)
        }

        private fun buildOpenLibraryPendingIntent(
            context: Context,
            requestCode: Int
        ): PendingIntent {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_LIBRARY_TAB, true)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun describeTaskLabel(context: Context, descriptor: TranslationTaskDescriptor): String {
            return when (descriptor.mode) {
                TranslationTaskPersistence.MODE_COLLECTION -> descriptor.collectionFolderPath
                    ?.let(::File)
                    ?.name
                    .orEmpty()
                TranslationTaskPersistence.MODE_BATCH -> context.getString(
                    R.string.translation_result_batch_task_label,
                    descriptor.tasks.map { it.folderPath }.distinct().size.coerceAtLeast(1)
                )
                else -> descriptor.tasks.firstOrNull()
                    ?.folderPath
                    ?.let(::File)
                    ?.name
                    .orEmpty()
            }.ifBlank {
                context.getString(R.string.translation_result_task_fallback_label)
            }
        }

        private fun ensureChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.translation_keepalive_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }

        private fun ensureResultChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    RESULT_CHANNEL_ID,
                    context.getString(R.string.translation_result_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                channel.setSound(null, null)
                manager.createNotificationChannel(channel)
            }
        }

        private fun ensureSuccessResultChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    SUCCESS_RESULT_CHANNEL_ID,
                    context.getString(R.string.translation_success_result_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                channel.setSound(soundUri, audioAttributes)
                manager.createNotificationChannel(channel)
            }
        }

        private fun ensureAlertChannel(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.model_error_attention_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
                manager.createNotificationChannel(channel)
            }
        }
    }
}
