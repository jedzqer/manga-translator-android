package com.manga.translate.platform

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppLogger {
    private const val MAX_LOG_BYTES = 1_000_000
    private const val MAX_LOG_FILES = 15
    private const val LEVEL_INFO = "I"
    private const val LEVEL_ERROR = "E"
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameFormatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)
    private var logDir: File? = null
    private var logFile: File? = null

    fun init(context: Context) {
        val externalFilesDir = context.getExternalFilesDir(null)
        val dir = if (externalFilesDir != null) {
            // Keep logs below the app-specific external-files root so FileProvider
            // can resolve them without embedding the applicationId in resources.
            File(externalFilesDir, "log")
        } else {
            File(context.filesDir, "logs")
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        migrateLegacyLogs(externalFilesDir, dir)
        logDir = dir
        logFile = createNewLogFile(dir)
        log("AppLogger", "Logger initialized")
        cleanupOldLogs()
    }

    private fun migrateLegacyLogs(externalFilesDir: File?, destination: File) {
        val legacyDir = externalFilesDir?.parentFile?.let { File(it, "log") } ?: return
        val sameDirectory = runCatching {
            legacyDir.canonicalFile == destination.canonicalFile
        }.getOrDefault(legacyDir.absolutePath == destination.absolutePath)
        if (sameDirectory || !legacyDir.isDirectory) return
        legacyDir.listFiles { file -> file.isFile && file.extension.equals("log", true) }
            .orEmpty()
            .forEach { source ->
                val target = File(destination, source.name)
                if (target.exists()) return@forEach
                runCatching { source.copyTo(target, overwrite = false) }
                    .onFailure {
                        Log.w(
                            "MangaTranslate/AppLogger",
                            "Failed to migrate legacy log ${source.name}",
                            it
                        )
                    }
            }
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val level = if (throwable != null) LEVEL_ERROR else LEVEL_INFO
        writeLog(level, tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        writeLog(LEVEL_ERROR, tag, message, throwable)
    }

    /**
     * Write a crash/fatal record and force it onto disk before the process dies.
     * Also mirrors to a dedicated `crash_latest.log` so the last crash is easy to find.
     * Call this from uncaught-exception handlers only.
     */
    fun logFatal(tag: String, message: String, throwable: Throwable? = null) {
        writeLog(LEVEL_ERROR, tag, message, throwable, forceSync = true)
        writeCrashSnapshot(tag, message, throwable)
    }

    private fun writeCrashSnapshot(tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return
        val time = formatter.format(Date())
        val body = buildString {
            append('[')
            append(LEVEL_ERROR)
            append("][")
            append(tag)
            append("] ")
            append(time)
            append("  ")
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message ?: "no message")
                append('\n')
                append(stackTraceString(throwable))
            }
            append('\n')
        }
        try {
            val snapshot = File(dir, "crash_latest.log")
            FileOutputStream(snapshot, false).use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        } catch (e: Exception) {
            Log.e("MangaTranslate/AppLogger", "Failed to write crash_latest.log", e)
        }
    }

    private fun writeLog(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        forceSync: Boolean = false
    ) {
        val time = formatter.format(Date())
        val separator = "  "
        val line = buildString {
            append('[')
            append(level)
            append(']')
            append('[')
            append(tag)
            append("] ")
            append(time)
            append(separator)
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message ?: "no message")
                append('\n')
                append(stackTraceString(throwable))
            }
            append('\n')
        }
        // Always mirror to logcat so adb/tombstone paths still have a trail.
        if (throwable != null || level == LEVEL_ERROR) {
            Log.e("MangaTranslate/$tag", message, throwable)
        } else {
            Log.i("MangaTranslate/$tag", message)
        }
        val file = logFile ?: return
        synchronized(this) {
            try {
                if (file.exists() && file.length() > MAX_LOG_BYTES) {
                    // Truncate oversized log; keep a rotation marker.
                    FileOutputStream(file, false).use { out ->
                        val marker = "[AppLogger] $time${separator}Log rotated\n".toByteArray(Charsets.UTF_8)
                        out.write(marker)
                        out.flush()
                        if (forceSync) {
                            out.fd.sync()
                        }
                    }
                }
                FileOutputStream(file, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.flush()
                    if (forceSync || throwable != null) {
                        // Best-effort durability: crash/error lines should survive process death.
                        out.fd.sync()
                    }
                }
            } catch (e: Exception) {
                Log.e("MangaTranslate/AppLogger", "Failed to write log file", e)
            }
        }
    }

    fun readLogs(): String {
        val file = logFile ?: return ""
        return if (file.exists()) file.readText() else ""
    }

    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("log", true) }
            ?.toList()
            .orEmpty()
        return files.sortedByDescending { it.name }
    }

    fun listErrorLogFiles(): List<File> {
        return listLogFiles().filter(::containsErrorEntries)
    }

    fun createErrorLogsArchive(context: Context): File? {
        val errorFiles = listErrorLogFiles()
        if (errorFiles.isEmpty()) return null
        val dir = logDir ?: File(context.filesDir, "logs").also { it.mkdirs() }
        val archive = File(dir, "error_logs_${fileNameFormatter.format(Date())}.zip")
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archive))).use { zip ->
                for (file in errorFiles) {
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
            if (archive.exists() && archive.length() > 0L) archive else null
        } catch (_: Exception) {
            if (archive.exists()) {
                archive.delete()
            }
            null
        }
    }

    private fun containsErrorEntries(file: File): Boolean {
        return try {
            file.useLines { lines ->
                lines.any { line ->
                    line.startsWith("[$LEVEL_ERROR]") || line.contains(LEGACY_EXCEPTION_MARKER)
                }
            }
        } catch (_: Exception) {
            false
        }
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
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("log", true) }
            ?.sortedByDescending { it.name }
            .orEmpty()
        if (files.size <= MAX_LOG_FILES) return
        for (file in files.drop(MAX_LOG_FILES)) {
            file.delete()
        }
    }

    private fun stackTraceString(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            throwable.printStackTrace(printWriter)
        }
        return writer.toString().trimEnd()
    }

    private const val LEGACY_EXCEPTION_MARKER = "Exception:"
}
