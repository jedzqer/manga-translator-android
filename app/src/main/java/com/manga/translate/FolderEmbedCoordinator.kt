package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class FolderEmbedCoordinator(
    context: Context,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    private val prefs: SharedPreferences,
    private val embeddedStateStore: EmbeddedStateStore,
    private val ui: LibraryUiCallbacks
) {
    private val appContext = context.applicationContext
    private var activeJob: Job? = null

    fun getEmbedThreadCount(): Int {
        val saved = prefs.getInt(KEY_EMBED_THREADS, DEFAULT_EMBED_THREADS)
        return normalizeEmbedThreads(saved)
    }

    fun embedFolder(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        embedThreads: Int,
        onSetActionsEnabled: (Boolean) -> Unit
    ) {
        if (activeJob?.isActive == true) {
            ui.showToast(R.string.folder_embed_running)
            return
        }
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }

        val normalizedThreads = normalizeEmbedThreads(embedThreads)
        prefs.edit().putInt(KEY_EMBED_THREADS, normalizedThreads).apply()

        onSetActionsEnabled(false)
        TranslationKeepAliveService.start(
            appContext,
            appContext.getString(R.string.embed_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message),
            appContext.getString(R.string.folder_embed_progress, 0, images.size)
        )
        TranslationKeepAliveService.updateStatus(
            appContext,
            appContext.getString(R.string.folder_embed_progress, 0, images.size),
            appContext.getString(R.string.embed_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message)
        )
        activeJob = scope.launch {
            try {
                val result = embedInternal(folder, images, normalizedThreads) { done, total ->
                    withContext(Dispatchers.Main) {
                        val progressText = appContext.getString(R.string.folder_embed_progress, done, total)
                        ui.setFolderStatus(progressText)
                        TranslationKeepAliveService.updateProgress(
                            appContext,
                            done,
                            total,
                            progressText,
                            appContext.getString(R.string.embed_keepalive_title),
                            appContext.getString(R.string.translation_keepalive_message)
                        )
                    }
                }
                when (result) {
                    is EmbedResult.Success -> {
                        ui.setFolderStatus(appContext.getString(R.string.folder_embed_done))
                        ui.showToast(R.string.folder_embed_done)
                    }
                    is EmbedResult.Failure -> {
                        val message = result.message.ifBlank { appContext.getString(R.string.folder_embed_failed) }
                        ui.setFolderStatus(appContext.getString(R.string.folder_embed_failed))
                        ui.showToastMessage(message)
                    }
                }
                ui.refreshImages(folder)
                ui.refreshFolders()
            } finally {
                onSetActionsEnabled(true)
                TranslationKeepAliveService.stop(appContext)
            }
        }
    }

    fun cancelEmbed(
        scope: CoroutineScope,
        folder: File,
        onSetActionsEnabled: (Boolean) -> Unit
    ) {
        if (activeJob?.isActive == true) {
            ui.showToast(R.string.folder_embed_running)
            return
        }
        onSetActionsEnabled(false)
        activeJob = scope.launch {
            val success = withContext(Dispatchers.IO) {
                embeddedStateStore.clearEmbeddedState(folder)
                !embeddedStateStore.isEmbedded(folder)
            }
            onSetActionsEnabled(true)
            if (success) {
                ui.setFolderStatus(appContext.getString(R.string.folder_unembed_done))
                ui.showToast(R.string.folder_unembed_done)
            } else {
                ui.setFolderStatus(appContext.getString(R.string.folder_unembed_failed))
                ui.showToast(R.string.folder_unembed_failed)
            }
            ui.refreshImages(folder)
            ui.refreshFolders()
        }
    }

    private suspend fun embedInternal(
        folder: File,
        images: List<File>,
        embedThreads: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): EmbedResult = withContext(Dispatchers.IO) {
        val embeddedDir = embeddedStateStore.embeddedDir(folder)
        if (!embeddedDir.exists() && !embeddedDir.mkdirs()) {
            return@withContext EmbedResult.Failure(appContext.getString(R.string.folder_embed_failed))
        }

        val pending = ArrayList<Pair<File, TranslationResult>>(images.size)
        for (image in images) {
            val translation = translationStore.load(image)
            if (translation == null) {
                continue
            }
            val target = File(embeddedDir, image.name)
            if (target.exists()) {
                continue
            }
            pending.add(image to translation)
        }

        if (pending.isEmpty()) {
            if (images.all { File(embeddedDir, it.name).exists() }) {
                embeddedStateStore.writeEmbeddedState(folder, images.size)
            }
            return@withContext EmbedResult.Success
        }
        val verticalLayoutEnabled = !settingsStore.loadUseHorizontalText()
        val workerCount = minOf(normalizeEmbedThreads(embedThreads), pending.size).coerceAtLeast(1)
        val nextIndex = AtomicInteger(0)
        val doneCount = AtomicInteger(0)
        val failed = AtomicBoolean(false)
        val failedImage = AtomicReference<File?>(null)
        val initFailed = AtomicBoolean(false)

        coroutineScope {
            val workers = List(workerCount) {
                async(Dispatchers.IO) {
                    val detector = try {
                        TextMaskDetector(appContext)
                    } catch (e: Exception) {
                        AppLogger.log("Embed", "Failed to init text mask detector", e)
                        initFailed.set(true)
                        failed.set(true)
                        return@async
                    }
                    val renderer = EmbeddedTextRenderer()

                    while (!failed.get()) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= pending.size) {
                            return@async
                        }
                        val item = pending[index]
                        val image = item.first
                        val translation = item.second
                        val result = runCatching {
                            processSingleImage(
                                sourceImage = image,
                                translation = translation,
                                detector = detector,
                                renderer = renderer,
                                verticalLayoutEnabled = verticalLayoutEnabled,
                                outputDir = embeddedDir
                            )
                        }
                        if (result.isFailure || result.getOrNull() != true) {
                            val throwable = result.exceptionOrNull()
                            if (throwable != null) {
                                AppLogger.log("Embed", "Embed failed for ${image.name}", throwable)
                            } else {
                                AppLogger.log("Embed", "Embed failed for ${image.name}")
                            }
                            failedImage.compareAndSet(null, image)
                            failed.set(true)
                            return@async
                        }
                        val done = doneCount.incrementAndGet()
                        onProgress(done, pending.size)
                    }
                }
            }
            workers.awaitAll()
        }

        if (failed.get()) {
            if (initFailed.get()) {
                return@withContext EmbedResult.Failure(appContext.getString(R.string.folder_embed_failed))
            }
            val image = failedImage.get()
            if (image != null) {
                return@withContext EmbedResult.Failure(
                    appContext.getString(R.string.folder_embed_failed_image, image.name)
                )
            }
            return@withContext EmbedResult.Failure(appContext.getString(R.string.folder_embed_failed))
        }

        if (images.all { File(embeddedDir, it.name).exists() }) {
            embeddedStateStore.writeEmbeddedState(folder, images.size)
        }
        EmbedResult.Success
    }

    private fun processSingleImage(
        sourceImage: File,
        translation: TranslationResult,
        detector: TextMaskDetector,
        renderer: EmbeddedTextRenderer,
        verticalLayoutEnabled: Boolean,
        outputDir: File
    ): Boolean {
        val bitmap = BitmapFactory.decodeFile(sourceImage.absolutePath) ?: return false
        val coverMask = buildBubbleTextCoverMask(
            bitmap = bitmap,
            translation = translation,
            detector = detector
        )
        val expandedCoverMask = if (coverMask.any { it }) {
            dilateMask(coverMask, bitmap.width, bitmap.height, TEXT_COVER_DILATE_ITERATIONS)
        } else {
            coverMask
        }
        val covered = applyPureWhiteCover(bitmap, expandedCoverMask)
        val rendered = renderer.render(covered, translation, verticalLayoutEnabled)
        val outputFile = File(outputDir, sourceImage.name)
        val saved = saveBitmap(outputFile, rendered, sourceImage.name)

        if (rendered !== covered) {
            rendered.recycle()
        }
        if (covered !== bitmap) {
            covered.recycle()
        }
        bitmap.recycle()
        return saved
    }

    private fun buildBubbleTextCoverMask(
        bitmap: Bitmap,
        translation: TranslationResult,
        detector: TextMaskDetector
    ): BooleanArray {
        val width = bitmap.width
        val height = bitmap.height
        val mask = BooleanArray(width * height)

        for (bubble in translation.bubbles) {
            if (bubble.source != BubbleSource.BUBBLE_DETECTOR) continue
            val left = bubble.rect.left.toInt().coerceIn(0, width - 1)
            val top = bubble.rect.top.toInt().coerceIn(0, height - 1)
            val right = bubble.rect.right.toInt().coerceIn(left + 1, width)
            val bottom = bubble.rect.bottom.toInt().coerceIn(top + 1, height)
            val cropW = right - left
            val cropH = bottom - top
            if (cropW <= 1 || cropH <= 1) continue

            val crop = try {
                Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
            } catch (e: Exception) {
                AppLogger.log("Embed", "Create crop failed", e)
                continue
            }

            val cropMask = detector.detectMask(crop)
            for (y in 0 until cropH) {
                val cropRow = y * cropW
                val fullRow = (top + y) * width + left
                for (x in 0 until cropW) {
                    if (!cropMask[cropRow + x]) continue
                    mask[fullRow + x] = true
                }
            }
            crop.recycle()
        }
        return mask
    }

    private fun applyPureWhiteCover(source: Bitmap, coverMask: BooleanArray): Bitmap {
        val prepared = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = source.width
        val height = source.height
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!coverMask[row + x]) continue
                prepared.setPixel(x, y, Color.WHITE)
            }
        }
        return prepared
    }

    private fun dilateMask(mask: BooleanArray, width: Int, height: Int, iterations: Int): BooleanArray {
        var current = mask
        repeat(iterations.coerceAtLeast(1)) {
            val out = current.clone()
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    if (!current[row + x]) continue
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val nrow = ny * width
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx !in 0 until width) continue
                            out[nrow + nx] = true
                        }
                    }
                }
            }
            current = out
        }
        return current
    }

    private fun saveBitmap(target: File, bitmap: Bitmap, sourceName: String): Boolean {
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 95
        return try {
            FileOutputStream(target).use { output ->
                bitmap.compress(format, quality, output)
            }
        } catch (e: Exception) {
            AppLogger.log("Embed", "Save embedded image failed: ${target.absolutePath}", e)
            false
        }
    }

    private sealed class EmbedResult {
        data object Success : EmbedResult()
        data class Failure(val message: String) : EmbedResult()
    }

    companion object {
        private const val TEXT_COVER_DILATE_ITERATIONS = 1
        private const val KEY_EMBED_THREADS = "embed_threads"
        private const val DEFAULT_EMBED_THREADS = 2
        private const val MIN_EMBED_THREADS = 1
        private const val MAX_EMBED_THREADS = 16
    }

    private fun normalizeEmbedThreads(value: Int): Int {
        return value.coerceIn(MIN_EMBED_THREADS, MAX_EMBED_THREADS)
    }
}
