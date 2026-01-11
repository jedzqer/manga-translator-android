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

class TranslationKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                this,
                getString(R.string.translation_preparing),
                null,
                null
            )
        )
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL_ID = "translation_keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_REQUEST_CODE = 0

        fun start(context: Context) {
            val intent = Intent(context, TranslationKeepAliveService::class.java)
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
            notifyProgress(context, status, null, null)
        }

        fun updateProgress(context: Context, progress: Int, total: Int) {
            notifyProgress(context, "$progress/$total", progress, total)
        }

        private fun notifyProgress(
            context: Context,
            content: String,
            progress: Int?,
            total: Int?
        ) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(context, manager)
            val notification = buildNotification(context, content, progress, total)
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun buildNotification(
            context: Context,
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
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(context.getString(R.string.translation_keepalive_title))
                .setContentText(content)
                .setSubText(context.getString(R.string.translation_keepalive_message))
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
