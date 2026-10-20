package com.manga.translate.translation

import android.content.Context
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.ocr.LocalOcrConcurrency
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.ImageProcessingGuards
import com.manga.translate.settings.SettingsStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/** Bounds OCR preparation independently of AI request concurrency. */
internal class FolderOcrPreparation(
    context: Context,
    private val translationPipeline: TranslationPipeline,
    private val settingsStore: SettingsStore,
    private val onImagePreprocessStage: (String, String) -> Unit
) {
    private val appContext = context.applicationContext

    suspend fun prepareStandardPagesConcurrent(
        pages: List<File>,
        force: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit
    ): List<PreparedStandardPage?> {
        if (useVlDirectTranslate) {
            onPrepareProgress(pages.size, pages.size, "")
            return pages.map { PreparedStandardPage(image = it, ocrResult = null) }
        }
        if (pages.isEmpty()) {
            onPrepareProgress(0, 0, "")
            return emptyList()
        }
        onPrepareProgress(0, pages.size, "")
        // OCR preparation has its own resource budget; AI request concurrency must not throttle it.
        val maxConcurrency = resolveOcrPrepareConcurrency()
        val prepared = ArrayList<PreparedStandardPage?>(pages.size)
        repeat(pages.size) { prepared.add(null) }
        val completedCount = AtomicInteger(0)
        supervisorScope {
            pages.forEachBounded(maxConcurrency) { index, image ->
                currentCoroutineContext().ensureActive()
                // Show which page is running even before the first page finishes.
                onPrepareProgress(completedCount.get(), pages.size, image.name)
                val result = try {
                    prepareStandardPageForTranslation(
                        image = image,
                        force = force,
                        useVlDirectTranslate = false,
                        language = language,
                        onlyTranslateBubbles = onlyTranslateBubbles
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.log("Library", "Prepare standard page failed for ${image.name}", e)
                    null
                }
                prepared[index] = result
                // Count success and failure so progress never freezes on failed pages.
                onPrepareProgress(completedCount.incrementAndGet(), pages.size, image.name)
            }
        }
        return prepared
    }

    /**
     * Shared concurrent OCR pre-processing for full-page translation, used by both
     * folder and collection tasks through [FolderFullTranslationWorkflow]. OCR runs with the
     * same bounded concurrency as the standard-mode prepare phase, while results
     * are collected in the original page order so cross-page bubble merging and
     * glossary text keep the exact ordering of the previous serial implementation.
     *
     * The caller limits this to one window (plus a webtoon lookahead page).
     * Glossary extraction and translation finish before the next window is prepared.
     */
    suspend fun prepareFullPagesConcurrent(
        pages: List<File>,
        force: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit
    ): List<PageOcrResult?> {
        if (pages.isEmpty()) {
            onPrepareProgress(0, 0, "")
            return emptyList()
        }
        onPrepareProgress(0, pages.size, "")
        val maxConcurrency = resolveOcrPrepareConcurrency()
        val results = ArrayList<PageOcrResult?>(pages.size)
        repeat(pages.size) { results.add(null) }
        val completedCount = AtomicInteger(0)
        supervisorScope {
            pages.forEachBounded(maxConcurrency) { index, image ->
                currentCoroutineContext().ensureActive()
                // Show which page is running even before the first page finishes.
                onPrepareProgress(completedCount.get(), pages.size, image.name)
                val result = try {
                    translationPipeline.ocrImage(
                        image,
                        force,
                        language,
                        onlyTranslateBubbles = onlyTranslateBubbles,
                    ) { stage ->
                        onImagePreprocessStage(image.name, stage)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.log("Library", "OCR failed for ${image.name}", e)
                    null
                }
                results[index] = result
                // Count success and failure so progress never freezes on failed pages.
                onPrepareProgress(completedCount.incrementAndGet(), pages.size, image.name)
            }
        }
        return results
    }

    fun resolveOcrPrepareConcurrency(): Int {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        return if (ocrSettings.useLocalOcr) {
            LocalOcrConcurrency.resolve(appContext)
                .coerceAtMost(ImageProcessingGuards.decodeConcurrency)
                .coerceAtLeast(1)
        } else {
            ocrSettings.apiOcrConcurrencyLimit.coerceAtLeast(1)
        }
    }

    suspend fun prepareStandardPageForTranslation(
        image: File,
        force: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false
    ): PreparedStandardPage? {
        if (useVlDirectTranslate) {
            return PreparedStandardPage(image = image, ocrResult = null)
        }
        if (!force && hasRefillablePartialTranslation(image)) {
            return PreparedStandardPage(image = image, ocrResult = null)
        }
        val ocrResult = translationPipeline.ocrImage(
            image,
            force,
            language,
            onlyTranslateBubbles = onlyTranslateBubbles,
        ) { } ?: return null
        return PreparedStandardPage(image = image, ocrResult = ocrResult)
    }

    fun hasRefillablePartialTranslation(
        image: File
    ): Boolean {
        val existing = translationPipeline.loadAnyTranslation(image) ?: return false
        return existing.metadata.status == PageTranslationStatus.PARTIAL
    }

}
