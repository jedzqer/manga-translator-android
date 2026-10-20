package com.manga.translate.storage

import android.graphics.RectF
import android.util.LruCache
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.BubbleTranslationState
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.deriveStatus
import com.manga.translate.platform.AppLogger
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

class TranslationStore {
    private data class CacheEntry(
        val lastModified: Long,
        val fileSize: Long,
        val result: TranslationResult
    )

    private val updatesFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64
    )
    private val loadCache = object : LruCache<String, CacheEntry>(computeLoadCacheSize()) {}
    private val writeLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    val updates: SharedFlow<String> = updatesFlow.asSharedFlow()

    fun load(imageFile: File): TranslationResult? {
        val jsonFile = translationFileFor(imageFile)
        if (!jsonFile.exists()) return null
        val cacheKey = jsonFile.absolutePath
        val cached = synchronized(loadCache) {
            loadCache.get(cacheKey)?.takeIf {
                it.lastModified == jsonFile.lastModified() && it.fileSize == jsonFile.length()
            }
        }
        if (cached != null) return cached.result
        return try {
            val json = JSONObject(jsonFile.readText())
            val metadata = parseMetadata(json.optJSONObject("metadata"))
            val bubblesJson = json.optJSONArray("bubbles") ?: JSONArray()
            val bubbles = ArrayList<BubbleTranslation>(bubblesJson.length())
            for (i in 0 until bubblesJson.length()) {
                val item = bubblesJson.optJSONObject(i) ?: continue
                val id = if (item.has("id")) item.optInt("id") else i
                val rect = RectF(
                    item.optDouble("left").toFloat(),
                    item.optDouble("top").toFloat(),
                    item.optDouble("right").toFloat(),
                    item.optDouble("bottom").toFloat()
                )
                val source = BubbleSource.fromJson(if (item.has("source")) item.optString("source") else null)
                val originalText = if (item.has("originalText")) {
                    item.optString("originalText", "")
                } else {
                    ""
                }
                val translatedText = if (item.has("translatedText")) {
                    item.optString("translatedText", "")
                } else {
                    ""
                }
                val translationState = if (item.has("translationState")) {
                    BubbleTranslationState.fromJson(item.optString("translationState"))
                } else {
                    val legacyText = item.optString("text", "")
                    if (legacyText.isBlank()) {
                        BubbleTranslationState.PENDING
                    } else {
                        BubbleTranslationState.TRANSLATED
                    }
                }
                val maskContourJson = item.optJSONArray("maskContour")
                val maskContour = if (maskContourJson != null && maskContourJson.length() >= 6) {
                    FloatArray(maskContourJson.length()) { i -> maskContourJson.optDouble(i).toFloat() }
                } else null
                val ownerImageName = item.optString("ownerImageName", "").ifBlank { null }
                val bubble = if (
                    item.has("originalText") ||
                    item.has("translatedText") ||
                    item.has("translationState")
                ) {
                    BubbleTranslation(
                        id = id,
                        rect = rect,
                        originalText = originalText,
                        translatedText = translatedText,
                        translationState = translationState,
                        source = source,
                        maskContour = maskContour,
                        ownerImageName = ownerImageName
                    )
                } else {
                    val legacyText = item.optString("text", "")
                    BubbleTranslation.translated(
                        id = id,
                        rect = rect,
                        translatedText = legacyText,
                        source = source,
                        maskContour = maskContour,
                        ownerImageName = ownerImageName
                    )
                }
                bubbles.add(bubble)
            }
            val baseResult = TranslationResult(
                imageName = json.optString("image", imageFile.name),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                bubbles = bubbles,
                metadata = metadata
            )
            val result = if (metadata.status == PageTranslationStatus.UNKNOWN) {
                baseResult.copy(metadata = metadata.copy(status = baseResult.deriveStatus()))
            } else {
                baseResult
            }
            synchronized(loadCache) {
                loadCache.put(
                    cacheKey,
                    CacheEntry(
                        lastModified = jsonFile.lastModified(),
                        fileSize = jsonFile.length(),
                        result = result
                    )
                )
            }
            result
        } catch (e: Exception) {
            AppLogger.log("TranslationStore", "Failed to load ${jsonFile.name}", e)
            null
        }
    }

    fun save(imageFile: File, result: TranslationResult): File {
        val jsonFile = translationFileFor(imageFile)
        val lock = writeLocks.computeIfAbsent(jsonFile.absoluteFile.path) { kotlinx.coroutines.sync.Mutex() }
        return kotlinx.coroutines.runBlocking { lock.withLock { saveLocked(imageFile, result, jsonFile) } }
    }

    private fun saveLocked(imageFile: File, result: TranslationResult, jsonFile: File): File {
        val metadata = normalizeMetadata(imageFile, result.metadata)
        val persistedStatus = if (metadata.status == PageTranslationStatus.UNKNOWN) {
            result.deriveStatus()
        } else {
            metadata.status
        }
        val json = JSONObject()
            .put("image", result.imageName)
            .put("width", result.width)
            .put("height", result.height)
            .put("metadata", JSONObject().apply {
                put("sourceLastModified", metadata.sourceLastModified)
                put("sourceFileSize", metadata.sourceFileSize)
                put("mode", metadata.mode)
                put("language", metadata.language)
                put("promptAsset", metadata.promptAsset)
                metadata.styleFingerprint?.let { put("styleFingerprint", it) }
                put("apiFormat", metadata.apiFormat)
                put("ocrCacheMode", metadata.ocrCacheMode)
                put("version", metadata.version)
                put("status", persistedStatus.jsonValue)
            })
        val bubbles = JSONArray()
        for (bubble in result.bubbles) {
            val item = JSONObject()
                .put("id", bubble.id)
                .put("left", bubble.rect.left)
                .put("top", bubble.rect.top)
                .put("right", bubble.rect.right)
                .put("bottom", bubble.rect.bottom)
                .put("text", bubble.text)
                .put("originalText", bubble.originalText)
                .put("translatedText", bubble.translatedText)
                .put("translationState", bubble.translationState.jsonValue)
                .put("source", bubble.source.jsonValue)
            bubble.ownerImageName?.takeIf { it.isNotBlank() }?.let {
                item.put("ownerImageName", it)
            }
            if (bubble.maskContour != null) {
                val contourArr = JSONArray()
                for (v in bubble.maskContour) contourArr.put(v.toDouble())
                item.put("maskContour", contourArr)
            }
            bubbles.put(item)
        }
        json.put("bubbles", bubbles)
        writeFileAtomically(jsonFile, json.toString())
        synchronized(loadCache) {
            loadCache.put(
                jsonFile.absolutePath,
                CacheEntry(
                    lastModified = jsonFile.lastModified(),
                    fileSize = jsonFile.length(),
                    result = result.copy(metadata = metadata.copy(status = persistedStatus))
                )
            )
        }
        updatesFlow.tryEmit(imageFile.absolutePath)
        return jsonFile
    }

    /** Merges only still-pending bubbles into the latest on-disk result. */
    fun mergePending(
        imageFile: File,
        additions: Map<Int, BubbleTranslation>,
        removedBubbleIds: Set<Int> = emptySet()
    ): TranslationResult? {
        if (additions.isEmpty() && removedBubbleIds.isEmpty()) return load(imageFile)
        val jsonFile = translationFileFor(imageFile)
        val lock = writeLocks.computeIfAbsent(jsonFile.absoluteFile.path) { kotlinx.coroutines.sync.Mutex() }
        return kotlinx.coroutines.runBlocking {
            lock.withLock {
                val latest = load(imageFile) ?: return@withLock null
                val merged = latest.bubbles.mapNotNull { bubble ->
                    if (!bubble.needsTranslationRetry()) return@mapNotNull bubble
                    if (bubble.id in removedBubbleIds) return@mapNotNull null
                    val addition = additions[bubble.id]
                    if (addition != null) bubble.withContentFrom(addition) else bubble
                }
                saveLocked(imageFile, latest.copy(bubbles = merged), jsonFile)
                load(imageFile)
            }
        }
    }

    fun translationFileFor(imageFile: File): File {
        val parent = imageFile.parentFile ?: File(".")
        return File(parent, "${imageFile.nameWithoutExtension}.json")
    }

    private fun computeLoadCacheSize(): Int {
        val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        return when {
            maxMemoryMb >= 512 -> 64
            maxMemoryMb >= 256 -> 48
            maxMemoryMb >= 128 -> 32
            else -> 16
        }
    }

    private fun parseMetadata(json: JSONObject?): TranslationMetadata {
        return TranslationMetadata(
            sourceLastModified = json?.optLong("sourceLastModified") ?: 0L,
            sourceFileSize = json?.optLong("sourceFileSize") ?: 0L,
            mode = json?.optString("mode").orEmpty(),
            language = json?.optString("language").orEmpty(),
            promptAsset = json?.optString("promptAsset").orEmpty(),
            styleFingerprint = json?.optString("styleFingerprint")?.takeIf { it.isNotBlank() },
            apiFormat = json?.optString("apiFormat").orEmpty(),
            ocrCacheMode = json?.optString("ocrCacheMode").orEmpty(),
            version = json?.let { it.optInt("version", TranslationMetadata.CURRENT_VERSION) }
                ?: TranslationMetadata.CURRENT_VERSION,
            status = PageTranslationStatus.fromJson(json?.optString("status"))
        )
    }

    private fun normalizeMetadata(imageFile: File, metadata: TranslationMetadata): TranslationMetadata {
        val fingerprinted = metadata.withSourceFingerprint(imageFile)
        return if (fingerprinted.mode.isNotBlank()) {
            fingerprinted
        } else {
            fingerprinted.copy(mode = TranslationMetadata.MODE_MANUAL)
        }
    }

}
