package com.manga.translate.translation

import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Standard mode: bounded OCR-to-request queue, batching and webtoon windows. */
internal class FolderStandardTranslationWorkflow(
    private val translationPipeline: TranslationPipeline,
    private val settingsStore: SettingsStore,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val repository: LibraryRepository,
    private val progressStore: TranslationProgressStore,
    private val ocrPreparation: FolderOcrPreparation,
    private val scheduler: FolderTranslationScheduler,
    private val pageExecutor: FolderPageTranslationExecutor
) {
    suspend fun execute(
        pages: List<File>,
        folder: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        val fullVisual = preferencesGateway.isVlFullVisualEnabled(folder)
        val applyCrossPageMerge = !useVlDirectTranslate &&
            shouldApplyCrossPageBubbleMerge(folder)

        if (!useVlDirectTranslate && !applyCrossPageMerge) {
            // Non-webtoon standard mode sends each ready batch while subsequent
            // pages continue OCR through an ordered, bounded queue.
            onCountUpdated(0, 0)
            var processed = 0
            var failures = 0
            var hasFailures = false
            while (processed < pages.size) {
                currentCoroutineContext().ensureActive()
                val end = minOf(processed + settingsStore.resolveTranslationProcessingLimit(), pages.size)
                val summary = executeStandardPagesPipelined(
                    pages = pages.subList(processed, end),
                    folder = folder,
                    force = force,
                    glossaryProcessingEnabled = glossaryProcessingEnabled,
                    language = language,
                    onlyTranslateBubbles = onlyTranslateBubbles,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex,
                    onPrepareProgress = { count, _, name -> onPrepareProgress(processed + count, pages.size, name) },
                    onCountUpdated = { count, failed -> onCountUpdated(processed + count, failures + failed) }
                )
                processed = end
                failures += summary.failedCount
                hasFailures = hasFailures || summary.hasFailures
            }
            return PageExecutionSummary(hasFailures, failures)
        }

        if (!applyCrossPageMerge) {
            // Bound retained OCR results independently of user-configured concurrency.
            var processed = 0
            var failures = 0
            onCountUpdated(0, 0)
            while (processed < pages.size) {
                currentCoroutineContext().ensureActive()
                val start = processed
                val windowSize = settingsStore.resolveTranslationProcessingLimit(useVlDirectTranslate)
                val window = pages.subList(start, minOf(start + windowSize, pages.size))
                val prepared = ocrPreparation.prepareStandardPagesConcurrent(
                    window, force, useVlDirectTranslate, language, onlyTranslateBubbles
                ) { count, _, name -> onPrepareProgress(start + count, pages.size, name) }
                val summary = scheduler.executePreparedStandardPages(
                    pages = window,
                    preparedPages = prepared,
                    folder = folder,
                    force = force,
                    glossaryProcessingEnabled = glossaryProcessingEnabled,
                    useVlDirectTranslate = useVlDirectTranslate,
                    fullVisual = fullVisual,
                    language = language,
                    onlyTranslateBubbles = onlyTranslateBubbles,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex
                ) { count, failed -> onCountUpdated(processed + count, failures + failed) }
                processed += window.size
                failures += summary.failedCount
            }
            return PageExecutionSummary(failures > 0, failures)
        }

        val pagePositions = loadPagePositions(folder)
        var failures = 0
        onCountUpdated(0, 0)
        forEachPreparedPageWindow(
            pages = pages,
            resolveWindowSize = { settingsStore.resolveTranslationProcessingLimit() },
            lookAhead = true,
            prepare = { window, offset ->
                ocrPreparation.prepareStandardPagesConcurrent(
                    window, force, useVlDirectTranslate, language, onlyTranslateBubbles
                ) { count, _, name ->
                    onPrepareProgress(offset + count, pages.size, name)
                }
            },
            transform = { prepared ->
                val repaired = repairOcrWindowSeams(prepared.map { it?.ocrResult }, pagePositions) { upper, lower ->
                    translationPipeline.repairPageSeam(upper, lower, language)
                }
                val merged = mergeOcrWindow(repaired, pagePositions)
                prepared.mapIndexed { index, page -> page?.copy(ocrResult = merged[index]) }
            },
            process = { prepared, offset ->
                val window = pages.subList(offset, offset + prepared.size)
                val summary = scheduler.executePreparedStandardPages(
                    pages = window,
                    preparedPages = prepared,
                    folder = folder,
                    force = force,
                    glossaryProcessingEnabled = glossaryProcessingEnabled,
                    useVlDirectTranslate = useVlDirectTranslate,
                    fullVisual = fullVisual,
                    language = language,
                    onlyTranslateBubbles = onlyTranslateBubbles,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex
                ) { count, failed -> onCountUpdated(offset + count, failures + failed) }
                failures += summary.failedCount
            }
        )
        return PageExecutionSummary(failures > 0, failures)
    }


    private suspend fun loadPagePositions(folder: File): Map<File, Int> = withContext(Dispatchers.IO) {
        repository.listImages(folder).withIndex().associate { it.value to it.index }
    }

    private fun shouldApplyCrossPageBubbleMerge(folder: File): Boolean {
        return preferencesGateway.getReadingMode(folder) == FolderReadingMode.WEBTOON_SCROLL
    }


    private suspend fun executeStandardPagesPipelined(
        pages: List<File>,
        folder: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        val maxConcurrency = settingsStore.loadMaxConcurrency()
        val windowSize = pages.size.coerceAtLeast(1)
        val ocrConcurrency = ocrPreparation.resolveOcrPrepareConcurrency().coerceAtMost(windowSize)
        val apiSemaphore = Semaphore(maxConcurrency)
        val batchSize = settingsStore.loadTranslationBatchPages().coerceAtMost(windowSize)
        // Queue at most a window of prepared pages, even for large configured batches.
        val channel = Channel<List<PipelinedStandardPage>>(capacity = 2)
        val preparedCount = AtomicInteger(0)
        val processedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val progressUpdateMutex = Mutex()
        val hasFailures = AtomicBoolean(false)
        val requestFailed = AtomicBoolean(false)
        val requestException = AtomicReference<LlmRequestException?>(null)

        onPrepareProgress(0, pages.size, "")

        coroutineScope {
            suspend fun reportPageProcessed(failed: Boolean) {
                progressUpdateMutex.withLock {
                    if (failed) {
                        failedCount.incrementAndGet()
                    }
                    onCountUpdated(processedCount.incrementAndGet(), failedCount.get())
                }
            }

            val producer = launch {
                val pending = ArrayList<PipelinedStandardPage>(batchSize)
                suspend fun flush() {
                    if (pending.isNotEmpty()) {
                        channel.send(pending.toList())
                        pending.clear()
                    }
                }
                pages.forEachOrderedBounded(ocrConcurrency, prepare = { image ->
                    currentCoroutineContext().ensureActive()
                    val prepared = try {
                        if (requestFailed.get()) {
                            PreparedStandardPage(image, null)
                        } else {
                            onPrepareProgress(preparedCount.get(), pages.size, image.name)
                            ocrPreparation.prepareStandardPageForTranslation(
                                image = image,
                                force = force,
                                useVlDirectTranslate = false,
                                language = language,
                                onlyTranslateBubbles = onlyTranslateBubbles
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AppLogger.log("Library", "Prepare standard page failed for ${image.name}", e)
                        null
                    }
                    val item = if (prepared != null) {
                        PipelinedStandardPage(
                            image = prepared.image,
                            ocrResult = prepared.ocrResult
                        )
                    } else {
                        PipelinedStandardPage(
                            image = image,
                            ocrResult = null,
                            preparationFailed = true
                        )
                    }
                    onPrepareProgress(preparedCount.incrementAndGet(), pages.size, image.name)
                    item
                }, consume = { item ->
                    // Partial results and failed preparation must use the single-page path.
                    if (item.ocrResult == null || item.preparationFailed) {
                        flush()
                        channel.send(listOf(item))
                    } else {
                        pending.add(item)
                        if (pending.size == batchSize) flush()
                    }
                })
                flush()
                channel.close()
            }

            suspend fun processPage(item: PipelinedStandardPage) {
                currentCoroutineContext().ensureActive()
                val image = item.image
                if (item.preparationFailed) {
                    hasFailures.set(true)
                    scheduler.recordPageFailure(folder, image, null)
                    reportPageProcessed(failed = true)
                    return
                }
                if (item.ocrResult != null) {
                    progressStore.update(folder, image.name, PageProgressStatus.OCR_DONE)
                }
                if (requestFailed.get()) {
                    scheduler.markPageAborted(folder, image, hasFailures, requestException)
                    reportPageProcessed(failed = true)
                    return
                }
                progressStore.update(folder, image.name, PageProgressStatus.PENDING)
                if (requestFailed.get()) {
                    scheduler.markPageAborted(folder, image, hasFailures, requestException)
                    reportPageProcessed(failed = true)
                    return
                }
                var failureMessage: String? = null
                val execution = try {
                    pageExecutor.executeStandardPageWithModelErrorResolution(
                        apiSemaphore = apiSemaphore,
                        folder = folder,
                        image = image,
                        page = item.ocrResult,
                        force = force,
                        glossaryProcessingEnabled = glossaryProcessingEnabled,
                        language = language,
                        onlyTranslateBubbles = onlyTranslateBubbles,
                        glossary = glossary,
                        glossaryMutex = glossaryMutex
                    )
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
                    scheduler.recordPageFailure(
                        folder,
                        image,
                        failureMessage ?: requestException.get()?.message
                    )
                    true
                }
                reportPageProcessed(pageFailed)
            }

            suspend fun processBatch(items: List<PipelinedStandardPage>) {
                if (items.size == 1) {
                    processPage(items.single())
                    return
                }
                var reported = 0
                var reportedFailures = 0
                try {
                    scheduler.executeMergedPages(
                        pages = items.map { it.image },
                        preparedPages = items.map { PreparedStandardPage(it.image, it.ocrResult) },
                        folder = folder,
                        force = force,
                        glossaryProcessingEnabled = glossaryProcessingEnabled,
                        promptAsset = STANDARD_PROMPT_ASSET,
                        mode = TranslationMetadata.MODE_STANDARD,
                        language = language,
                        glossary = glossary,
                        glossaryMutex = glossaryMutex,
                        onCountUpdated = { count, failures ->
                            if (count > reported) {
                                progressUpdateMutex.withLock {
                                    if (failures > reportedFailures) hasFailures.set(true)
                                    val totalFailed = failedCount.addAndGet(failures - reportedFailures)
                                    val totalProcessed = processedCount.addAndGet(count - reported)
                                    onCountUpdated(totalProcessed, totalFailed)
                                    reported = count
                                    reportedFailures = failures
                                }
                            }
                        },
                        requestException = requestException
                    ) { image, page, semaphore ->
                        pageExecutor.executeStandardPageWithModelErrorResolution(
                            semaphore, folder, image, page, force, glossaryProcessingEnabled,
                            language, onlyTranslateBubbles, glossary, glossaryMutex
                        )
                    }
                } catch (error: LlmRequestException) {
                    requestException.compareAndSet(null, error)
                    requestFailed.set(true)
                }
            }

            val consumers = List(minOf(maxConcurrency, windowSize / batchSize)) {
                launch {
                    for (item in channel) {
                        processBatch(item)
                    }
                }
            }
            producer.join()
            consumers.joinAll()
        }
        requestException.get()?.let { throw it }
        return PageExecutionSummary(
            hasFailures = hasFailures.get(),
            failedCount = failedCount.get()
        )
    }


}
