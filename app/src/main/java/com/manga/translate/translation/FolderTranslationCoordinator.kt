package com.manga.translate.translation

import com.manga.translate.model.PageOcrResult
import android.content.Context
import com.manga.translate.R
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.library.LibraryUiCallbacks
import com.manga.translate.network.executeWithModelResponseRetries
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.FolderStatus
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.model.TranslationResult
import com.manga.translate.network.LlmClient
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.ocr.LocalOcrConcurrency
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.GlobalTaskProgressStage
import com.manga.translate.platform.TaskProgressWriter
import com.manga.translate.platform.ImageProcessingGuards
import com.manga.translate.platform.PromptAssetResolver
import com.manga.translate.platform.TranslationCancellationRegistry
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.ExtractStateStore
import com.manga.translate.storage.GlossaryStore
import com.manga.translate.storage.OcrStore
import com.manga.translate.storage.PageProgressStatus
import com.manga.translate.storage.TranslationProgressStore
import com.manga.translate.storage.TranslationStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class FolderTranslationTask(
    val folder: File,
    val images: List<File>,
    val force: Boolean,
    val fullTranslate: Boolean,
    val glossaryProcessingEnabled: Boolean,
    val useVlDirectTranslate: Boolean,
    val language: TranslationLanguage,
    val onlyTranslateBubbles: Boolean = false
)

