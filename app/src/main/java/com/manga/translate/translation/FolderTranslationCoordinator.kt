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
import com.manga.translate.platform.GlobalTaskProgressStore
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
    val language: TranslationLanguage
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
    private val pendingBubbleRetranslator: PendingBubbleRetranslator? = null
) {
    private data class PreparedCollectionTask(
        val folder: File,
        val allImages: List<File>,
        val pendingImages: List<File>,
        val force: Boolean,
        val fullTranslate: Boolean,
        val glossaryProcessingEnabled: Boolean,
        val useVlDirectTranslate: Boolean,
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

    private data class PageExecutionSummary(
        val hasFailures: Boolean,
        val failedCount: Int
    )

    private data class PageTranslationExecutionResult(
        val result: TranslationResult? = null,
        val glossaryUsed: Map<String, String> = emptyMap(),
        val recoveredFromModelError: Boolean = false
    )

    private data class PreparedStandardPage(
        val image: File,
        val ocrResult: PageOcrResult?
    )

    private data class PipelinedStandardPage(
        val image: File,
        val ocrResult: PageOcrResult?,
        val preparationFailed: Boolean = false
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
    private val translationTargetKey: String
        get() = PromptAssetResolver.translationTargetKey(appContext)

    private fun loadScopedGlossary(folder: File): MutableMap<String, String> {
        return glossaryStore.load(folder, translationTargetKey).toMutableMap()
    }

    // 节流必须关闭（throttleMillis = 0）：mergeGlossary 的写入发生在对应页面
    // *.json 落盘之前，词条增量与页面结果同步持久化；若开启节流，进程被系统
    // 杀死时最近一个窗口内已应用到页面结果、却尚未落盘的词条会永久丢失。
    // 写入仍在 glossaryMutex 之外、Dispatchers.IO 上执行，不会阻塞并发页面。
    private val glossaryWriter = GlossaryWriteCoalescer(
        glossaryStore = glossaryStore,
        targetKeyProvider = { translationTargetKey },
        throttleMillis = 0L
    )

    private suspend fun saveScopedGlossary(
        folder: File,
        glossary: MutableMap<String, String>,
        additions: Map<String, String>
    ) {
        if (additions.isEmpty()) return
        val changed = additions.filter { (key, value) ->
            key.isNotBlank() && value.isNotBlank() && glossary[key] != value
        }
        changed.forEach { (key, value) -> glossary[key] = value }
        if (changed.isNotEmpty()) glossaryWriter.submit(folder, changed)
    }

    /** Lands any coalesced glossary write still pending. Survives cancellation. */
    private suspend fun flushPendingGlossary() {
        withContext(NonCancellable) {
            try {
                glossaryWriter.flushAll()
            } catch (e: Exception) {
                AppLogger.log("Library", "Failed to flush glossary", e)
            }
        }
    }

    private fun loadScopedExtractState(folder: File): MutableSet<String> {
        return extractStateStore.load(folder, translationTargetKey)
    }

    private fun saveScopedExtractState(folder: File, extracted: Set<String>) {
        extractStateStore.save(folder, extracted, translationTargetKey)
    }

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
        flushPendingGlossary()
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
            ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
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
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        if (fullTranslate) {
            return translateFolderFull(scope, folder, images, force, language, onTranslateEnabled)
        } else {
            return translateFolderStandard(
                scope,
                folder,
                images,
                force,
                glossaryProcessingEnabled,
                useVlDirectTranslate,
                language,
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
            ui.setFolderStatus(appContext.getString(R.string.folder_chapters_empty))
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }

        return beginTranslationJob(scope, onTranslateEnabled,
            "Start translating task batch, ${tasks.size} folders",
            onStartupFailure = { e ->
                AppLogger.log("Library", "Failed to start batch translation", e)
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
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
                        sourceTask.useVlDirectTranslate, sourceTask.language
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
                ui.setFolderStatus(
                    if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                        R.string.translation_done
                    )
                )
                if (failed) {
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                } else {
                    GlobalTaskProgressStore.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                }
                onFinished()
            } catch (e: CancellationException) {
                if (cancellationRequested.get()) {
                    AppLogger.log("Library", "Batch translation canceled")
                    ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                    ui.showToast(R.string.translation_canceled)
                    GlobalTaskProgressStore.complete(
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
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
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
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }
        return beginTranslationJob(scope, onTranslateEnabled,
            "Start translating folder ${folder.name}, ${images.size} images",
            onStartupFailure = { e ->
                AppLogger.log("Library", "Failed to start folder translation ${folder.name}", e)
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
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
                    ui.setFolderStatus(appContext.getString(R.string.translation_done))
                    GlobalTaskProgressStore.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                    return@beginTranslationJob
                }
                val glossary = loadScopedGlossary(folder)
                val glossaryMutex = Mutex()
                val preparing = appContext.getString(
                    R.string.folder_translation_preparing_pages,
                    pendingImages.size
                )
                ui.setFolderStatus(preparing)
                TranslationKeepAliveService.updateStatus(
                    appContext,
                    preparing,
                    GlobalTaskProgressStage.PREPARING_TRANSLATION
                )
                val standardExecution = executeConcurrentStandardPages(
                    pages = pendingImages,
                    folder = folder,
                    force = force,
                    glossaryProcessingEnabled = glossaryProcessingEnabled,
                    useVlDirectTranslate = useVlDirectTranslate,
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
                        ui.setFolderStatus(progress)
                        TranslationKeepAliveService.updateProgress(
                            appContext,
                            processedCount,
                            pendingImages.size,
                            progress,
                            appContext.getString(R.string.translation_keepalive_title),
                            appContext.getString(R.string.translation_keepalive_message),
                            failedCount = failedCount,
                            stage = GlobalTaskProgressStage.TRANSLATING
                        )
                    }
                )
                failed = standardExecution.hasFailures
                ui.setFolderStatus(
                    if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                        R.string.translation_done
                    )
                )
                if (failed) {
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                } else {
                    GlobalTaskProgressStore.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                }
                AppLogger.log(
                    "Library",
                    "Folder translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
                finalizeFolderProgress(folder, failed)
                cacheFolderStatusAfterTranslation(folder, images, failed)
                ui.refreshImages(folder)
            } catch (e: LlmRequestException) {
                failed = true
                AppLogger.log("Library", "Translation aborted for folder ${folder.name}", e)
                ui.showApiError(e.errorCode.value, e.responseBody)
                cacheFolderStatusAfterTranslation(folder, images, failed = true)
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
                ui.refreshImages(folder)
            } catch (e: CancellationException) {
                if (cancellationRequested.get()) {
                    cacheFolderStatusAfterTranslation(folder, images, failed = true)
                    AppLogger.log("Library", "Folder translation canceled: ${folder.name}")
                    ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                    ui.showToast(R.string.translation_canceled)
                    GlobalTaskProgressStore.complete(
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
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
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
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ): Job? {
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }
        return beginTranslationJob(scope, onTranslateEnabled,
            "Start full-page translating folder ${folder.name}, ${images.size} images",
            onStartupFailure = { e ->
                AppLogger.log("Library", "Failed to start full-page translation ${folder.name}", e)
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
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
                    ui.setFolderStatus(appContext.getString(R.string.translation_done))
                    GlobalTaskProgressStore.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                    return@beginTranslationJob
                }
                val fullExecution = executeWindowedFullPages(
                    pages = pendingImages,
                    folder = folder,
                    force = force,
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
                            ui.setFolderStatus(progress)
                            TranslationKeepAliveService.updateProgress(
                                appContext,
                                processedCount,
                                pendingImages.size,
                                progress,
                                appContext.getString(R.string.translation_keepalive_title),
                                appContext.getString(R.string.translation_keepalive_message),
                                failedCount = failedCount,
                                stage = GlobalTaskProgressStage.TRANSLATING
                            )
                        }
                    }
                )
                failed = failed || fullExecution.hasFailures
                ui.setFolderStatus(
                    if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                        R.string.translation_done
                    )
                )
                if (failed) {
                    GlobalTaskProgressStore.fail(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_failed)
                    )
                } else {
                    GlobalTaskProgressStore.complete(
                        appContext.getString(R.string.translation_keepalive_title),
                        appContext.getString(R.string.translation_done)
                    )
                }
                AppLogger.log(
                    "Library",
                    "Full-page translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
                finalizeFolderProgress(folder, failed)
                cacheFolderStatusAfterTranslation(folder, images, failed)
                ui.refreshImages(folder)
            } catch (e: LlmRequestException) {
                cacheFolderStatusAfterTranslation(folder, images, failed = true)
                AppLogger.log("Library", "Full-page translation aborted", e)
                ui.showApiError(e.errorCode.value, e.responseBody)
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
                ui.refreshImages(folder)
            } catch (e: CancellationException) {
                if (cancellationRequested.get()) {
                    cacheFolderStatusAfterTranslation(folder, images, failed = true)
                    AppLogger.log("Library", "Full-page translation canceled: ${folder.name}")
                    ui.setFolderStatus(appContext.getString(R.string.translation_canceled))
                    ui.showToast(R.string.translation_canceled)
                    GlobalTaskProgressStore.complete(
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
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
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
        val glossary = loadScopedGlossary(task.folder)
        val glossaryMutex = Mutex()
        return try {
            reportCollectionTranslationPreparing(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                chapterName = task.folder.name,
                pageCount = task.pendingImages.size
            )
            val standardExecution = executeConcurrentStandardPages(
                pages = task.pendingImages,
                folder = task.folder,
                force = task.force,
                glossaryProcessingEnabled = task.glossaryProcessingEnabled,
                useVlDirectTranslate = task.useVlDirectTranslate,
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
            finalizeFolderProgress(task.folder, standardExecution.hasFailures)
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
            val fullExecution = executeWindowedFullPages(
                pages = task.pendingImages,
                folder = task.folder,
                force = task.force,
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
            finalizeFolderProgress(task.folder, fullExecution.hasFailures)
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
        ui.setFolderStatus(left, right)
        TranslationKeepAliveService.updateProgress(
            appContext,
            safeImageIndex,
            safeImageTotal,
            "$left  $chapterName / $imageName",
            appContext.getString(R.string.translation_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message),
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
        ui.setFolderStatus(left, "$right  $preprocess")
        TranslationKeepAliveService.updateProgress(
            appContext,
            safeImageIndex,
            safeImageTotal,
            "$left  $chapterName / $imageName  $preprocess",
            appContext.getString(R.string.translation_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message),
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
        ui.setFolderStatus(left, chapterName)
        TranslationKeepAliveService.updateProgress(
            appContext,
            overallProcessed,
            safeImageTotal,
            "$left  $chapterName",
            appContext.getString(R.string.translation_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message),
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
        ui.setFolderStatus(content, chapterName)
        TranslationKeepAliveService.updateStatus(
            appContext,
            "$content  $chapterName",
            GlobalTaskProgressStage.PREPARING_TRANSLATION
        )
    }

    private suspend fun reportModelError(content: String): ModelErrorAction {
        val resolution = CompletableDeferred<ModelErrorAction>()
        if (!ui.isUiAttached()) {
            AppLogger.log("Library", "Model error dialog skipped because UI is detached")
            return ModelErrorAction.SKIP
        }
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
        val action = withTimeoutOrNull(MODEL_ERROR_RESOLUTION_TIMEOUT_MS) {
            resolution.await()
        }
        if (action == null) {
            AppLogger.log("Library", "Model error dialog timed out; skipping page")
            TranslationKeepAliveService.clearModelErrorAttention(appContext)
            return ModelErrorAction.SKIP
        }
        return action
    }

    private suspend fun executeConcurrentStandardPages(
        pages: List<File>,
        folder: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        val applyCrossPageMerge = !useVlDirectTranslate &&
            shouldApplyCrossPageBubbleMerge(folder)

        if (!useVlDirectTranslate && !applyCrossPageMerge) {
            // Non-webtoon standard mode sends each ready batch while subsequent
            // pages continue OCR through an ordered, bounded queue.
            onCountUpdated(0, 0)
            return executeStandardPagesPipelined(
                pages = pages,
                folder = folder,
                force = force,
                glossaryProcessingEnabled = glossaryProcessingEnabled,
                language = language,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                onPrepareProgress = onPrepareProgress,
                onCountUpdated = onCountUpdated
            )
        }

        if (!applyCrossPageMerge) {
            // Bound retained OCR results independently of user-configured concurrency.
            val batchSize = settingsStore.loadTranslationBatchPages()
            val windowSize = maxOf(
                translationWindowSize(batchSize),
                settingsStore.loadMaxConcurrency().coerceAtLeast(1) * batchSize
            )
            var processed = 0
            var failures = 0
            onCountUpdated(0, 0)
            for (start in pages.indices step windowSize) {
                currentCoroutineContext().ensureActive()
                val window = pages.subList(start, minOf(start + windowSize, pages.size))
                val prepared = prepareStandardPagesConcurrent(
                    window, force, useVlDirectTranslate, language
                ) { count, _, name -> onPrepareProgress(start + count, pages.size, name) }
                val summary = executePreparedStandardPages(
                    window, prepared, folder, force, glossaryProcessingEnabled,
                    useVlDirectTranslate, language, glossary, glossaryMutex
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
            windowSize = translationWindowSize(settingsStore.loadTranslationBatchPages()),
            lookAhead = true,
            prepare = { window, offset ->
                prepareStandardPagesConcurrent(window, force, useVlDirectTranslate, language) { count, _, name ->
                    onPrepareProgress(offset + count, pages.size, name)
                }
            },
            transform = { prepared ->
                val merged = mergeOcrWindow(prepared.map { it?.ocrResult }, pagePositions)
                prepared.mapIndexed { index, page -> page?.copy(ocrResult = merged[index]) }
            },
            process = { prepared, offset ->
                val window = pages.subList(offset, offset + prepared.size)
                val summary = executePreparedStandardPages(
                    window, prepared, folder, force, glossaryProcessingEnabled,
                    useVlDirectTranslate, language, glossary, glossaryMutex
                ) { count, failed -> onCountUpdated(offset + count, failures + failed) }
                failures += summary.failedCount
            }
        )
        return PageExecutionSummary(failures > 0, failures)
    }

    private suspend fun prepareStandardPagesConcurrent(
        pages: List<File>,
        force: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
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
                        language = language
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
     * [translateFolderFull] and [translateCollectionFolderFull]. OCR runs with the
     * same bounded concurrency as the standard-mode prepare phase, while results
     * are collected in the original page order so cross-page bubble merging and
     * glossary text keep the exact ordering of the previous serial implementation.
     *
     * The caller limits this to one window (plus a webtoon lookahead page).
     * Glossary extraction and translation finish before the next window is prepared.
     */
    private suspend fun prepareFullPagesConcurrent(
        pages: List<File>,
        force: Boolean,
        language: TranslationLanguage,
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
                    ) { stage ->
                        reportImagePreprocessStage(image.name, stage)
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

    private fun resolveOcrPrepareConcurrency(): Int {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        return if (ocrSettings.useLocalOcr) {
            LocalOcrConcurrency.resolve(appContext)
                .coerceAtMost(ImageProcessingGuards.decodeConcurrency)
                .coerceAtLeast(1)
        } else {
            ocrSettings.apiOcrConcurrencyLimit.coerceAtLeast(1)
        }
    }

    private suspend fun loadPagePositions(folder: File): Map<File, Int> = withContext(Dispatchers.IO) {
        repository.listImages(folder).withIndex().associate { it.value to it.index }
    }

    private fun shouldApplyCrossPageBubbleMerge(folder: File): Boolean {
        return preferencesGateway.getReadingMode(folder) == FolderReadingMode.WEBTOON_SCROLL
    }

    private suspend fun executePreparedStandardPages(
        pages: List<File>,
        preparedPages: List<PreparedStandardPage?>,
        folder: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
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
                useVlDirectTranslate = useVlDirectTranslate
            ) { image, page, semaphore ->
                if (useVlDirectTranslate) semaphore.withPermit {
                    executeVlPageTranslation(image, language, glossary, glossaryMutex, folder, glossaryProcessingEnabled, force)
                } else executeStandardPageWithModelErrorResolution(
                    semaphore, folder, image, page, force, glossaryProcessingEnabled,
                    language, glossary, glossaryMutex
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
                            executeVlPageTranslation(
                                image, language, glossary, glossaryMutex, folder, glossaryProcessingEnabled, force
                            )
                        }
                    } else {
                        executeStandardPageWithModelErrorResolution(
                            apiSemaphore = apiSemaphore,
                            folder = folder,
                            image = image,
                            page = prepared.ocrResult,
                            force = force,
                            glossaryProcessingEnabled = glossaryProcessingEnabled,
                            language = language,
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

    private suspend fun executeStandardPagesPipelined(
        pages: List<File>,
        folder: File,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        val maxConcurrency = settingsStore.loadMaxConcurrency()
        val windowSize = translationWindowSize(settingsStore.loadTranslationBatchPages())
        val ocrConcurrency = resolveOcrPrepareConcurrency().coerceAtMost(windowSize)
        val apiSemaphore = Semaphore(maxConcurrency)
        val batchSize = settingsStore.loadTranslationBatchPages()
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
                            prepareStandardPageForTranslation(
                                image = image,
                                force = force,
                                useVlDirectTranslate = false,
                                language = language
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
                    recordPageFailure(folder, image, null)
                    reportPageProcessed(failed = true)
                    return
                }
                if (item.ocrResult != null) {
                    progressStore.update(folder, image.name, PageProgressStatus.OCR_DONE)
                }
                if (requestFailed.get()) {
                    markPageAborted(folder, image, hasFailures, requestException)
                    reportPageProcessed(failed = true)
                    return
                }
                progressStore.update(folder, image.name, PageProgressStatus.PENDING)
                if (requestFailed.get()) {
                    markPageAborted(folder, image, hasFailures, requestException)
                    reportPageProcessed(failed = true)
                    return
                }
                var failureMessage: String? = null
                val execution = try {
                    executeStandardPageWithModelErrorResolution(
                        apiSemaphore = apiSemaphore,
                        folder = folder,
                        image = image,
                        page = item.ocrResult,
                        force = force,
                        glossaryProcessingEnabled = glossaryProcessingEnabled,
                        language = language,
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
                    recordPageFailure(
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
                    executeMergedPages(
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
                        executeStandardPageWithModelErrorResolution(
                            semaphore, folder, image, page, force, glossaryProcessingEnabled,
                            language, glossary, glossaryMutex
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

    private suspend fun prepareStandardPageForTranslation(
        image: File,
        force: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage
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
        ) { } ?: return null
        return PreparedStandardPage(image = image, ocrResult = ocrResult)
    }

    /** Shared by folder, collection and batch full translation. Retain only one OCR window. */
    private suspend fun executeWindowedFullPages(
        pages: List<File>,
        folder: File,
        force: Boolean,
        language: TranslationLanguage,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onGlossaryProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        val glossary = loadScopedGlossary(folder)
        val extractState = loadScopedExtractState(folder)
        val glossaryMutex = Mutex()
        val readingMode = preferencesGateway.getReadingMode(folder)
        val mergeAcrossPages = readingMode == FolderReadingMode.WEBTOON_SCROLL
        val pagePositions = if (mergeAcrossPages) loadPagePositions(folder) else emptyMap()
        var failures = 0
        var hasFailures = false
        onCountUpdated(0, 0)
        forEachPreparedPageWindow(
            pages = pages,
            windowSize = translationWindowSize(settingsStore.loadTranslationBatchPages()),
            lookAhead = mergeAcrossPages,
            prepare = { window, offset ->
                prepareFullPagesConcurrent(window, force, language) { count, _, name ->
                    onPrepareProgress(offset + count, pages.size, name)
                }
            },
            transform = { prepared ->
                if (mergeAcrossPages) mergeOcrWindow(prepared, pagePositions) else prepared
            },
            process = { prepared, offset ->
                val ocrResults = prepared.filterNotNull()
                val preprocessFailures = prepared.size - ocrResults.size
                val glossaryPages = ocrResults.filterNot {
                    translationPipeline.hasValidTranslation(it.imageFile) || extractState.contains(it.imageFile.name)
                }
                var extractionComplete = true
                val glossaryImage = glossaryPages.firstOrNull()?.imageFile?.name.orEmpty()
                for (text in glossaryTextBatches(glossaryPages)) {
                    currentCoroutineContext().ensureActive()
                    onGlossaryProgress(offset, pages.size, glossaryImage)
                    while (true) {
                        try {
                            val extracted = executeWithModelResponseRetries("Library") {
                                llmClient.extractGlossary(text, glossary, "prompts/llm_prompts_abstract.json")
                            }
                            if (extracted == null) {
                                extractionComplete = false
                            } else if (extracted.isNotEmpty()) {
                                // Let the writer detect additions before updating the in-memory map.
                                // Existing translations remain authoritative during extraction.
                                val additions = extracted.filterKeys { !glossary.containsKey(it) }
                                saveScopedGlossary(folder, glossary, additions)
                            }
                            break
                        } catch (e: LlmResponseException) {
                            AppLogger.log("Library", "Full-page glossary response invalid", e)
                            if (reportModelError(e.responseContent) == ModelErrorAction.SKIP) {
                                extractionComplete = false
                                hasFailures = true
                                break
                            }
                        }
                    }
                }
                // A page can span several glossary requests. Mark it only after all
                // chunks succeed, so a failed/cancelled extraction remains retryable.
                if (extractionComplete && glossaryPages.isNotEmpty()) {
                    glossaryPages.forEach { extractState.add(it.imageFile.name) }
                    saveScopedExtractState(folder, extractState)
                }
                onGlossaryProgress(offset + prepared.size, pages.size, glossaryImage)
                val summary = executeConcurrentFullPages(
                    pages = ocrResults,
                    folder = folder,
                    promptAsset = "prompts/llm_prompts_FullTrans.json",
                    language = language,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex
                ) { count, failed ->
                    onCountUpdated(offset + preprocessFailures + count, failures + preprocessFailures + failed)
                }
                failures += preprocessFailures + summary.failedCount
                hasFailures = hasFailures || preprocessFailures > 0 || summary.hasFailures
            }
        )
        return PageExecutionSummary(hasFailures, failures)
    }

    private suspend fun executeConcurrentFullPages(
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
                executeFullPageWithModelErrorResolution(
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
                    executeFullPageWithModelErrorResolution(
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

    private suspend fun executeMergedPages(
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
        executeSinglePage: suspend (File, PageOcrResult?, Semaphore) -> PageTranslationExecutionResult
    ): PageExecutionSummary {
        val semaphore = Semaphore(settingsStore.loadMaxConcurrency())
        val progressMutex = Mutex()
        var processedCount = 0
        var failedCount = 0
        val batchSize = settingsStore.loadTranslationBatchPages()
        val batchable = pages.indices.map { index ->
            val partial = !force && hasRefillablePartialTranslation(
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
                            val snapshot = glossaryMutex.withLock { LinkedHashMap(glossary) }
                            val outcomes = translationPipeline.translateImagesWithVl(
                                indices.map { pages[it] }, language, snapshot, glossaryProcessingEnabled
                            )
                            if (outcomes != null && glossaryProcessingEnabled) {
                                outcomes.forEach { mergeGlossary(glossary, it.glossaryUsed, glossaryMutex, folder) }
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
                        executeGuardedTranslation(
                            apiSemaphore = semaphore,
                            execute = {
                                requestException.get()?.let { throw it }
                                val snapshot = glossaryMutex.withLock { LinkedHashMap(glossary) }
                                val outcomes = translationPipeline.translatePagesWithGlossary(
                                    batch, snapshot, promptAsset, language, mode, glossaryProcessingEnabled
                                )
                                if (outcomes != null && glossaryProcessingEnabled) {
                                    mergeGlossary(
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
                                        skipStandardImage(folder, page, language)
                                    } else {
                                        skipFullPageImage(folder, page, promptAsset, language)
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

    private suspend fun recordPageFailure(folder: File, image: File, errorMessage: String?) {
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

    private suspend fun markPageAborted(
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
    private suspend fun finalizeFolderProgress(folder: File, failed: Boolean) {
        glossaryWriter.flush(folder)
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

    private suspend fun executeStandardPageTranslation(
        folder: File,
        image: File,
        page: PageOcrResult?,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex
    ): PageTranslationExecutionResult {
        if (!settingsStore.load().isValid()) {
            throw LlmRequestException(LlmErrorCode.MissingApiSettings, "No configured translation provider")
        }
        if (!force) {
            tryRefillPartial(
                folder = folder,
                image = image,
                language = language,
                promptAsset = STANDARD_PROMPT_ASSET,
                translationMode = "standard",
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                glossaryProcessingEnabled = glossaryProcessingEnabled
            )?.let { return it }
        }
        val resolvedPage = page ?: translationPipeline.ocrImage(
            image,
            force,
            language,
        ) { }
            ?: return PageTranslationExecutionResult()
        var lastResponseException: LlmResponseException? = null
        var lastRequestException: LlmRequestException? = null
        try {
            val glossarySnapshot = glossaryMutex.withLock { LinkedHashMap(glossary) }
            val translated = translationPipeline.translateStandardPageWithGlossary(
                page = resolvedPage,
                imageFile = image,
                glossary = glossarySnapshot,
                language = language,
                glossaryProcessingEnabled = glossaryProcessingEnabled
            ) { }
            if (translated != null) {
                val glossaryUsed = if (glossaryProcessingEnabled) {
                    translated.glossaryUsed
                } else {
                    emptyMap()
                }
                if (glossaryProcessingEnabled) {
                    mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
                }
                AppLogger.log("Library", "Translated ${image.name}")
                return PageTranslationExecutionResult(
                    result = translated.result,
                    glossaryUsed = glossaryUsed
                )
            }
        } catch (e: LlmRequestException) {
            lastRequestException = e
            AppLogger.log("Library", "Translation request failed for ${image.name}", e)
        } catch (e: LlmResponseException) {
            lastResponseException = e
            AppLogger.log("Library", "Translation returned invalid response for ${image.name}", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLogger.log("Library", "Translation threw for ${image.name}", e)
        }
        if (lastResponseException != null) {
            AppLogger.log("Library", "Invalid model response for ${image.name}", lastResponseException)
            throw lastResponseException
        }
        lastRequestException?.let { throw it }
        return PageTranslationExecutionResult()
    }

    private fun hasRefillablePartialTranslation(
        image: File
    ): Boolean {
        val existing = translationPipeline.loadAnyTranslation(image) ?: return false
        return existing.metadata.status == PageTranslationStatus.PARTIAL
    }

    private suspend fun executeFullPageTranslation(
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex
    ): PageTranslationExecutionResult {
        if (!settingsStore.load().isValid()) {
            throw LlmRequestException(LlmErrorCode.MissingApiSettings, "No configured translation provider")
        }
        tryRefillPartial(
            folder = folder,
            image = page.imageFile,
            language = language,
            promptAsset = promptAsset,
            translationMode = "full_page",
            glossary = glossary,
            glossaryMutex = glossaryMutex,
            glossaryProcessingEnabled = true
        )?.let { return it }
        var lastResponseException: LlmResponseException? = null
        var lastRequestException: LlmRequestException? = null
        try {
            val glossarySnapshot = glossaryMutex.withLock { LinkedHashMap(glossary) }
            val translated = translationPipeline.translateFullPageWithGlossary(
                page = page,
                glossary = glossarySnapshot,
                promptAsset = promptAsset,
                language = language
            ) { }
            if (translated != null) {
                val glossaryUsed = translated.glossaryUsed
                mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
                AppLogger.log("Library", "Translated ${page.imageFile.name}")
                return PageTranslationExecutionResult(
                    result = translated.result,
                    glossaryUsed = glossaryUsed
                )
            }
        } catch (e: LlmRequestException) {
            lastRequestException = e
            AppLogger.log("Library", "Translation request failed for ${page.imageFile.name}", e)
        } catch (e: LlmResponseException) {
            lastResponseException = e
            AppLogger.log("Library", "Translation returned invalid response for ${page.imageFile.name}", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLogger.log("Library", "Translation threw for ${page.imageFile.name}", e)
        }
        if (lastResponseException != null) {
            AppLogger.log("Library", "Invalid model response for ${page.imageFile.name}", lastResponseException)
            throw lastResponseException
        }
        lastRequestException?.let { throw it }
        return PageTranslationExecutionResult()
    }

    private suspend fun executeVlPageTranslation(
        image: File,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        folder: File,
        glossaryProcessingEnabled: Boolean,
        force: Boolean
    ): PageTranslationExecutionResult {
        val existing = if (force) null else translationPipeline.loadAnyTranslation(image)
        if (existing != null && (existing.bubbles.none { it.needsTranslationRetry() } || existing.hasCrossPageBubbleGeometry())) {
            return PageTranslationExecutionResult(result = existing)
        }
        val outcome = translationPipeline.translateImageWithVl(
            imageFile = image,
            language = language,
            glossary = glossaryMutex.withLock { LinkedHashMap(glossary) },
            glossaryProcessingEnabled = glossaryProcessingEnabled,
            baseTranslation = existing
        )
        return when {
            outcome.requiresVlModel -> {
                ui.showToast(R.string.folder_vl_model_required)
                throw LlmRequestException(LlmErrorCode.VlModelRequired, image.name)
            }
            outcome.timedOut -> {
                ui.showToast(R.string.floating_translate_timeout)
                throw LlmRequestException(LlmErrorCode.Timeout, image.name)
            }
            outcome.result != null -> {
                if (glossaryProcessingEnabled) mergeGlossary(glossary, outcome.glossaryUsed, glossaryMutex, folder)
                PageTranslationExecutionResult(result = outcome.result)
            }
            else -> PageTranslationExecutionResult()
        }
    }

    private suspend fun tryRefillPartial(
        folder: File,
        image: File,
        language: TranslationLanguage,
        promptAsset: String,
        translationMode: String,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        glossaryProcessingEnabled: Boolean
    ): PageTranslationExecutionResult? {
        val existing = translationPipeline.loadAnyTranslation(image) ?: return null
        if (existing.metadata.status != PageTranslationStatus.PARTIAL) return null
        val preserved = PageTranslationExecutionResult(result = existing)
        val retranslator = pendingBubbleRetranslator ?: return preserved
        // Preserve existing translations even if their geometry cannot be refilled here.
        if (!shouldApplyCrossPageBubbleMerge(folder) && existing.hasCrossPageBubbleGeometry()) {
            return preserved
        }
        val glossarySnapshot = glossaryMutex.withLock { LinkedHashMap(glossary) }
        val outcome = try {
            retranslator.refill(
                imageFile = image,
                baseTranslation = existing,
                glossary = glossarySnapshot,
                language = language,
                promptAsset = promptAsset,
                translationMode = translationMode,
                logTag = "Library",
                discardShortOcr = false,
                glossaryProcessingEnabled = glossaryProcessingEnabled
            )
        } catch (e: LlmResponseException) {
            AppLogger.log("Library", "Partial refill rejected for ${image.name}", e)
            return preserved
        } catch (e: LlmRequestException) {
            throw e
        } ?: return preserved

        val glossaryUsed: Map<String, String> =
            if (glossaryProcessingEnabled) outcome.glossaryUsed else emptyMap()
        if (glossaryProcessingEnabled && glossaryUsed.isNotEmpty()) {
            mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
        }
        AppLogger.log("Library", "Partial refill applied for ${image.name}")
        return PageTranslationExecutionResult(
            result = outcome.translation,
            glossaryUsed = glossaryUsed
        )
    }

    /**
     * Merges page-level glossary additions into the task-scoped glossary.
     *
     * The merge holds [glossaryMutex] only long enough to update the in-memory map
     * and take a snapshot; the `glossary.json` write happens outside the lock via
     * [glossaryWriter] (write-through, no throttle) and lands BEFORE the page's own
     * `*.json` is saved by the caller, so a process death can never leave a saved
     * page whose glossary additions were lost. Previously every successful page
     * wrote the whole glossary while holding the lock, so as the word list grew
     * each page blocked all concurrent pages for the duration of a full serialize
     * + write; the write itself stays outside the lock and on Dispatchers.IO.
     */
    private suspend fun mergeGlossary(
        glossary: MutableMap<String, String>,
        additions: Map<String, String>,
        glossaryMutex: Mutex,
        folder: File
    ) {
        if (additions.isEmpty()) return
        val additionsToSave = glossaryMutex.withLock {
            val changed = additions.filter { (key, value) ->
                key.isNotBlank() && value.isNotBlank() && glossary[key] != value
            }
            changed.forEach { (key, value) -> glossary[key] = value }
            changed
        }
        if (additionsToSave.isNotEmpty()) glossaryWriter.submit(folder, additionsToSave)
    }

    private suspend fun skipStandardImage(
        folder: File,
        page: PageOcrResult,
        language: TranslationLanguage
    ) {
        val blank = translationPipeline.buildBlankTranslationResult(
            page = page,
            mode = TranslationMetadata.MODE_STANDARD,
            promptAsset = STANDARD_PROMPT_ASSET,
            language = language
        )
        val skipped = blank.copy(
            metadata = blank.metadata.copy(status = PageTranslationStatus.SKIPPED)
        )
        translationPipeline.saveResult(page.imageFile, skipped)
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
    }

    /**
     * Shared guarded execution wrapper for both standard and full-page translation.
     * Runs the actual page translation under [apiSemaphore] and, when the model
     * returns an invalid response, asks the user via [reportModelError] whether to
     * retry the page or skip it. SKIP executes [onSkipPage] (which marks the page
     * as skipped) and returns immediately; RETRY loops back into the guarded block.
     */
    private suspend fun <Result> executeGuardedTranslation(
        apiSemaphore: Semaphore,
        execute: suspend () -> Result,
        onSkipPage: suspend () -> Result
    ): Result {
        while (true) {
            try {
                return apiSemaphore.withPermit { execute() }
            } catch (e: LlmResponseException) {
                if (reportModelError(e.responseContent) == ModelErrorAction.SKIP) {
                    return onSkipPage()
                }
            }
        }
    }

    private suspend fun executeStandardPageWithModelErrorResolution(
        apiSemaphore: Semaphore,
        folder: File,
        image: File,
        page: PageOcrResult?,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex
    ): PageTranslationExecutionResult {
        return executeGuardedTranslation(
            apiSemaphore = apiSemaphore,
            execute = {
                executeStandardPageTranslation(
                    folder = folder,
                    image = image,
                    page = page,
                    force = force,
                    glossaryProcessingEnabled = glossaryProcessingEnabled,
                    language = language,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex
                )
            },
            onSkipPage = {
                val pageToSkip = page ?: translationPipeline.ocrImage(
                    image,
                    force,
                    language,
                ) { }
                if (pageToSkip != null) {
                    skipStandardImage(folder, pageToSkip, language)
                    PageTranslationExecutionResult(recoveredFromModelError = true)
                } else {
                    PageTranslationExecutionResult()
                }
            }
        )
    }

    private suspend fun executeFullPageWithModelErrorResolution(
        apiSemaphore: Semaphore,
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex
    ): PageTranslationExecutionResult {
        return executeGuardedTranslation(
            apiSemaphore = apiSemaphore,
            execute = {
                executeFullPageTranslation(
                    folder = folder,
                    page = page,
                    promptAsset = promptAsset,
                    language = language,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex
                )
            },
            onSkipPage = {
                skipFullPageImage(folder, page, promptAsset, language)
                PageTranslationExecutionResult(recoveredFromModelError = true)
            }
        )
    }

    private suspend fun skipFullPageImage(
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage
    ) {
        val blank = translationPipeline.buildBlankTranslationResult(
            page = page,
            mode = TranslationMetadata.MODE_FULL_PAGE,
            promptAsset = promptAsset,
            language = language
        )
        val skipped = blank.copy(
            metadata = blank.metadata.copy(status = PageTranslationStatus.SKIPPED)
        )
        translationPipeline.saveResult(page.imageFile, skipped)
        withContext(Dispatchers.Main) {
            ui.refreshImages(folder)
        }
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
        ui.setFolderStatus(left, imageName)
        val content = if (imageName.isBlank()) left else "$left  $imageName"
        TranslationKeepAliveService.updateProgress(
            appContext,
            safeProcessed,
            safeTotal,
            content,
            appContext.getString(R.string.translation_keepalive_title),
            appContext.getString(R.string.translation_keepalive_message),
            stage = progressStage
        )
    }

    private fun reportImagePreprocessStage(imageName: String, stage: String) {
        ui.setFolderStatus(stage, imageName)
        val content = if (imageName.isBlank()) stage else "$stage  $imageName"
        val progressStage = when {
            stage == appContext.getString(R.string.detecting_bubbles) ->
                GlobalTaskProgressStage.DETECTING_REGIONS
            stage.contains("OCR", ignoreCase = true) -> GlobalTaskProgressStage.OCR
            else -> null
        }
        TranslationKeepAliveService.updateStatus(appContext, content, progressStage)
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
        private const val STANDARD_PROMPT_ASSET = "prompts/llm_prompts.json"
    }
}
