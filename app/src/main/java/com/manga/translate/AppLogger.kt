package com.manga.translate

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_LOG_BYTES = 1_000_000
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "app.log")
        log("AppLogger", "Logger initialized")
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val file = logFile ?: return
        val time = formatter.format(Date())
        val line = buildString {
            append(time)
            append(" [")
            append(tag)
            append("] ")
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message ?: "no message")
            }
            append('\n')
        }
        synchronized(this) {
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                file.writeText("$time [AppLogger] Log rotated\n")
            }
            file.appendText(line)
        }
    }

    fun readLogs(): String {
        val file = logFile ?: return ""
        return if (file.exists()) file.readText() else ""
    }
}