internal class FolderTranslationCoordinator(
    context: Context,
    private val translationPipeline: TranslationPipeline,
    private val glossaryStore: GlossaryStore,
    private val extractStateStore: ExtractStateStore,
    private val translationStore: TranslationStore,
    private val ocrStore: OcrStore = OcrStore(),
    private val settingsStore: SettingsStore,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val repository: LibraryRepository,
    private val llmClient: LlmClient,
    private val ui: LibraryUiCallbacks,
    private val progressStore: TranslationProgressStore = TranslationProgressStore(),
    private val pendingBubbleRetranslator: PendingBubbleRetranslator? = null,
    private val taskProgress: TaskProgressWriter
) {
    private data class PreparedCollectionTask(
        val folder: File,
        val allImages: List<File>,
        val pendingImages: List<File>,
        val force: Boolean,
        val fullTranslate: Boolean,
        val glossaryProcessingEnabled: Boolean,
        val useVlDirectTranslate: Boolean,
        val onlyTranslateBubbles: Boolean,
        val language: TranslationLanguage
    )

    private enum class CollectionTaskStatus {
        SUCCESS,
        FAILED,
        ABORTED
    }

    private data class CollectionTaskResult(
        val status: CollectionTaskStatus,
        val failedCount: Int = 0
    )

    private enum class ModelErrorAction {
        RETRY,
        SKIP
    }

    private val appContext = context.applicationContext
    private val translationRunning = AtomicBoolean(false)
    private val cancellationRequested = AtomicBoolean(false)
    @Volatile
    private var activeJob: Job? = null
    private var cancellationRegistration: TranslationCancellationRegistry.Registration? = null
    private val glossaryLifecycle = FolderTranslationGlossary(
        glossaryStore, extractStateStore, llmClient,
        targetKeyProvider = { PromptAssetResolver.translationTargetKey(appContext) }
    )
    private val ocrPreparation = FolderOcrPreparation(
        appContext, translationPipeline, settingsStore, ::reportImagePreprocessStage
    )
    private val pageExecutor = FolderPageTranslationExecutor(
        translationPipeline, settingsStore, preferencesGateway, ui,
        pendingBubbleRetranslator, glossaryLifecycle,
        onModelResponseError = { reportModelError(it) == ModelErrorAction.SKIP }
    )
    private val scheduler = FolderTranslationScheduler(
        translationPipeline, settingsStore, progressStore, ocrPreparation,
        pageExecutor, glossaryLifecycle
    )
    private val standardWorkflow = FolderStandardTranslationWorkflow(
        translationPipeline, settingsStore, preferencesGateway, repository,
        progressStore, ocrPreparation, scheduler, pageExecutor
    )
    private val fullWorkflow = FolderFullTranslationWorkflow(
        translationPipeline, settingsStore, preferencesGateway, repository,
        ocrPreparation, scheduler, glossaryLifecycle,
        onModelResponseError = { reportModelError(it) == ModelErrorAction.SKIP }
    )

    private fun cacheFolderStatusAfterTranslation(
        folder: File,
        translatedImages: List<File>,
        failed: Boolean
    ) {
        if (failed) {
            preferencesGateway.setCachedFolderStatus(folder, FolderStatus.UNTRANSLATED)
            return
        }
        val currentImages = repository.listImages(folder)
        val coversFolder = currentImages.isNotEmpty() &&
            currentImages.map { it.absolutePath }.toSet() ==
            translatedImages.map { it.absolutePath }.toSet()
        if (coversFolder) {
            preferencesGateway.setCachedFolderStatus(folder, FolderStatus.TRANSLATED)
        } else if (preferencesGateway.getCachedFolderStatus(folder) == null) {
            preferencesGateway.setCachedFolderStatus(folder, FolderStatus.UNTRANSLATED)
        }
    }

    private fun cacheCollectionStatus(collectionFolder: File) {
        val chapters = repository.listChildFolders(collectionFolder)
        val translated = chapters.isNotEmpty() && chapters.all { chapter ->
            preferencesGateway.getCachedFolderStatus(chapter) == FolderStatus.TRANSLATED
        }
        preferencesGateway.setCachedFolderStatus(
            collectionFolder,
            if (translated) FolderStatus.TRANSLATED else FolderStatus.UNTRANSLATED
        )
    }

    /**
     * Persists any progress still held in memory. Runs as [NonCancellable] so a
     * user cancellation or a Service teardown still lands the last snapshot.
     */
    private suspend fun flushPendingProgress() {
        withContext(NonCancellable) {
            try {
                progressStore.flushAll()
            } catch (e: Exception) {
                AppLogger.log("Library", "Failed to flush translation progress", e)
            }
        }
        glossaryLifecycle.flushPendingGlossary()
    }

    private fun cleanupTranslationState() {
        activeJob = null
        cancellationRegistration?.unregister()
        cancellationRegistration = null
        cancellationRequested.set(false)
        translationRunning.set(false)
    }

    private fun beginTranslationJob(
        scope: CoroutineScope,
        onTranslateEnabled: (Boolean) -> Unit,
        logMessage: String,
        onStartupFailure: (Exception) -> Unit,
        block: suspend CoroutineScope.() -> Unit
    ): Job? {
        if (!translationRunning.compareAndSet(false, true)) {

            return activeJob
        }
        cancellationRequested.set(false)
        cancellationRegistration = TranslationCancellationRegistry.register { cancelActiveTranslation() }
        onTranslateEnabled(false)
        try {
            AppLogger.log("Library", logMessage)
            val job = scope.launch(block = block)
            activeJob = job
            if (cancellationRequested.get()) {
                job.cancel(CancellationException(USER_CANCELED_REASON))
            }
            return job
        } catch (e: Exception) {
            cleanupTranslationState()
            onTranslateEnabled(true)
            onStartupFailure(e)
            return null
        }
    }

    fun translateFolder(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        fullTranslate: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        if (fullTranslate) {
            return translateFolderFull(scope, folder, images, force, onlyTranslateBubbles, language, onTranslateEnabled)
        } else {
            return translateFolderStandard(
                scope,
                folder,
                images,
                force,
                glossaryProcessingEnabled,
                useVlDirectTranslate,
                language,
                onlyTranslateBubbles,
                onTranslateEnabled
            )
        }
    }

    fun translateCollection(
        scope: CoroutineScope,
        collectionFolder: File,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        return translateTaskBatch(
            scope = scope,
            tasks = tasks,
            onTranslateEnabled = onTranslateEnabled,
            onFinished = {
                cacheCollectionStatus(collectionFolder)
                ui.refreshImages(collectionFolder)
            }
        )
    }

    fun translateBatch(
        scope: CoroutineScope,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        return translateTaskBatch(
            scope = scope,
            tasks = tasks,
            onTranslateEnabled = onTranslateEnabled,
            onFinished = { ui.refreshFolders() }
        )
    }

    private fun translateTaskBatch(
        scope: CoroutineScope,
        tasks: List<FolderTranslationTask>,
        onTranslateEnabled: (Boolean) -> Unit,
        onFinished: () -> Unit
    ): Job? {
        if (tasks.isEmpty()) {
            taskProgress.fail(appContext.getString(R.string.translation_keepalive_title), appContext.getString(R.string.folder_chapters_empty))
            return null
        }
        if (!llmClient.isConfigured()) {
            taskProgress.fail(appContext.getString(R.string.translation_keepalive_title), appContext.getString(R.string.missing_api_settings))
            return null
        }

        return beginTranslationJob(scope, onTranslateEnabled,
            "Start translating task batch, ${tasks.size} folders",
            onStartupFailure = { e ->
                AppLogger.log("Library", "Failed to start batch translation", e)

                taskProgress.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
            }
        ) {
            val totalImages = tasks.sumOf { it.images.size }.coerceAtLeast(1)
            var failed = false
            try {
                var translatedImages = 0
                var translatedFailed = 0
                for ((index, sourceTask) in tasks.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    // Inspect only the current folder; thousands of JSON reads must
                    // neither block the main thread nor delay the first translation.
                    val pending = resolvePendingImages(sourceTask.images, sourceTask.force)
                    translatedImages += sourceTask.images.size - pending.size
                    if (pending.isEmpty()) {
                        cacheFolderStatusAfterTranslation(sourceTask.folder, sourceTask.images, false)
                        reportCollectionProgress(
                            index, tasks.size, translatedImages, totalImages,
                            sourceTask.folder.name, ""
                        )
                        continue
                    }
                    val task = PreparedCollectionTask(
                        sourceTask.folder, sourceTask.images, pending, sourceTask.force,
                        sourceTask.fullTranslate, sourceTask.glossaryProcessingEnabled,
                        sourceTask.useVlDirectTranslate, sourceTask.onlyTranslateBubbles, sourceTask.language
                    )
                    val result = try {
                        if (task.fullTranslate) {
                            translateCollectionFolderFull(
                                task = task,
                                chapterIndex = index,
                                chapterTotal = tasks.size,
                                translatedImages = translatedImages,
                                translatedFailed = translatedFailed,
                                totalImages = totalImages
                            )
                        } else {
                            translateCollectionFolderStandard(
                                task = task,
                                chapterIndex = index,
                                chapterTotal = tasks.size,
                                translatedImages = translatedImages,
                                translatedFailed = translatedFailed,
                                totalImages = totalImages
                            )
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            progressStore.flush(task.folder, releaseSnapshot = true)
                        }
                    }
                    when (result.status) {
                        CollectionTaskStatus.SUCCESS -> {
                            cacheFolderStatusAfterTranslation(task.folder, task.allImages, failed = false)
                            translatedImages += task.pendingImages.size
                            translatedFailed += result.failedCount
                        }
                        CollectionTaskStatus.FAILED -> {
                            cacheFolderStatusAfterTranslation(task.folder, task.allImages, failed = true)
                            translatedImages += task.pendingImages.size
                            translatedFailed += result.failedCount
                            failed = true
                        }
                        CollectionTaskStatus.ABORTED -> {
                            cacheFolderStatusAfterTranslation(task.folder, task.allImages, failed = true)
                            failed = true
                            break
                        }
                    }
                }

                if (failed) {
                    taskProgress.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                } else {
                    taskProgress.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                }
                onFinished()
            } catch (e: CancellationException) {
                if (cancellationRequested.get()) {
                    AppLogger.log("Library", "Batch translation canceled")

                    ui.showToast(R.string.translation_canceled)
                    taskProgress.cancel(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_canceled)
                    )
                    onFinished()
                } else {
                    throw e
                }
            } catch (e: Throwable) {
                AppLogger.log("Library", "Batch translation crashed", e)
                failed = true

                taskProgress.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
                onFinished()
            } finally {
                flushPendingProgress()
                cleanupTranslationState()
                onTranslateEnabled(true)
            }
        }
    }

    private fun translateFolderStandard(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        if (images.isEmpty()) {
            taskProgress.fail(appContext.getString(R.string.translation_keepalive_title), appContext.getString(R.string.folder_images_empty))
            return null
        }
        if (!llmClient.isConfigured()) {
            taskProgress.fail(appContext.getString(R.string.translation_keepalive_title), appContext.getString(R.string.missing_api_settings))
            return null
        }
        return beginTranslationJob(scope, onTranslateEnabled,
            "Start translating folder ${folder.name}, ${images.size} images",
            onStartupFailure = { e ->
                AppLogger.log("Library", "Failed to start folder translation ${folder.name}", e)

                taskProgress.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
            }
        ) {
            var failed = false
            try {
                val pendingImages = resolvePendingImages(images, force)
                if (pendingImages.isEmpty()) {
                    cacheFolderStatusAfterTranslation(folder, images, failed = false)

                    taskProgress.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                    return@beginTranslationJob
                }
                val glossary = glossaryLifecycle.loadScopedGlossary(folder)
                val glossaryMutex = Mutex()
                val preparing = appContext.getString(
                    R.string.folder_translation_preparing_pages,
                    pendingImages.size
                )

                taskProgress.show(
                    title = appContext.getString(R.string.translation_keepalive_title),
                    detail = preparing,
                    stage = GlobalTaskProgressStage.PREPARING_TRANSLATION
                )
                val standardExecution = standardWorkflow.execute(
                    pages = pendingImages,
                    folder = folder,
                    force = force,
                    glossaryProcessingEnabled = glossaryProcessingEnabled,
                    useVlDirectTranslate = useVlDirectTranslate,
                    onlyTranslateBubbles = onlyTranslateBubbles,
                    language = language,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex,
                    onPrepareProgress = { processed, total, imageName ->
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            progressStage = GlobalTaskProgressStage.OCR,
                            processed = processed,
                            total = total,
                            imageName = imageName
                        )
                    },
                    onCountUpdated = { processedCount, failedCount ->
                        val progress = appContext.getString(
                            R.string.folder_translation_processed,
                            processedCount,
                            pendingImages.size,
                            failedCount
                        )

                        taskProgress.show(
                            title = appContext.getString(R.string.translation_keepalive_title),
                            detail = progress,
                            progress = processedCount,
                            total = pendingImages.size,
                            failedCount = failedCount,
                            stage = GlobalTaskProgressStage.TRANSLATING
                        )
                    }
                )
                failed = standardExecution.hasFailures

                if (failed) {
                    taskProgress.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                } else {
                    taskProgress.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                }
                AppLogger.log(
                    "Library",
                    "Folder translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
                scheduler.finalizeFolderProgress(folder, failed)
                cacheFolderStatusAfterTranslation(folder, images, failed)
                ui.refreshImages(folder)
            } catch (e: LlmRequestException) {
                failed = true
                AppLogger.log("Library", "Translation aborted for folder ${folder.name}", e)
                ui.showApiError(e.errorCode.value, e.responseBody)
                cacheFolderStatusAfterTranslation(folder, images, failed = true)

                taskProgress.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
                ui.refreshImages(folder)
            } catch (e: CancellationException) {
                if (cancellationRequested.get()) {
                    cacheFolderStatusAfterTranslation(folder, images, failed = true)
                    AppLogger.log("Library", "Folder translation canceled: ${folder.name}")

                    ui.showToast(R.string.translation_canceled)
                    taskProgress.cancel(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_canceled)
                    )
                    ui.refreshImages(folder)
                } else {
                    throw e
                }
            } catch (e: Throwable) {
                AppLogger.log("Library", "Folder translation crashed: ${folder.name}", e)
                failed = true
                cacheFolderStatusAfterTranslation(folder, images, failed = true)

                taskProgress.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
                ui.refreshImages(folder)
            } finally {
                flushPendingProgress()
                cleanupTranslationState()
                onTranslateEnabled(true)
            }
        }
    }

    private fun translateFolderFull(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        onlyTranslateBubbles: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        if (images.isEmpty()) {
            taskProgress.fail(appContext.getString(R.string.translation_keepalive_title), appContext.getString(R.string.folder_images_empty))
            return null
        }
        if (!llmClient.isConfigured()) {
            taskProgress.fail(appContext.getString(R.string.translation_keepalive_title), appContext.getString(R.string.missing_api_settings))
            return null
        }
        return beginTranslationJob(scope, onTranslateEnabled,
            "Start full-page translating folder ${folder.name}, ${images.size} images",
            onStartupFailure = { e ->
                AppLogger.log("Library", "Failed to start full-page translation ${folder.name}", e)

                taskProgress.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
            }
        ) {
            var failed = false
            try {
                val pendingImages = resolvePendingImages(images, force)
                if (pendingImages.isEmpty()) {
                    cacheFolderStatusAfterTranslation(folder, images, failed = false)

                    taskProgress.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                    return@beginTranslationJob
                }
                val fullExecution = fullWorkflow.execute(
                    pages = pendingImages,
                    folder = folder,
                    force = force,
                    onlyTranslateBubbles = onlyTranslateBubbles,
                    language = language,
                    onPrepareProgress = { processed, total, imageName ->
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            progressStage = GlobalTaskProgressStage.OCR,
                            processed = processed,
                            total = total,
                            imageName = imageName
                        )
                    },
                    onGlossaryProgress = { processed, total, imageName ->
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_glossary),
                            progressStage = GlobalTaskProgressStage.GLOSSARY,
                            processed = processed,
                            total = total,
                            imageName = imageName
                        )
                    },
                    onCountUpdated = { processedCount, failedCount ->
                        withContext(Dispatchers.Main) {
                            val progress = appContext.getString(
                                R.string.folder_translation_processed,
                                processedCount,
                                pendingImages.size,
                                failedCount
                            )

                            taskProgress.show(
                                title = appContext.getString(R.string.translation_keepalive_title),
                                detail = progress,
                                progress = processedCount,
                                total = pendingImages.size,
                                failedCount = failedCount,
                                stage = GlobalTaskProgressStage.TRANSLATING
                            )
                        }
                    }
                )
                failed = failed || fullExecution.hasFailures

                if (failed) {
                    taskProgress.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                } else {
                    taskProgress.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                }
                AppLogger.log(
                    "Library",
                    "Full-page translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
                scheduler.finalizeFolderProgress(folder, failed)
                cacheFolderStatusAfterTranslation(folder, images, failed)
                ui.refreshImages(folder)
            } catch (e: LlmRequestException) {
                cacheFolderStatusAfterTranslation(folder, images, failed = true)
                AppLogger.log("Library", "Full-page translation aborted", e)
                ui.showApiError(e.errorCode.value, e.responseBody)

                taskProgress.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
                ui.refreshImages(folder)
            } catch (e: CancellationException) {
                if (cancellationRequested.get()) {
                    cacheFolderStatusAfterTranslation(folder, images, failed = true)
                    AppLogger.log("Library", "Full-page translation canceled: ${folder.name}")

                    ui.showToast(R.string.translation_canceled)
                    taskProgress.cancel(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_canceled)
                    )
                    ui.refreshImages(folder)
                } else {
                    throw e
                }
            } catch (e: Throwable) {
                AppLogger.log("Library", "Full-page translation crashed: ${folder.name}", e)
                failed = true
                cacheFolderStatusAfterTranslation(folder, images, failed = true)

                taskProgress.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
                ui.refreshImages(folder)
            } finally {
                flushPendingProgress()
                cleanupTranslationState()
                onTranslateEnabled(true)
            }
        }
    }

    private suspend fun translateCollectionFolderStandard(
        task: PreparedCollectionTask,
        chapterIndex: Int,
        chapterTotal: Int,
        translatedImages: Int,
        translatedFailed: Int,
        totalImages: Int
    ): CollectionTaskResult {
        val glossary = glossaryLifecycle.loadScopedGlossary(task.folder)
        val glossaryMutex = Mutex()
        return try {
            reportCollectionTranslationPreparing(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                chapterName = task.folder.name,
                pageCount = task.pendingImages.size
            )
            val standardExecution = standardWorkflow.execute(
                pages = task.pendingImages,
                folder = task.folder,
                force = task.force,
                glossaryProcessingEnabled = task.glossaryProcessingEnabled,
                useVlDirectTranslate = task.useVlDirectTranslate,
                onlyTranslateBubbles = task.onlyTranslateBubbles,
                language = task.language,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                onPrepareProgress = { processed, total, imageName ->
                    reportCollectionPreprocessProgress(
                        chapterIndex = chapterIndex,
                        chapterTotal = chapterTotal,
                        imageIndex = translatedImages + processed,
                        imageTotal = totalImages,
                        chapterName = task.folder.name,
                        imageName = imageName,
                        processed = processed,
                        total = total
                    )
                },
                onCountUpdated = { processedCount, failedCount ->
                    reportCollectionTranslationProgress(
                        chapterIndex = chapterIndex,
                        chapterTotal = chapterTotal,
                        translatedImages = translatedImages,
                        translatedFailed = translatedFailed,
                        imageTotal = totalImages,
                        chapterName = task.folder.name,
                        processedCount = processedCount,
                        failedCount = failedCount
                    )
                }
            )
            scheduler.finalizeFolderProgress(task.folder, standardExecution.hasFailures)
            if (standardExecution.hasFailures) {
                CollectionTaskResult(
                    status = CollectionTaskStatus.FAILED,
                    failedCount = standardExecution.failedCount
                )
            } else {
                CollectionTaskResult(
                    status = CollectionTaskStatus.SUCCESS,
                    failedCount = standardExecution.failedCount
                )
            }
        } catch (e: LlmRequestException) {
            AppLogger.log("Library", "Collection translation aborted for ${task.folder.name}", e)
            ui.showApiError(e.errorCode.value, e.responseBody)
            CollectionTaskResult(status = CollectionTaskStatus.ABORTED)
        }
    }

    private suspend fun translateCollectionFolderFull(
        task: PreparedCollectionTask,
        chapterIndex: Int,
        chapterTotal: Int,
        translatedImages: Int,
        translatedFailed: Int,
        totalImages: Int
    ): CollectionTaskResult {
        return try {
            val fullExecution = fullWorkflow.execute(
                pages = task.pendingImages,
                folder = task.folder,
                force = task.force,
                onlyTranslateBubbles = task.onlyTranslateBubbles,
                language = task.language,
                onPrepareProgress = { processed, _, imageName ->
                    reportCollectionProgress(
                        chapterIndex = chapterIndex,
                        chapterTotal = chapterTotal,
                        imageIndex = translatedImages + processed,
                        imageTotal = totalImages,
                        chapterName = task.folder.name,
                        imageName = imageName,
                        stage = GlobalTaskProgressStage.OCR
                    )
                },
                onGlossaryProgress = { processed, total, imageName ->
                    reportCollectionPreprocessProgress(
                        chapterIndex = chapterIndex,
                        chapterTotal = chapterTotal,
                        imageIndex = translatedImages + processed,
                        imageTotal = totalImages,
                        chapterName = task.folder.name,
                        imageName = imageName,
                        processed = processed,
                        total = total,
                        stage = appContext.getString(R.string.folder_preprocess_stage_glossary),
                        progressStage = GlobalTaskProgressStage.GLOSSARY
                    )
                },
                onCountUpdated = { processedCount, failedCount ->
                    reportCollectionTranslationProgress(
                        chapterIndex = chapterIndex,
                        chapterTotal = chapterTotal,
                        translatedImages = translatedImages,
                        translatedFailed = translatedFailed,
                        imageTotal = totalImages,
                        chapterName = task.folder.name,
                        processedCount = processedCount,
                        failedCount = failedCount
                    )
                }
            )
            scheduler.finalizeFolderProgress(task.folder, fullExecution.hasFailures)
            CollectionTaskResult(
                status = if (fullExecution.hasFailures) CollectionTaskStatus.FAILED else CollectionTaskStatus.SUCCESS,
                failedCount = fullExecution.failedCount
            )
        } catch (e: LlmRequestException) {
            AppLogger.log("Library", "Collection full translation aborted for ${task.folder.name}", e)
            ui.showApiError(e.errorCode.value, e.responseBody)
            CollectionTaskResult(status = CollectionTaskStatus.ABORTED)
        }
    }

    private fun reportCollectionProgress(
        chapterIndex: Int,
        chapterTotal: Int,
        imageIndex: Int,
        imageTotal: Int,
        chapterName: String,
        imageName: String,
        stage: GlobalTaskProgressStage? = null
    ) {
        val safeChapterIndex = (chapterIndex + 1).coerceIn(1, chapterTotal.coerceAtLeast(1))
        val safeChapterTotal = chapterTotal.coerceAtLeast(1)
        val safeImageIndex = imageIndex.coerceIn(0, imageTotal.coerceAtLeast(1))
        val safeImageTotal = imageTotal.coerceAtLeast(1)
        val left = appContext.getString(
            R.string.folder_collection_translation_progress,
            safeChapterIndex,
            safeChapterTotal,
            safeImageIndex,
            safeImageTotal
        )
        val right = appContext.getString(
            R.string.folder_collection_translation_target,
            chapterName,
            imageName
        )

        taskProgress.show(
            title = appContext.getString(R.string.translation_keepalive_title),
            detail = "$left  $chapterName / $imageName",
            folderLeft = left,
            folderRight = right,
            progress = safeImageIndex,
            total = safeImageTotal,
            stage = stage
        )
    }

    private fun reportCollectionPreprocessProgress(
        chapterIndex: Int,
        chapterTotal: Int,
        imageIndex: Int,
        imageTotal: Int,
        chapterName: String,
        imageName: String,
        processed: Int,
        total: Int,
        stage: String = appContext.getString(R.string.folder_preprocess_stage_ocr),
        progressStage: GlobalTaskProgressStage = GlobalTaskProgressStage.OCR
    ) {
        val safeChapterIndex = (chapterIndex + 1).coerceIn(1, chapterTotal.coerceAtLeast(1))
        val safeChapterTotal = chapterTotal.coerceAtLeast(1)
        val safeImageIndex = imageIndex.coerceIn(0, imageTotal.coerceAtLeast(1))
        val safeImageTotal = imageTotal.coerceAtLeast(1)
        val safeProcessed = processed.coerceIn(0, total.coerceAtLeast(1))
        val safeTotal = total.coerceAtLeast(1)
        val left = appContext.getString(
            R.string.folder_collection_translation_progress,
            safeChapterIndex,
            safeChapterTotal,
            safeImageIndex,
            safeImageTotal
        )
        val right = appContext.getString(
            R.string.folder_collection_translation_target,
            chapterName,
            imageName
        )
        val preprocess = appContext.getString(
            R.string.folder_preprocess_progress,
            stage,
            safeProcessed,
            safeTotal
        )

        taskProgress.show(
            title = appContext.getString(R.string.translation_keepalive_title),
            detail = "$left  $chapterName / $imageName  $preprocess",
            folderLeft = left,
            folderRight = "$right  $preprocess",
            progress = safeImageIndex,
            total = safeImageTotal,
            stage = progressStage
        )
    }

    private fun reportCollectionTranslationProgress(
        chapterIndex: Int,
        chapterTotal: Int,
        translatedImages: Int,
        translatedFailed: Int,
        imageTotal: Int,
        chapterName: String,
        processedCount: Int,
        failedCount: Int
    ) {
        val safeChapterIndex = (chapterIndex + 1).coerceIn(1, chapterTotal.coerceAtLeast(1))
        val safeChapterTotal = chapterTotal.coerceAtLeast(1)
        val safeProcessed = processedCount.coerceIn(0, imageTotal.coerceAtLeast(1))
        val safeChapterFailed = failedCount.coerceIn(0, safeProcessed)
        val safeImageTotal = imageTotal.coerceAtLeast(1)
        val overallProcessed = (translatedImages + safeProcessed).coerceIn(0, safeImageTotal)
        val overallFailed = (translatedFailed + safeChapterFailed).coerceIn(0, overallProcessed)
        val left = appContext.getString(
            R.string.folder_collection_translation_processed,
            safeChapterIndex,
            safeChapterTotal,
            overallProcessed,
            safeImageTotal,
            overallFailed
        )

        taskProgress.show(
            title = appContext.getString(R.string.translation_keepalive_title),
            detail = "$left  $chapterName",
            folderLeft = left,
            folderRight = chapterName,
            progress = overallProcessed,
            total = safeImageTotal,
            failedCount = overallFailed,
            stage = GlobalTaskProgressStage.TRANSLATING
        )
    }

    private fun reportCollectionTranslationPreparing(
        chapterIndex: Int,
        chapterTotal: Int,
        chapterName: String,
        pageCount: Int
    ) {
        val content = appContext.getString(
            R.string.folder_collection_translation_preparing,
            (chapterIndex + 1).coerceIn(1, chapterTotal.coerceAtLeast(1)),
            chapterTotal.coerceAtLeast(1),
            pageCount.coerceAtLeast(0)
        )

        taskProgress.show(
            title = appContext.getString(R.string.translation_keepalive_title),
            detail = "$content  $chapterName",
            folderLeft = content,
            folderRight = chapterName,
            stage = GlobalTaskProgressStage.PREPARING_TRANSLATION
        )
    }

    private suspend fun reportModelError(content: String): ModelErrorAction {
        val resolution = CompletableDeferred<ModelErrorAction>()
        val appInForeground = ui.isAppInForeground()
        val useSystemOverlay = !appInForeground && ui.canShowSystemOverlay()
        if (!appInForeground && !useSystemOverlay) {
            AppLogger.log(
                "Library",
                "Model error dialog queued for foreground display because library is in background and overlay is unavailable"
            )
            TranslationKeepAliveService.notifyModelErrorNeedsAttention(appContext)
        }
        withContext(Dispatchers.Main) {
            ui.showModelError(
                content = content,
                useSystemOverlay = useSystemOverlay,
                onRetry = {
                    TranslationKeepAliveService.clearModelErrorAttention(appContext)
                    resolution.complete(ModelErrorAction.RETRY)
                },
                onSkip = {
                    TranslationKeepAliveService.clearModelErrorAttention(appContext)
                    resolution.complete(ModelErrorAction.SKIP)
                }
            )
        }
        val action = try {
            withTimeoutOrNull(MODEL_ERROR_RESOLUTION_TIMEOUT_MS) { resolution.await() }
        } finally {
            ui.clearModelErrors()
        }
        if (action == null) {
            AppLogger.log("Library", "Model error dialog timed out; skipping page")
            TranslationKeepAliveService.clearModelErrorAttention(appContext)
            return ModelErrorAction.SKIP
        }
        return action
    }

    private suspend fun resolvePendingImages(
        images: List<File>,
        force: Boolean
    ): List<File> = withContext(Dispatchers.IO) {
        if (force) {
            images
        } else {
            images.filterNot {
                currentCoroutineContext().ensureActive()
                translationPipeline.hasValidTranslation(it)
            }
        }
    }

    private fun reportPreprocessProgress(
        stage: String,
        progressStage: GlobalTaskProgressStage,
        processed: Int,
        total: Int,
        imageName: String = ""
    ) {
        val safeTotal = total.coerceAtLeast(1)
        val safeProcessed = processed.coerceIn(0, safeTotal)
        val left = appContext.getString(
            R.string.folder_preprocess_progress,
            stage,
            safeProcessed,
            safeTotal
        )

        val content = if (imageName.isBlank()) left else "$left  $imageName"
        taskProgress.show(
            title = appContext.getString(R.string.translation_keepalive_title),
            detail = content,
            folderLeft = left,
            folderRight = imageName,
            progress = safeProcessed,
            total = safeTotal,
            stage = progressStage
        )
    }

    private fun reportImagePreprocessStage(imageName: String, stage: String) {

        val content = if (imageName.isBlank()) stage else "$stage  $imageName"
        val progressStage = when {
            stage == appContext.getString(R.string.detecting_bubbles) ->
                GlobalTaskProgressStage.DETECTING_REGIONS
            stage.contains("OCR", ignoreCase = true) -> GlobalTaskProgressStage.OCR
            else -> null
        }
        taskProgress.show(
            title = appContext.getString(R.string.translation_keepalive_title),
            detail = content,
            folderLeft = stage,
            folderRight = imageName,
            stage = progressStage
        )
    }

    private fun cancelActiveTranslation(): Boolean {
        if (!translationRunning.get()) {
            return false
        }
        cancellationRequested.set(true)
        activeJob?.cancel(CancellationException(USER_CANCELED_REASON))
        return true
    }

    private companion object {
        private const val USER_CANCELED_REASON = "user_canceled_translation"
        private const val MODEL_ERROR_RESOLUTION_TIMEOUT_MS = 120_000L
    }
}
