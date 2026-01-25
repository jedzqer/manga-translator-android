package com.manga.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class TranslationKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLock()
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.translation_keepalive_title)
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.translation_keepalive_message)
        val content = intent?.getStringExtra(EXTRA_CONTENT)
            ?: getString(R.string.translation_preparing)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                this,
                title,
                message,
                content,
                null,
                null
            )
        )
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MangaTranslator:TranslationKeepAlive"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
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

    companion object {
        private const val CHANNEL_ID = "translation_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_REQUEST_CODE = 0
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_MESSAGE = "extra_message"
        private const val EXTRA_CONTENT = "extra_content"

        fun start(context: Context) {
            val intent = Intent(context, TranslationKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context, title: String, message: String, content: String) {
            val intent = Intent(context, TranslationKeepAliveService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_CONTENT, content)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TranslationKeepAliveService::class.java)
            context.stopService(intent)
        }

        fun updateStatus(context: Context, status: String) {
            notifyProgress(
                context,
                context.getString(R.string.translation_keepalive_title),
                context.getString(R.string.translation_keepalive_message),
                status,
                null,
                null
            )
        }

        fun updateStatus(context: Context, status: String, title: String, message: String) {
            notifyProgress(context, title, message, status, null, null)
        }

        fun updateProgress(context: Context, progress: Int, total: Int) {
            notifyProgress(
                context,
                context.getString(R.string.translation_keepalive_title),
                context.getString(R.string.translation_keepalive_message),
                "$progress/$total",
                progress,
                total
            )
        }

        fun updateProgress(
            context: Context,
            progress: Int,
            total: Int,
            content: String,
            title: String,
            message: String
        ) {
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
            if (progress != null && total != null && total > 0) {
                builder.setProgress(total, progress.coerceAtMost(total), false)
            } else {
                builder.setProgress(0, 0, false)
            }
            return builder.build()
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
    }
}
