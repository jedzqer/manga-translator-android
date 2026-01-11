package com.manga.translate

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val MAX_LOG_BYTES = 1_000_000
    private const val MAX_LOG_FILES = 15
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameFormatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)
    private var logDir: File? = null
    private var logFile: File? = null

    fun init(context: Context) {
        val externalRoot = context.getExternalFilesDir(null)?.parentFile
        val dir = if (externalRoot != null) {
            File(externalRoot, "log")
        } else {
            File(context.filesDir, "logs")
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        logDir = dir
        logFile = createNewLogFile(dir)
        log("AppLogger", "Logger initialized")
        cleanupOldLogs()
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val file = logFile ?: return
        val time = formatter.format(Date())
        val line = buildString {
            append("[ info ] ")
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
                file.writeText("[ info ] $time [AppLogger] Log rotated\n")
            }
            file.appendText(line)
        }
    }

    fun readLogs(): String {
        val file = logFile ?: return ""
        return if (file.exists()) file.readText() else ""
    }

    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        val files = dir.listFiles { file -> file.isFile }?.toList().orEmpty()
        return files.sortedByDescending { it.name }
    }

    private fun createNewLogFile(dir: File): File {
        val base = "app_${fileNameFormatter.format(Date())}"
        var candidate = File(dir, "$base.log")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$index.log")
            index += 1
        }
        return candidate
    }

    private fun cleanupOldLogs() {
        val dir = logDir ?: return
        val files = dir.listFiles { file -> file.isFile }?.sortedByDescending { it.name }.orEmpty()
        if (files.size <= MAX_LOG_FILES) return
        for (file in files.drop(MAX_LOG_FILES)) {
            file.delete()
        }
    }
}
