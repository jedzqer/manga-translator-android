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
import kotlin.math.sqrt

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
                    val inpainter = try {
                        MiganInpainter(appContext)
                    } catch (e: Exception) {
                        AppLogger.log("Embed", "Failed to init migan inpainter", e)
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
                                inpainter = inpainter,
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
        inpainter: MiganInpainter,
        renderer: EmbeddedTextRenderer,
        verticalLayoutEnabled: Boolean,
        outputDir: File
    ): Boolean {
        val bitmap = BitmapFactory.decodeFile(sourceImage.absolutePath) ?: return false
        val eraseMask = buildEraseMask(
            bitmap = bitmap,
            translation = translation,
            detector = detector,
            includeBubble = { true },
            expandRatio = DEFAULT_BUBBLE_MASK_EXPAND_RATIO
        )
        val prefill = applyUniformWhiteCover(bitmap, eraseMask, translation)
        val inpainted = if (prefill.remainingMask.any { it }) {
            inpainter.inpaint(prefill.preparedBitmap, prefill.remainingMask)
        } else {
            prefill.preparedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        val rendered = renderer.render(inpainted, translation, verticalLayoutEnabled)
        val outputFile = File(outputDir, sourceImage.name)
        val saved = saveBitmap(outputFile, rendered, sourceImage.name)

        if (rendered !== inpainted) {
            rendered.recycle()
        }
        prefill.preparedBitmap.recycle()
        if (inpainted !== bitmap) {
            inpainted.recycle()
        }
        bitmap.recycle()
        return saved
    }

    private fun buildEraseMask(
        bitmap: Bitmap,
        translation: TranslationResult,
        detector: TextMaskDetector,
        includeBubble: (BubbleTranslation) -> Boolean,
        expandRatio: Float
    ): BooleanArray {
        val width = bitmap.width
        val height = bitmap.height
        val mask = BooleanArray(width * height)

        for (bubble in translation.bubbles) {
            if (!includeBubble(bubble)) continue
            val expandBase = maxOf(2f, minOf(bubble.rect.width(), bubble.rect.height()) * expandRatio)
            val left = (bubble.rect.left - expandBase).toInt().coerceIn(0, width - 1)
            val top = (bubble.rect.top - expandBase).toInt().coerceIn(0, height - 1)
            val right = (bubble.rect.right + expandBase).toInt().coerceIn(left + 1, width)
            val bottom = (bubble.rect.bottom + expandBase).toInt().coerceIn(top + 1, height)
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

    private fun applyUniformWhiteCover(
        source: Bitmap,
        eraseMask: BooleanArray,
        translation: TranslationResult
    ): WhiteCoverResult {
        val width = source.width
        val height = source.height
        val remaining = eraseMask.clone()
        val prepared = source.copy(Bitmap.Config.ARGB_8888, true)

        for (bubble in translation.bubbles) {
            val bubbleW = bubble.rect.width().coerceAtLeast(1f)
            val bubbleH = bubble.rect.height().coerceAtLeast(1f)
            val expand = maxOf(WHITE_COVER_MIN_EXPAND, (minOf(bubbleW, bubbleH) * WHITE_COVER_EXPAND_RATIO).toInt())
            val scan = expandRect(
                bubble.rect.left.toInt(),
                bubble.rect.top.toInt(),
                bubble.rect.right.toInt(),
                bubble.rect.bottom.toInt(),
                expand,
                width,
                height
            ) ?: continue

            val maskBounds = findMaskBounds(remaining, width, scan.left, scan.top, scan.right, scan.bottom) ?: continue
            val coverRect = expandRect(
                maskBounds.left,
                maskBounds.top,
                maskBounds.right,
                maskBounds.bottom,
                expand,
                width,
                height
            ) ?: continue
            val sampleRect = expandRect(
                coverRect.left,
                coverRect.top,
                coverRect.right,
                coverRect.bottom,
                expand,
                width,
                height
            ) ?: continue

            val sample = sampleRing(source, coverRect, sampleRect) ?: continue
            if (sample.avgLuma < WHITE_BG_MIN_LUMA) continue
            if (sample.lumaStd > WHITE_BG_MAX_STD) continue
            if (sample.avgColorSpread > WHITE_BG_MAX_SPREAD) continue

            val fillColor = Color.argb(
                255,
                sample.avgR.toInt().coerceIn(0, 255),
                sample.avgG.toInt().coerceIn(0, 255),
                sample.avgB.toInt().coerceIn(0, 255)
            )
            for (y in coverRect.top until coverRect.bottom) {
                val row = y * width
                for (x in coverRect.left until coverRect.right) {
                    val idx = row + x
                    if (!remaining[idx]) continue
                    prepared.setPixel(x, y, fillColor)
                    remaining[idx] = false
                }
            }
        }

        return WhiteCoverResult(prepared, remaining)
    }

    private fun findMaskBounds(
        mask: BooleanArray,
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IntRect? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in top until bottom) {
            val row = y * width
            for (x in left until right) {
                if (!mask[row + x]) continue
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (minX == Int.MAX_VALUE) return null
        return IntRect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun sampleRing(source: Bitmap, inner: IntRect, outer: IntRect): RingSample? {
        var count = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumLuma = 0.0
        var sumLuma2 = 0.0
        var sumSpread = 0.0

        for (y in outer.top until outer.bottom) {
            for (x in outer.left until outer.right) {
                if (x in inner.left until inner.right && y in inner.top until inner.bottom) continue
                val pixel = source.getPixel(x, y)
                val r = Color.red(pixel).toDouble()
                val g = Color.green(pixel).toDouble()
                val b = Color.blue(pixel).toDouble()
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                val spread = maxOf(r, g, b) - minOf(r, g, b)
                sumR += r
                sumG += g
                sumB += b
                sumLuma += luma
                sumLuma2 += luma * luma
                sumSpread += spread
                count += 1
            }
        }
        if (count < MIN_RING_SAMPLE_PIXELS) return null

        val avgR = sumR / count
        val avgG = sumG / count
        val avgB = sumB / count
        val avgLuma = sumLuma / count
        val variance = (sumLuma2 / count) - (avgLuma * avgLuma)
        val std = sqrt(variance.coerceAtLeast(0.0))
        val avgSpread = sumSpread / count
        return RingSample(avgR, avgG, avgB, avgLuma, std, avgSpread)
    }

    private fun expandRect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        expand: Int,
        width: Int,
        height: Int
    ): IntRect? {
        val l = (left - expand).coerceIn(0, width - 1)
        val t = (top - expand).coerceIn(0, height - 1)
        val r = (right + expand).coerceIn(l + 1, width)
        val b = (bottom + expand).coerceIn(t + 1, height)
        if (r - l <= 1 || b - t <= 1) return null
        return IntRect(l, t, r, b)
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

    private data class WhiteCoverResult(
        val preparedBitmap: Bitmap,
        val remainingMask: BooleanArray
    )

    private data class IntRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class RingSample(
        val avgR: Double,
        val avgG: Double,
        val avgB: Double,
        val avgLuma: Double,
        val lumaStd: Double,
        val avgColorSpread: Double
    )

    companion object {
        private const val DEFAULT_BUBBLE_MASK_EXPAND_RATIO = 0.1f
        private const val WHITE_COVER_EXPAND_RATIO = 0.04f
        private const val WHITE_COVER_MIN_EXPAND = 1
        private const val WHITE_BG_MIN_LUMA = 220.0
        private const val WHITE_BG_MAX_STD = 18.0
        private const val WHITE_BG_MAX_SPREAD = 20.0
        private const val MIN_RING_SAMPLE_PIXELS = 24
        private const val KEY_EMBED_THREADS = "embed_threads"
        private const val DEFAULT_EMBED_THREADS = 2
        private const val MIN_EMBED_THREADS = 1
        private const val MAX_EMBED_THREADS = 16
    }

    private fun normalizeEmbedThreads(value: Int): Int {
        return value.coerceIn(MIN_EMBED_THREADS, MAX_EMBED_THREADS)
    }
}
