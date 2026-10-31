package com.manga.translate.translation

import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.PageProgressStatus
import com.manga.translate.storage.TranslationProgressStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Schedules bounded AI requests, commits page results, and records failures. */
internal class FolderTranslationScheduler(
    private val translationPipeline: TranslationPipeline,
    private val settingsStore: SettingsStore,
    private val progressStore: TranslationProgressStore,
    private val ocrPreparation: FolderOcrPreparation,
    private val pageExecutor: FolderPageTranslationExecutor,
    private val glossaryLifecycle: FolderTranslationGlossary
) {
    suspend fun executePreparedStandardPages(
        pages: List<File>,
        preparedPages: List<PreparedStandardPage?>,
        folder: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        fullVisual: Boolean = false,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        if (settingsStore.loadTranslationBatchPages() > 1) {
            return executeMergedPages(
                pages = pages,
                preparedPages = preparedPages,
                folder = folder,
                force = force,
                glossaryProcessingEnabled = glossaryProcessingEnabled,
                promptAsset = STANDARD_PROMPT_ASSET,
                mode = TranslationMetadata.MODE_STANDARD,
                language = language,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                onCountUpdated = onCountUpdated,
                fullVisual = fullVisual,
                useVlDirectTranslate = useVlDirectTranslate
            ) { image, page, semaphore ->
                if (useVlDirectTranslate) semaphore.withPermit {
                    pageExecutor.executeVlPageTranslation(image, language, glossary, glossaryMutex, folder, glossaryProcessingEnabled, force, fullVisual)
                } else pageExecutor.executeStandardPageWithModelErrorResolution(
                    semaphore, folder, image, page, force, glossaryProcessingEnabled,
                    language, onlyTranslateBubbles, glossary, glossaryMutex
                )
            }
        }
        val maxConcurrency = settingsStore.loadMaxConcurrency()
        val apiSemaphore = Semaphore(maxConcurrency)
        val processedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val progressUpdateMutex = Mutex()
        val hasFailures = AtomicBoolean(false)
        val requestFailed = AtomicBoolean(false)
        val requestException = AtomicReference<LlmRequestException?>(null)

        supervisorScope {
            suspend fun reportPageProcessed(failed: Boolean) {
                progressUpdateMutex.withLock {
                    if (failed) {
                        failedCount.incrementAndGet()
                    }
                    onCountUpdated(processedCount.incrementAndGet(), failedCount.get())
                }
            }

            pages.forEachBounded(maxConcurrency) { index, image ->
                val prepared = preparedPages.getOrNull(index)
                currentCoroutineContext().ensureActive()

                if (prepared == null) {
                    hasFailures.set(true)
                    recordPageFailure(folder, image, null)
                    reportPageProcessed(failed = true)
                    return@forEachBounded
                }
                if (prepared.ocrResult != null) {
                    progressStore.update(folder, image.name, PageProgressStatus.OCR_DONE)
                }

                if (requestFailed.get()) {
                    markPageAborted(folder, image, hasFailures, requestException)
                    reportPageProcessed(failed = true)
                    return@forEachBounded
                }
                progressStore.update(folder, image.name, PageProgressStatus.PENDING)
                if (requestFailed.get()) {
                    markPageAborted(folder, image, hasFailures, requestException)
                    reportPageProcessed(failed = true)
                    return@forEachBounded
                }
                var failureMessage: String? = null
                val execution = try {
                    if (useVlDirectTranslate) {
                        apiSemaphore.withPermit {
                            pageExecutor.executeVlPageTranslation(
                                image, language, glossary, glossaryMutex, folder, glossaryProcessingEnabled, force, fullVisual
                            )
                        }
                    } else {
                        pageExecutor.executeStandardPageWithModelErrorResolution(
                            apiSemaphore = apiSemaphore,
                            folder = folder,
                            image = image,
                            page = prepared.ocrResult,
                            force = force,
                            glossaryProcessingEnabled = glossaryProcessingEnabled,
                            language = language,
                            onlyTranslateBubbles = onlyTranslateBubbles,
                            glossary = glossary,
                            glossaryMutex = glossaryMutex
                        )
                    }
                } catch (e: LlmRequestException) {
                    requestException.compareAndSet(null, e)
                    requestFailed.set(true)
                    AppLogger.log("Library", "Translation aborted for ${image.name}", e)
                    failureMessage = e.message
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.log("Library", "Translation failed for ${image.name}", e)
                    failureMessage = e.message
                    null
                }
                val pageFailed = if (execution?.result != null) {
                    translationPipeline.saveResult(image, execution.result)
                    val savedStatus = execution.result.metadata.status
                    progressStore.update(
                        folder,
                        image.name,
                        if (savedStatus == PageTranslationStatus.SKIPPED) {
                            PageProgressStatus.SKIPPED
                        } else {
                            PageProgressStatus.SAVED
                        }
                    )
                    false
                } else if (execution?.recoveredFromModelError == true) {
                    progressStore.update(folder, image.name, PageProgressStatus.SKIPPED)
                    false
                } else {
                    hasFailures.set(true)
                    recordPageFailure(
                        folder,
                        image,
                        failureMessage ?: requestException.get()?.message
                    )
                    true
                }
                reportPageProcessed(pageFailed)
            }
        }
        requestException.get()?.let { throw it }
        return PageExecutionSummary(
            hasFailures = hasFailures.get(),
            failedCount = failedCount.get()
        )
    }

    suspend fun executeConcurrentFullPages(
        pages: List<PageOcrResult>,
        folder: File,
        promptAsset: String,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        if (settingsStore.loadTranslationBatchPages() > 1) {
            return executeMergedPages(
                pages = pages.map { it.imageFile },
                preparedPages = pages.map { PreparedStandardPage(it.imageFile, it) },
                folder = folder,
                force = false,
                glossaryProcessingEnabled = true,
                promptAsset = promptAsset,
                mode = TranslationMetadata.MODE_FULL_PAGE,
                language = language,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                onCountUpdated = onCountUpdated
            ) { _, page, semaphore ->
                pageExecutor.executeFullPageWithModelErrorResolution(
                    semaphore, folder, requireNotNull(page), promptAsset, language,
                    glossary, glossaryMutex
                )
            }
        }
        val maxConcurrency = settingsStore.loadMaxConcurrency()
        val semaphore = Semaphore(maxConcurrency)
        val processedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val progressUpdateMutex = Mutex()
        val hasFailures = AtomicBoolean(false)
        val requestFailed = AtomicBoolean(false)
        val requestException = AtomicReference<LlmRequestException?>(null)
        onCountUpdated(0, 0)
        supervisorScope {
            suspend fun reportPageProcessed(failed: Boolean) {
                progressUpdateMutex.withLock {
                    if (failed) {
                        failedCount.incrementAndGet()
                    }
                    onCountUpdated(processedCount.incrementAndGet(), failedCount.get())
                }
            }

            pages.forEachBounded(maxConcurrency) { _, page ->
                currentCoroutineContext().ensureActive()
                if (requestFailed.get()) {
                    markPageAborted(folder, page.imageFile, hasFailures, requestException)
                    reportPageProcessed(failed = true)
                    return@forEachBounded
                }
                progressStore.update(folder, page.imageFile.name, PageProgressStatus.PENDING)
                if (requestFailed.get()) {
                    markPageAborted(folder, page.imageFile, hasFailures, requestException)
                    reportPageProcessed(failed = true)
                    return@forEachBounded
                }
                var failureMessage: String? = null
                val execution = try {
                    pageExecutor.executeFullPageWithModelErrorResolution(
                        apiSemaphore = semaphore,
                        folder = folder,
                        page = page,
                        promptAsset = promptAsset,
                        language = language,
                        glossary = glossary,
                        glossaryMutex = glossaryMutex
                    )
                } catch (e: LlmRequestException) {
                    requestException.compareAndSet(null, e)
                    requestFailed.set(true)
                    AppLogger.log("Library", "Full-page translation aborted for ${page.imageFile.name}", e)
                    failureMessage = e.message
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.log("Library", "Full-page translation failed for ${page.imageFile.name}", e)
                    failureMessage = e.message
                    null
                }
                val pageFailed = if (execution?.result != null) {
                    translationPipeline.saveResult(page.imageFile, execution.result)
                    val savedStatus = execution.result.metadata.status
                    progressStore.update(
                        folder,
                        page.imageFile.name,
                        if (savedStatus == PageTranslationStatus.SKIPPED) {
                            PageProgressStatus.SKIPPED
                        } else {
                            PageProgressStatus.SAVED
                        }
                    )
                    false
                } else if (execution?.recoveredFromModelError == true) {
                    progressStore.update(folder, page.imageFile.name, PageProgressStatus.SKIPPED)
                    false
                } else {
                    hasFailures.set(true)
                    recordPageFailure(
                        folder,
                        page.imageFile,
                        failureMessage ?: requestException.get()?.message
                    )
                    true
                }
                reportPageProcessed(pageFailed)
            }
        }
        requestException.get()?.let { throw it }
        return PageExecutionSummary(
            hasFailures = hasFailures.get(),
            failedCount = failedCount.get()
        )
    }

    suspend fun executeMergedPages(
        pages: List<File>,
        preparedPages: List<PreparedStandardPage?>,
        folder: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        promptAsset: String,
        mode: String,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit,
        requestException: AtomicReference<LlmRequestException?> = AtomicReference(null),
        useVlDirectTranslate: Boolean = false,
        fullVisual: Boolean = false,
        executeSinglePage: suspend (File, PageOcrResult?, Semaphore) -> PageTranslationExecutionResult
    ): PageExecutionSummary {
        val semaphore = Semaphore(settingsStore.loadMaxConcurrency())
        val progressMutex = Mutex()
        var processedCount = 0
        var failedCount = 0
        val batchSize = settingsStore.loadTranslationBatchPages(useVlDirectTranslate)
        val batchable = pages.indices.map { index ->
            val partial = !force && ocrPreparation.hasRefillablePartialTranslation(
                pages[index]
            )
            if (useVlDirectTranslate) {
                preparedPages.getOrNull(index) != null && (force || translationPipeline.loadAnyTranslation(pages[index]) == null)
            } else preparedPages.getOrNull(index)?.ocrResult != null && !partial
        }
        val groups = buildPageTranslationBatches(batchable, batchSize)
        onCountUpdated(0, 0)
        supervisorScope {
            groups.forEachBounded(settingsStore.loadMaxConcurrency()) { _, indices ->
                var failureMessage: String? = null
                val executions = try {
                    currentCoroutineContext().ensureActive()
                    requestException.get()?.let { throw it }
                    indices.forEach { index ->
                        progressStore.update(folder, pages[index].name, PageProgressStatus.PENDING)
                    }
                    if (indices.size == 1) {
                        val index = indices.single()
                        val prepared = preparedPages.getOrNull(index)
                        listOf(
                            if (prepared == null) PageTranslationExecutionResult() else
                                executeSinglePage(pages[index], prepared.ocrResult, semaphore)
                        )
                    } else if (useVlDirectTranslate) {
                        semaphore.withPermit {
                            requestException.get()?.let { throw it }
                            val snapshot = glossaryLifecycle.snapshot(glossary, glossaryMutex)
                            val outcomes = translationPipeline.translateImagesWithVl(
                                indices.map { pages[it] }, language, snapshot, glossaryProcessingEnabled, fullVisual
                            )
                            if (outcomes != null && glossaryProcessingEnabled) {
                                outcomes.forEach { glossaryLifecycle.mergeGlossary(glossary, it.glossaryUsed, glossaryMutex, folder) }
                            }
                            indices.indices.map { position ->
                                val outcome = outcomes?.get(position)
                                if (outcome?.timedOut == true) throw LlmRequestException(LlmErrorCode.Timeout, pages[indices[position]].name)
                                if (outcome?.requiresVlModel == true) throw LlmRequestException(LlmErrorCode.VlModelRequired, pages[indices[position]].name)
                                PageTranslationExecutionResult(result = outcome?.result)
                            }
                        }
                    } else {
                        val batch = indices.map { requireNotNull(preparedPages[it]?.ocrResult) }
                        pageExecutor.executeGuardedTranslation(
                            apiSemaphore = semaphore,
                            execute = {
                                requestException.get()?.let { throw it }
                                val snapshot = glossaryLifecycle.snapshot(glossary, glossaryMutex)
                                val outcomes = translationPipeline.translatePagesWithGlossary(
                                    batch, snapshot, promptAsset, language, mode, glossaryProcessingEnabled
                                )
                                if (outcomes != null && glossaryProcessingEnabled) {
                                    glossaryLifecycle.mergeGlossary(
                                        glossary, outcomes.first().glossaryUsed, glossaryMutex, folder
                                    )
                                }
                                batch.indices.map { index ->
                                    PageTranslationExecutionResult(result = outcomes?.get(index)?.result)
                                }
                            },
                            onSkipPage = {
                                batch.map { page ->
                                    if (mode == TranslationMetadata.MODE_STANDARD) {
                                        pageExecutor.skipStandardImage(folder, page, language)
                                    } else {
                                        pageExecutor.skipFullPageImage(folder, page, promptAsset, language)
                                    }
                                    PageTranslationExecutionResult(recoveredFromModelError = true)
                                }
                            }
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (error is LlmRequestException) requestException.compareAndSet(null, error)
                    failureMessage = error.message
                    AppLogger.log("Library", "Merged page translation failed", error)
                    emptyList()
                }
                indices.forEachIndexed { position, index ->
                    currentCoroutineContext().ensureActive()
                    val image = pages[index]
                    val execution = executions.getOrNull(position)
                    val result = execution?.result
                    val failed = result == null && execution?.recoveredFromModelError != true
                    if (result != null) {
                        translationPipeline.saveResult(image, result)
                        progressStore.update(
                            folder, image.name,
                            if (result.metadata.status == PageTranslationStatus.SKIPPED) {
                                PageProgressStatus.SKIPPED
                            } else {
                                PageProgressStatus.SAVED
                            }
                        )
                    } else if (!failed) {
                        progressStore.update(folder, image.name, PageProgressStatus.SKIPPED)
                    } else {
                        recordPageFailure(folder, image, failureMessage)
                    }
                    progressMutex.withLock {
                        if (failed) failedCount++
                        onCountUpdated(++processedCount, failedCount)
                    }
                }
            }
        }
        requestException.get()?.let { throw it }
        return PageExecutionSummary(hasFailures = failedCount > 0, failedCount = failedCount)
    }

    suspend fun recordPageFailure(folder: File, image: File, errorMessage: String?) {
        progressStore.update(folder, image.name, PageProgressStatus.FAILED, errorMessage)
        val existing = translationPipeline.loadAnyTranslation(image)
        if (existing != null && existing.metadata.status == PageTranslationStatus.SUCCESS) {
            return
        }
        if (existing != null && existing.metadata.isManual()) {
            return
        }
        if (existing == null) {
            // No prior translation file; progress.json alone records the failure.
            return
        }
        val placeholder = existing.copy(
            metadata = existing.metadata.copy(status = PageTranslationStatus.FAILED)
        )
        try {
            translationPipeline.saveResult(image, placeholder)
        } catch (e: Exception) {
            AppLogger.log("Library", "Failed to record FAILED status for ${image.name}", e)
        }
    }

    suspend fun markPageAborted(
        folder: File,
        image: File,
        hasFailures: AtomicBoolean,
        requestException: AtomicReference<LlmRequestException?>
    ) {
        hasFailures.set(true)
        recordPageFailure(folder, image, requestException.get()?.message)
    }

    /**
     * Lands the throttled progress and glossary state for a finished folder.
     *
     * Called at every folder-completion site, so a batch or collection task
     * persists each chapter as it finishes instead of deferring every folder's
     * writes to task teardown.
     */
    suspend fun finalizeFolderProgress(folder: File, failed: Boolean) {
        glossaryLifecycle.flush(folder)
        if (failed) {
            progressStore.flush(folder)
            return
        }
        val progress = progressStore.load(folder)
        val keep = progress.values.any {
            it.status == PageProgressStatus.SKIPPED || it.status == PageProgressStatus.FAILED
        }
        if (keep) {
            progressStore.flush(folder)
        } else {
            progressStore.clear(folder)
        }
    }


}
