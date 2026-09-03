package com.manga.translate.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject

/** Creates a portable, lossless backup of the library and app preferences. */
class AppBackupManager(private val context: Context) {
    data class ImportResult(val mangaFiles: Int, val preferenceFiles: Int)

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                addBytes(
                    zip,
                    "backup_manifest.json",
                    JSONObject().put("format", BACKUP_FORMAT).put("version", BACKUP_VERSION)
                        .toString().toByteArray()
                )
                val mangaRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, MANGA_LIBRARY_ROOT)
                val mtimes = LinkedHashMap<String, Long>()
                addDirectory(zip, mangaRoot, MANGA_LIBRARY_ROOT) { path, file ->
                    mtimes[path.removePrefix("$MANGA_LIBRARY_ROOT/")] = file.lastModified()
                }
                addBytes(zip, MTIMES_ENTRY, mtimesJson(mtimes))
                addBytes(zip, "preferences/${SettingsStore.PREFS_NAME}.json", preferencesJson(
                    context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
                ).toString().toByteArray())
                addBytes(zip, "preferences/library_prefs.json", preferencesJson(
                    context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
                ).toString().toByteArray())
                val profiles = File(context.filesDir, SettingsStore.AI_PROVIDER_PROFILES_FILE_NAME)
                if (profiles.isFile) addFile(zip, profiles, "files/${profiles.name}")
                val fonts = File(context.filesDir, "custom_fonts")
                addDirectory(zip, fonts, "files/custom_fonts")
            }
        } ?: error("Unable to open backup destination")
        1
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val ioScope = this
        val staging = File(context.cacheDir, "backup_import_${System.currentTimeMillis()}")
        staging.mkdirs()
        var entries = 0
        var bytes = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        // Cooperative cancellation: an aborted import must
                        // stop between entries instead of after the whole zip.
                        yield()
                        require(entries <= MAX_ENTRIES) { "Backup contains too many files" }
                        val safeName = validateEntry(entry.name)
                        val target = File(staging, safeName)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count <= 0) break
                                    bytes += count
                                    require(bytes <= MAX_BYTES) { "Backup is too large" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("Unable to open backup source")

            val manifest = File(staging, "backup_manifest.json")
            require(manifest.isFile) { "Backup is missing its manifest" }
            val json = JSONObject(manifest.readText())
            require(json.optString("format") == BACKUP_FORMAT &&
                json.optInt("version", -1) == BACKUP_VERSION) { "Unsupported backup format" }

            var mangaCount = 0
            val stagedManga = File(staging, MANGA_LIBRARY_ROOT)
            if (stagedManga.isDirectory) {
                // Format v2 always ships the mtimes manifest alongside manga_library.
                val stagedMtimes = File(staging, MTIMES_ENTRY)
                require(stagedMtimes.isFile) { "Backup is missing the mtimes manifest" }
                val mtimes = parseMtimesFile(stagedMtimes)
                val destination = File(context.getExternalFilesDir(null) ?: context.filesDir, MANGA_LIBRARY_ROOT)
                val copiedFiles = mutableListOf<File>()
                val copiedDirs = mutableListOf<File>()
                mangaCount = copyTree(stagedManga, destination, copiedFiles, copiedDirs) {
                    ioScope.ensureActive()
                }
                restoreMtimes(destination, mtimes, copiedFiles, copiedDirs)
            }
            var preferenceCount = 0
            listOf(SettingsStore.PREFS_NAME, "library_prefs").forEach { name ->
                val file = File(staging, "preferences/$name.json")
                if (file.isFile) {
                    importPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), JSONObject(file.readText()))
                    preferenceCount++
                }
            }
            val profiles = File(staging, "files/${SettingsStore.AI_PROVIDER_PROFILES_FILE_NAME}")
            if (profiles.isFile) {
                profiles.copyTo(File(context.filesDir, profiles.name), overwrite = true)
                preferenceCount++
            }
            val stagedFonts = File(staging, "files/custom_fonts")
            if (stagedFonts.isDirectory) {
                preferenceCount += copyTree(stagedFonts, File(context.filesDir, "custom_fonts")) {
                    ioScope.ensureActive()
                }
            }
            ImportResult(mangaCount, preferenceCount)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun preferencesJson(prefs: SharedPreferences): JSONObject {
        val values = JSONObject()
        prefs.all.forEach { (key, value) ->
            val item = JSONObject()
            when (value) {
                is Boolean -> item.put("type", "boolean").put("value", value)
                is Int -> item.put("type", "int").put("value", value)
                is Long -> item.put("type", "long").put("value", value)
                is Float -> item.put("type", "float").put("value", value.toDouble())
                is String -> item.put("type", "string").put("value", value)
                is Set<*> -> item.put("type", "string_set").put("value", JSONArray(value.filterIsInstance<String>()))
                else -> return@forEach
            }
            values.put(key, item)
        }
        return JSONObject().put("version", 1).put("values", values)
    }

    private fun importPreferences(prefs: SharedPreferences, root: JSONObject) {
        val values = root.optJSONObject("values") ?: return
        prefs.edit().clear().commit()
        val editor = prefs.edit()
        values.keys().forEach { key ->
            val item = values.optJSONObject(key) ?: return@forEach
            when (item.optString("type")) {
                "boolean" -> editor.putBoolean(key, item.optBoolean("value"))
                "int" -> editor.putInt(key, item.optInt("value"))
                "long" -> editor.putLong(key, item.optLong("value"))
                "float" -> editor.putFloat(key, item.optDouble("value").toFloat())
                "string" -> editor.putString(key, item.optString("value"))
                "string_set" -> editor.putStringSet(key, buildSet {
                    val array = item.optJSONArray("value") ?: return@buildSet
                    for (i in 0 until array.length()) add(array.optString(i))
                })
            }
        }
        editor.commit()
    }

    /**
     * Restores original timestamps for entries copied by this import.
     *
     * Translation and OCR cache validity is checked against the source image's
     * lastModified/size fingerprint (see TranslationMetadata.matchesSource). A
     * plain copy gives every restored file a fresh timestamp, which would mark
     * every restored translation stale; the mtimes manifest shipped with the
     * backup carries the original timestamps so fingerprints keep matching.
     */
    private fun restoreMtimes(
        destinationRoot: File,
        mtimes: Map<String, Long>,
        copiedFiles: List<File>,
        copiedDirs: List<File>
    ) {
        val failures = applyMtimes(destinationRoot, copiedFiles, copiedDirs, mtimes)
        AppLogger.log(
            TAG,
            "Restored library timestamps (files=${copiedFiles.size}, dirs=${copiedDirs.size}, failures=$failures)"
        )
    }

    internal fun applyMtimes(
        destinationRoot: File,
        copiedFiles: List<File>,
        copiedDirs: List<File>,
        mtimes: Map<String, Long>
    ): Int {
        var failures = 0
        for (file in copiedFiles) {
            val millis = mtimes[file.toRelativeString(destinationRoot)] ?: continue
            if (!setLastModifiedVerified(file, millis)) failures++
        }
        // Directories must be restored last: copying entries into them bumps
        // their timestamps, while setting a file's mtime never touches the parent.
        for (dir in copiedDirs) {
            val millis = mtimes[dir.toRelativeString(destinationRoot)] ?: continue
            if (!setLastModifiedVerified(dir, millis)) failures++
        }
        return failures
    }

    private fun setLastModifiedVerified(file: File, millis: Long): Boolean {
        // Some filesystems (e.g. FAT) silently truncate timestamps; verify so
        // affected entries are reported instead of silently breaking fingerprints.
        return file.setLastModified(millis) && file.lastModified() == millis
    }

    internal fun mtimesJson(mtimes: Map<String, Long>): ByteArray {
        val values = JSONObject()
        mtimes.forEach { (path, millis) -> values.put(path, millis) }
        return JSONObject().put("mtimes", values).toString().toByteArray()
    }

    /** Strict parse: the manifest is a required part of the v2 backup format. */
    internal fun parseMtimesFile(file: File): Map<String, Long> {
        val values = requireNotNull(JSONObject(file.readText()).optJSONObject("mtimes")) {
            "Invalid mtimes manifest"
        }
        require(values.length() <= MAX_ENTRIES) { "mtimes manifest too large" }
        return buildMap<String, Long> {
            values.keys().forEach { key ->
                // Manifest is self-generated; reject anything that is not a clean
                // relative path so timestamps can never escape the library root.
                require(runCatching { validateEntry(key) }.getOrNull() == key) {
                    "Invalid mtime path: $key"
                }
                val value = requireNotNull(values.opt(key) as? Number) {
                    "Invalid mtime value for $key"
                }
                put(key, value.toLong())
            }
        }
    }

    private suspend fun addDirectory(
        zip: ZipOutputStream,
        directory: File,
        root: String,
        onEntry: ((path: String, file: File) -> Unit)? = null
    ) {
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { file ->
            // Cooperative cancellation: an aborted export must stop between
            // entries instead of after the whole library.
            yield()
            val path = "$root/${file.name}"
            if (file.isDirectory) {
                onEntry?.invoke(path, file)
                addDirectory(zip, file, path, onEntry)
            } else {
                addFile(zip, file, path)
                onEntry?.invoke(path, file)
            }
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, path: String) {
        zip.putNextEntry(ZipEntry(path))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun validateEntry(name: String): String {
        val normalized = name.replace('\\', '/').trimEnd('/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') &&
            normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid backup path" }
        return normalized
    }

    internal fun copyTree(
        source: File,
        destination: File,
        copiedFiles: MutableList<File>? = null,
        copiedDirs: MutableList<File>? = null,
        checkCancelled: () -> Unit = {}
    ): Int {
        checkCancelled()
        if (source.isDirectory) {
            val created = !destination.exists()
            destination.mkdirs()
            if (created) copiedDirs?.add(destination)
            return source.listFiles()
                ?.sumOf {
                    copyTree(it, File(destination, it.name), copiedFiles, copiedDirs, checkCancelled)
                } ?: 0
        }
        destination.parentFile?.mkdirs()
        // Preserve files already present in the library, especially translated JSON.
        if (destination.exists()) return 0
        source.copyTo(destination, overwrite = false)
        copiedFiles?.add(destination)
        return 1
    }

    companion object {
        private const val TAG = "Backup"
        private const val BACKUP_FORMAT = "manga_translate_backup"
        private const val BACKUP_VERSION = 2
        private const val MANGA_LIBRARY_ROOT = "manga_library"
        private const val MTIMES_ENTRY = "backup_mtimes.json"
        private const val MAX_ENTRIES = 100_000
        private const val MAX_BYTES = 8L * 1024L * 1024L * 1024L
    }
}
