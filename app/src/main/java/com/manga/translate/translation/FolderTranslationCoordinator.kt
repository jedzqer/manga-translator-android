package com.manga.translate.translation


import com.manga.translate.model.PageOcrResult
import android.content.Context
import com.manga.translate.R
import com.manga.translate.background.TranslationKeepAliveService
import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.library.LibraryUiCallbacks
import com.manga.translate.detection.RegionDetectionSelection
import com.manga.translate.floating.executeWithModelResponseRetries
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /** Persists the glossary immediately, superseding any coalesced pending write. */
    private suspend fun saveScopedGlossary(folder: File, glossary: Map<String, String>) {
        glossaryWriter.writeNow(folder, LinkedHashMap(glossary))
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
        val preparedTasks = tasks.mapNotNull { task ->
            val pendingImages = resolvePendingImages(
                images = task.images,
                force = task.force,
                fullTranslate = task.fullTranslate,
                useVlDirectTranslate = task.useVlDirectTranslate,
                language = task.language,
                detectionSelection = preferencesGateway.getRegionDetectionSelection(task.folder),
                readingMode = preferencesGateway.getReadingMode(task.folder)
            )
            if (pendingImages.isEmpty()) {
                null
            } else {
                PreparedCollectionTask(
                    folder = task.folder,
                    allImages = task.images,
                    pendingImages = pendingImages,
                    force = task.force,
                    fullTranslate = task.fullTranslate,
                    glossaryProcessingEnabled = task.glossaryProcessingEnabled,
                    useVlDirectTranslate = task.useVlDirectTranslate,
                    language = task.language
                )
            }
        }
        if (preparedTasks.isEmpty()) {
            tasks.forEach { task ->
                cacheFolderStatusAfterTranslation(task.folder, task.images, failed = false)
            }
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            onFinished()
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }

        return beginTranslationJob(scope, onTranslateEnabled,
            "Start translating task batch, ${preparedTasks.size} folders",
            onStartupFailure = { e ->
                AppLogger.log("Library", "Failed to start batch translation", e)
                ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                GlobalTaskProgressStore.fail(
                    appContext.getString(R.string.translation_keepalive_title),
                    appContext.getString(R.string.translation_failed)
                )
            }
        ) {
            val totalImages = preparedTasks.sumOf { it.pendingImages.size }.coerceAtLeast(1)
            var failed = false
            try {
                var translatedImages = 0
                var translatedFailed = 0
                for ((index, task) in preparedTasks.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val result = if (task.fullTranslate) {
                        translateCollectionFolderFull(
                            task = task,
                            chapterIndex = index,
                            chapterTotal = preparedTasks.size,
                            translatedImages = translatedImages,
                            translatedFailed = translatedFailed,
                            totalImages = totalImages
                        )
                    } else {
                        translateCollectionFolderStandard(
                            task = task,
                            chapterIndex = index,
                            chapterTotal = preparedTasks.size,
                            translatedImages = translatedImages,
                            translatedFailed = translatedFailed,
                            totalImages = totalImages
                        )
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
        val pendingImages = resolvePendingImages(
            images = images,
            force = force,
            fullTranslate = false,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language,
            detectionSelection = preferencesGateway.getRegionDetectionSelection(folder),
            readingMode = preferencesGateway.getReadingMode(folder)
        )
        if (pendingImages.isEmpty()) {
            cacheFolderStatusAfterTranslation(folder, images, failed = false)
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }
        return beginTranslationJob(scope, onTranslateEnabled,
            "Start translating folder ${folder.name}, ${pendingImages.size} images",
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
                if (glossary.isNotEmpty()) {
                    saveScopedGlossary(folder, glossary)
                }
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
        val pendingImages = resolvePendingImages(
            images = images,
            force = force,
            fullTranslate = true,
            useVlDirectTranslate = false,
            language = language,
            detectionSelection = preferencesGateway.getRegionDetectionSelection(folder),
            readingMode = preferencesGateway.getReadingMode(folder)
        )
        if (pendingImages.isEmpty()) {
            cacheFolderStatusAfterTranslation(folder, images, failed = false)
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            return null
        }
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return null
        }
        return beginTranslationJob(scope, onTranslateEnabled,
            "Start full-page translating folder ${folder.name}, ${pendingImages.size} images",
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
                val detectionSelection = preferencesGateway.getRegionDetectionSelection(folder)
                val glossary = loadScopedGlossary(folder)
                val extractState = loadScopedExtractState(folder)
                val preparedOcrResults = prepareFullPagesConcurrent(
                    pages = pendingImages,
                    force = force,
                    language = language,
                    detectionSelection = detectionSelection,
                    onPrepareProgress = { processed, total, imageName ->
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            progressStage = GlobalTaskProgressStage.OCR,
                            processed = processed,
                            total = total,
                            imageName = imageName
                        )
                    }
                )
                val ocrResults = ArrayList<PageOcrResult>(preparedOcrResults.size)
                var preprocessFailedCount = 0
                for (result in preparedOcrResults) {
                    if (result != null) {
                        ocrResults.add(result)
                    } else {
                        failed = true
                        preprocessFailedCount++
                    }
                }

                if (ocrResults.isNotEmpty() && shouldApplyCrossPageBubbleMerge(folder)) {
                    val merged = CrossPageBubbleMerger.merge(ocrResults)
                    ocrResults.clear()
                    ocrResults.addAll(merged)
                }

                val glossaryPages = ocrResults.filterNot {
                    translationPipeline.hasValidTranslation(
                        imageFile = it.imageFile,
                        fullTranslate = true,
                        useVlDirectTranslate = false,
                        language = language,
                        detectionSelection = detectionSelection,
                        readingMode = preferencesGateway.getReadingMode(folder)
                    ) ||
                        extractState.contains(it.imageFile.name)
                }
                val glossaryText = buildGlossaryText(glossaryPages)
                if (glossaryText.isNotBlank()) {
                    val glossaryStage = appContext.getString(R.string.folder_preprocess_stage_glossary)
                    val glossaryImage = glossaryPages.firstOrNull()?.imageFile?.name
                    reportPreprocessProgress(
                        stage = glossaryStage,
                        progressStage = GlobalTaskProgressStage.GLOSSARY,
                        processed = 0,
                        total = 1,
                        imageName = glossaryImage.orEmpty()
                    )
                    val abstractPromptAsset = "prompts/llm_prompts_abstract.json"
                    while (true) {
                        try {
                            val extracted = executeWithModelResponseRetries("Library") {
                                llmClient.extractGlossary(glossaryText, glossary, abstractPromptAsset)
                            }
                            if (extracted != null) {
                                if (extracted.isNotEmpty()) {
                                    for ((key, value) in extracted) {
                                        if (!glossary.containsKey(key)) {
                                            glossary[key] = value
                                        }
                                    }
                                    saveScopedGlossary(folder, glossary)
                                }
                                for (page in glossaryPages) {
                                    extractState.add(page.imageFile.name)
                                }
                                saveScopedExtractState(folder, extractState)
                            }
                            break
                        } catch (e: LlmRequestException) {
                            throw e
                        } catch (e: LlmResponseException) {
                            AppLogger.log("Library", "Full-page glossary response invalid", e)
                            if (reportModelError(e.responseContent) == ModelErrorAction.SKIP) {
                                failed = true
                                break
                            }
                        }
                    }
                    reportPreprocessProgress(
                        stage = glossaryStage,
                        progressStage = GlobalTaskProgressStage.GLOSSARY,
                        processed = 1,
                        total = 1,
                        imageName = glossaryImage.orEmpty()
                    )
                }

                val glossaryMutex = Mutex()
                ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
                val fullExecution = executeConcurrentFullPages(
                    pages = ocrResults,
                    folder = folder,
                    promptAsset = "prompts/llm_prompts_FullTrans.json",
                    language = language,
                    detectionSelection = detectionSelection,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex,
                    onCountUpdated = { processedCount, failedCount ->
                        val overallProcessed = preprocessFailedCount + processedCount
                        val overallFailed = preprocessFailedCount + failedCount
                        withContext(Dispatchers.Main) {
                            ui.setFolderStatus(
                                appContext.getString(
                                    R.string.folder_translation_processed,
                                    overallProcessed,
                                    pendingImages.size,
                                    overallFailed
                                )
                            )
                            val progress = appContext.getString(
                                R.string.folder_translation_processed,
                                overallProcessed,
                                pendingImages.size,
                                overallFailed
                            )
                            TranslationKeepAliveService.updateProgress(
                                appContext,
                                overallProcessed,
                                pendingImages.size,
                                progress,
                                appContext.getString(R.string.translation_keepalive_title),
                                appContext.getString(R.string.translation_keepalive_message),
                                failedCount = overallFailed,
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
            if (glossary.isNotEmpty()) {
                saveScopedGlossary(task.folder, glossary)
            }
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
        var failed = false
        val detectionSelection = preferencesGateway.getRegionDetectionSelection(task.folder)
        val glossary = loadScopedGlossary(task.folder)
        val extractState = loadScopedExtractState(task.folder)
        val preparedOcrResults = prepareFullPagesConcurrent(
            pages = task.pendingImages,
            force = task.force,
            language = task.language,
            detectionSelection = detectionSelection,
            onPrepareProgress = { processed, total, imageName ->
                reportCollectionProgress(
                    chapterIndex = chapterIndex,
                    chapterTotal = chapterTotal,
                    imageIndex = translatedImages + processed,
                    imageTotal = totalImages,
                    chapterName = task.folder.name,
                    imageName = imageName,
                    stage = GlobalTaskProgressStage.OCR
                )
            }
        )
        val ocrResults = ArrayList<PageOcrResult>(preparedOcrResults.size)
        var preprocessFailedCount = 0
        for (result in preparedOcrResults) {
            if (result != null) {
                ocrResults.add(result)
            } else {
                failed = true
                preprocessFailedCount++
            }
        }

        if (ocrResults.isNotEmpty() && shouldApplyCrossPageBubbleMerge(task.folder)) {
            val merged = CrossPageBubbleMerger.merge(ocrResults)
            ocrResults.clear()
            ocrResults.addAll(merged)
        }

        val glossaryPages = ocrResults.filterNot {
            translationPipeline.hasValidTranslation(
                imageFile = it.imageFile,
                fullTranslate = true,
                useVlDirectTranslate = false,
                language = task.language,
                detectionSelection = detectionSelection,
                readingMode = preferencesGateway.getReadingMode(task.folder)
            ) || extractState.contains(it.imageFile.name)
        }
        val glossaryText = buildGlossaryText(glossaryPages)
        if (glossaryText.isNotBlank()) {
            val glossaryStage = appContext.getString(R.string.folder_preprocess_stage_glossary)
            val glossaryImage = glossaryPages.firstOrNull()?.imageFile?.name.orEmpty()
            reportCollectionPreprocessProgress(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                imageIndex = translatedImages + task.pendingImages.size,
                imageTotal = totalImages,
                chapterName = task.folder.name,
                imageName = glossaryImage,
                processed = 0,
                total = 1,
                stage = glossaryStage,
                progressStage = GlobalTaskProgressStage.GLOSSARY
            )
            val abstractPromptAsset = "prompts/llm_prompts_abstract.json"
            while (true) {
                try {
                    val extracted = executeWithModelResponseRetries("Library") {
                        llmClient.extractGlossary(glossaryText, glossary, abstractPromptAsset)
                    }
                    if (extracted != null) {
                        if (extracted.isNotEmpty()) {
                            for ((key, value) in extracted) {
                                if (!glossary.containsKey(key)) {
                                    glossary[key] = value
                                }
                            }
                            saveScopedGlossary(task.folder, glossary)
                        }
                        for (page in glossaryPages) {
                            extractState.add(page.imageFile.name)
                        }
                        saveScopedExtractState(task.folder, extractState)
                    }
                    break
                } catch (e: LlmRequestException) {
                    AppLogger.log("Library", "Collection glossary extraction aborted", e)
                    ui.showApiError(e.errorCode.value, e.responseBody)
                    return CollectionTaskResult(status = CollectionTaskStatus.ABORTED)
                } catch (e: LlmResponseException) {
                    AppLogger.log("Library", "Collection glossary response invalid", e)
                    if (reportModelError(e.responseContent) == ModelErrorAction.SKIP) {
                        failed = true
                        break
                    }
                }
            }
            reportCollectionPreprocessProgress(
                chapterIndex = chapterIndex,
                chapterTotal = chapterTotal,
                imageIndex = translatedImages + task.pendingImages.size,
                imageTotal = totalImages,
                chapterName = task.folder.name,
                imageName = glossaryImage,
                processed = 1,
                total = 1,
                stage = glossaryStage,
                progressStage = GlobalTaskProgressStage.GLOSSARY
            )
        }

        val glossaryMutex = Mutex()
        return try {
            val fullExecution = executeConcurrentFullPages(
                pages = ocrResults,
                folder = task.folder,
                promptAsset = "prompts/llm_prompts_FullTrans.json",
                language = task.language,
                detectionSelection = detectionSelection,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                onCountUpdated = { processedCount, failedCount ->
                    reportCollectionTranslationProgress(
                        chapterIndex = chapterIndex,
                        chapterTotal = chapterTotal,
                        translatedImages = translatedImages,
                        translatedFailed = translatedFailed,
                        imageTotal = totalImages,
                        chapterName = task.folder.name,
                        processedCount = preprocessFailedCount + processedCount,
                        failedCount = preprocessFailedCount + failedCount
                    )
                }
            )
            failed = failed || fullExecution.hasFailures
            finalizeFolderProgress(task.folder, failed)
            CollectionTaskResult(
                status = if (failed) CollectionTaskStatus.FAILED else CollectionTaskStatus.SUCCESS,
                failedCount = preprocessFailedCount + fullExecution.failedCount
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
        val detectionSelection = preferencesGateway.getRegionDetectionSelection(folder)
        val applyCrossPageMerge = !useVlDirectTranslate &&
            shouldApplyCrossPageBubbleMerge(folder)

        if (!useVlDirectTranslate && !applyCrossPageMerge) {
            // Non-webtoon standard mode can start LLM translation as soon as the first
            // page finishes OCR instead of waiting for the whole folder to be prepared.
            onCountUpdated(0, 0)
            return executeStandardPagesPipelined(
                pages = pages,
                folder = folder,
                force = force,
                glossaryProcessingEnabled = glossaryProcessingEnabled,
                language = language,
                detectionSelection = detectionSelection,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                onPrepareProgress = onPrepareProgress,
                onCountUpdated = onCountUpdated
            )
        }

        val preparedPages = prepareStandardPagesConcurrent(
            pages = pages,
            force = force,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language,
            detectionSelection = detectionSelection,
            onPrepareProgress = onPrepareProgress
        )
        val mergedPreparedPages = if (applyCrossPageMerge) {
            applyCrossPageBubbleMerge(preparedPages)
        } else {
            preparedPages
        }
        onCountUpdated(0, 0)
        return executePreparedStandardPages(
            pages = pages,
            preparedPages = mergedPreparedPages,
            folder = folder,
            force = force,
            glossaryProcessingEnabled = glossaryProcessingEnabled,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language,
            detectionSelection = detectionSelection,
            glossary = glossary,
            glossaryMutex = glossaryMutex,
            onCountUpdated = onCountUpdated
        )
    }

    private suspend fun prepareStandardPagesConcurrent(
        pages: List<File>,
        force: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection,
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
        // OCR prepare shares local detectors/engines and decode permits; keep it lower than LLM concurrency.
        val maxConcurrency = resolveOcrPrepareConcurrency()
        val semaphore = Semaphore(maxConcurrency)
        val prepared = ArrayList<PreparedStandardPage?>(pages.size)
        repeat(pages.size) { prepared.add(null) }
        val completedCount = AtomicInteger(0)
        supervisorScope {
            val tasks = pages.mapIndexed { index, image ->
                async {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        // Show which page is running even before the first page finishes.
                        onPrepareProgress(completedCount.get(), pages.size, image.name)
                        val result = try {
                            prepareStandardPageForTranslation(
                                image = image,
                                force = force,
                                useVlDirectTranslate = false,
                                language = language,
                                detectionSelection = detectionSelection
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
            }
            tasks.awaitAll()
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
     * Full-page translation intentionally stays two-phase (no producer-consumer
     * pipeline): chapter-wide glossary extraction must finish before the first
     * page is translated (glossary rules), and webtoon cross-page merging needs
     * every page's OCR result up front.
     */
    private suspend fun prepareFullPagesConcurrent(
        pages: List<File>,
        force: Boolean,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit
    ): List<PageOcrResult?> {
        if (pages.isEmpty()) {
            onPrepareProgress(0, 0, "")
            return emptyList()
        }
        onPrepareProgress(0, pages.size, "")
        val maxConcurrency = resolveOcrPrepareConcurrency()
        val semaphore = Semaphore(maxConcurrency)
        val results = ArrayList<PageOcrResult?>(pages.size)
        repeat(pages.size) { results.add(null) }
        val completedCount = AtomicInteger(0)
        supervisorScope {
            val tasks = pages.mapIndexed { index, image ->
                async {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        // Show which page is running even before the first page finishes.
                        onPrepareProgress(completedCount.get(), pages.size, image.name)
                        val result = try {
                            translationPipeline.ocrImage(
                                image,
                                force,
                                language,
                                detectionSelection
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
            }
            tasks.awaitAll()
        }
        return results
    }

    private fun resolveOcrPrepareConcurrency(): Int {
        val configured = settingsStore.loadMaxConcurrency().coerceAtLeast(1)
        val ocrSettings = settingsStore.loadOcrApiSettings()
        val localCap = if (ocrSettings.useLocalOcr) {
            LocalOcrConcurrency.resolve(ocrSettings.localOcrConcurrencyLimit, appContext)
                .coerceAtMost(ImageProcessingGuards.decodeConcurrency)
                .coerceAtLeast(1)
        } else {
            ocrSettings.apiOcrConcurrencyLimit.coerceAtLeast(1)
        }
        return minOf(configured, localCap)
    }

    private fun applyCrossPageBubbleMerge(
        preparedPages: List<PreparedStandardPage?>
    ): List<PreparedStandardPage?> {
        val indexedOcr = preparedPages.mapIndexed { index, prepared ->
            index to prepared?.ocrResult
        }
        val validPairs = indexedOcr.filter { it.second != null }
        if (validPairs.size < 2) return preparedPages
        val merged = CrossPageBubbleMerger.merge(validPairs.map { it.second!! })
        val mergedByIndex = mutableMapOf<Int, PageOcrResult>()
        validPairs.forEachIndexed { mergeIndex, (originalIndex, _) ->
            mergedByIndex[originalIndex] = merged[mergeIndex]
        }
        return preparedPages.mapIndexed { index, prepared ->
            if (prepared == null) return@mapIndexed null
            mergedByIndex[index]?.let { prepared.copy(ocrResult = it) } ?: prepared
        }
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
        detectionSelection: RegionDetectionSelection,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
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

            val tasks = pages.mapIndexed { index, image ->
                val prepared = preparedPages.getOrNull(index)
                async {
                    currentCoroutineContext().ensureActive()

                    if (prepared == null) {
                        hasFailures.set(true)
                        recordPageFailure(folder, image, null)
                        reportPageProcessed(failed = true)
                        return@async
                    }
                    if (prepared.ocrResult != null) {
                        progressStore.update(folder, image.name, PageProgressStatus.OCR_DONE)
                    }

                    if (requestFailed.get()) {
                        markPageAborted(folder, image, hasFailures, requestException)
                        reportPageProcessed(failed = true)
                        return@async
                    }
                    progressStore.update(folder, image.name, PageProgressStatus.PENDING)
                    if (requestFailed.get()) {
                        markPageAborted(folder, image, hasFailures, requestException)
                        reportPageProcessed(failed = true)
                        return@async
                    }
                    var failureMessage: String? = null
                    val execution = try {
                        if (useVlDirectTranslate) {
                            apiSemaphore.withPermit {
                                executeVlPageTranslation(image, language, detectionSelection)
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
                                detectionSelection = detectionSelection,
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
            tasks.awaitAll()
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
        detectionSelection: RegionDetectionSelection,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        val maxConcurrency = settingsStore.loadMaxConcurrency()
        val ocrSemaphore = Semaphore(resolveOcrPrepareConcurrency())
        val apiSemaphore = Semaphore(maxConcurrency)
        val channel = Channel<PipelinedStandardPage>(capacity = maxConcurrency * 2)
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
                val ocrWorkers = pages.map { image ->
                    launch {
                        currentCoroutineContext().ensureActive()
                        val prepared = try {
                            ocrSemaphore.withPermit {
                                currentCoroutineContext().ensureActive()
                                onPrepareProgress(preparedCount.get(), pages.size, image.name)
                                prepareStandardPageForTranslation(
                                    image = image,
                                    force = force,
                                    useVlDirectTranslate = false,
                                    language = language,
                                    detectionSelection = detectionSelection
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
                        channel.send(item)
                    }
                }
                ocrWorkers.joinAll()
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
                        detectionSelection = detectionSelection,
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

            val consumers = List(maxConcurrency) {
                launch {
                    for (item in channel) {
                        processPage(item)
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
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection
    ): PreparedStandardPage? {
        if (useVlDirectTranslate) {
            return PreparedStandardPage(image = image, ocrResult = null)
        }
        if (!force && hasRefillablePartialTranslation(image, language, detectionSelection, TranslationMetadata.MODE_STANDARD)) {
            return PreparedStandardPage(image = image, ocrResult = null)
        }
        val ocrResult = translationPipeline.ocrImage(
            image,
            force,
            language,
            detectionSelection
        ) { } ?: return null
        return PreparedStandardPage(image = image, ocrResult = ocrResult)
    }

    private suspend fun executeConcurrentFullPages(
        pages: List<PageOcrResult>,
        folder: File,
        promptAsset: String,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
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

            val tasks = pages.map { page ->
                async {
                    currentCoroutineContext().ensureActive()
                    if (requestFailed.get()) {
                        markPageAborted(folder, page.imageFile, hasFailures, requestException)
                        reportPageProcessed(failed = true)
                        return@async
                    }
                    progressStore.update(folder, page.imageFile.name, PageProgressStatus.PENDING)
                    if (requestFailed.get()) {
                        markPageAborted(folder, page.imageFile, hasFailures, requestException)
                        reportPageProcessed(failed = true)
                        return@async
                    }
                    var failureMessage: String? = null
                    val execution = try {
                        executeFullPageWithModelErrorResolution(
                            apiSemaphore = semaphore,
                            folder = folder,
                            page = page,
                            promptAsset = promptAsset,
                            language = language,
                            detectionSelection = detectionSelection,
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
            tasks.awaitAll()
        }
        requestException.get()?.let { throw it }
        return PageExecutionSummary(
            hasFailures = hasFailures.get(),
            failedCount = failedCount.get()
        )
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
        detectionSelection: RegionDetectionSelection,
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
                detectionSelection = detectionSelection,
                glossary = glossary,
                glossaryMutex = glossaryMutex,
                glossaryProcessingEnabled = glossaryProcessingEnabled
            )?.let { return it }
        }
        val resolvedPage = page ?: translationPipeline.ocrImage(
            image,
            force,
            language,
            detectionSelection
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
                language = language
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
        image: File,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection,
        translationMode: String
    ): Boolean {
        if (pendingBubbleRetranslator == null) return false
        val existing = translationPipeline.loadAnyTranslation(image) ?: return false
        return existing.metadata.status == PageTranslationStatus.PARTIAL &&
            existing.metadata.matchesSource(image) &&
            matchesPartialTranslationRequest(
                metadata = existing.metadata,
                image = image,
                language = language,
                translationMode = translationMode,
                detectionSelection = detectionSelection
            )
    }

    /**
     * 批量补填的 metadata 匹配。比较逻辑与常规缓存读取共用同一套：
     * 期望值由 [TranslationPipeline.buildExpectedTranslationMetadata] 按当前请求
     * 构建（与写入路径同一代码，含完整的 `ocrCacheMode`），比较统一走
     * [TranslationStore.matchesTranslationRequest]，新增影响译文的维度时不可能只改一处。
     *
     * 模式与语言在这里精确比较：manual 结果与模式/语言不一致的结果不允许
     * 按当前请求补填（[TranslationStore.matchesTranslationRequest] 对 legacy
     * 数据整体容忍，这里不能跟随放宽）。
     */
    private fun matchesPartialTranslationRequest(
        metadata: TranslationMetadata,
        image: File,
        language: TranslationLanguage,
        translationMode: String,
        detectionSelection: RegionDetectionSelection
    ): Boolean {
        if (metadata.mode != translationMode || metadata.language != language.name) {
            return false
        }
        val expectedMetadata = translationPipeline.buildExpectedTranslationMetadata(
            imageFile = image,
            fullTranslate = translationMode == TranslationMetadata.MODE_FULL_PAGE,
            useVlDirectTranslate = translationMode == TranslationMetadata.MODE_VL_DIRECT,
            language = language,
            detectionSelection = detectionSelection
        )
        return translationStore.matchesTranslationRequest(image, metadata, expectedMetadata)
    }

    private suspend fun executeFullPageTranslation(
        folder: File,
        page: PageOcrResult,
        promptAsset: String,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection,
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
            detectionSelection = detectionSelection,
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
        detectionSelection: RegionDetectionSelection
    ): PageTranslationExecutionResult {
        val outcome = translationPipeline.translateImageWithVl(
            imageFile = image,
            language = language,
            detectionSelection = detectionSelection
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
        detectionSelection: RegionDetectionSelection,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        glossaryProcessingEnabled: Boolean
    ): PageTranslationExecutionResult? {
        val retranslator = pendingBubbleRetranslator ?: return null
        val existing = translationPipeline.loadAnyTranslation(image) ?: return null
        if (existing.metadata.status != PageTranslationStatus.PARTIAL) return null
        if (!existing.metadata.matchesSource(image)) return null
        if (!matchesPartialTranslationRequest(existing.metadata, image, language, translationMode, detectionSelection)) {
            AppLogger.log("Library", "Partial refill skipped for ${image.name}: request metadata mismatch")
            return null
        }
        // 跨页合并坐标只在条漫模式下有效，普通模式必须整页重译而非在旧坐标上补翻。
        if (!shouldApplyCrossPageBubbleMerge(folder) && existing.hasCrossPageBubbleGeometry()) {
            AppLogger.log("Library", "Partial refill skipped for ${image.name}: cross-page geometry")
            return null
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
                discardShortOcr = false
            )
        } catch (e: LlmResponseException) {
            AppLogger.log("Library", "Partial refill rejected for ${image.name}", e)
            return null
        } catch (e: LlmRequestException) {
            throw e
        } ?: return null

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
        val snapshotToSave = glossaryMutex.withLock {
            var changed = false
            additions.forEach { (key, value) ->
                if (key.isBlank() || value.isBlank()) return@forEach
                if (glossary[key] != value) {
                    glossary[key] = value
                    changed = true
                }
            }
            if (changed) LinkedHashMap(glossary) else null
        } ?: return
        glossaryWriter.submit(folder, snapshotToSave)
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
    private suspend fun executeGuardedTranslation(
        apiSemaphore: Semaphore,
        execute: suspend () -> PageTranslationExecutionResult,
        onSkipPage: suspend () -> PageTranslationExecutionResult
    ): PageTranslationExecutionResult {
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
        detectionSelection: RegionDetectionSelection,
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
                    detectionSelection = detectionSelection,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex
                )
            },
            onSkipPage = {
                val pageToSkip = page ?: translationPipeline.ocrImage(
                    image,
                    force,
                    language,
                    detectionSelection
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
        detectionSelection: RegionDetectionSelection,
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
                    detectionSelection = detectionSelection,
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

    private fun buildGlossaryText(pages: List<PageOcrResult>): String {
        val builder = StringBuilder()
        for (page in pages) {
            val orderedBubbles = page.bubbles.sortedWith(
                compareBy({ it.rect.top }, { it.rect.left }, { it.id })
            )
            for (bubble in orderedBubbles) {
                val text = bubble.text.trim()
                if (text.isNotBlank()) {
                    builder.append("<b>").append(text).append("</b>\n")
                }
            }
        }
        return builder.toString().trim()
    }

    private fun resolvePendingImages(
        images: List<File>,
        force: Boolean,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection,
        readingMode: FolderReadingMode
    ): List<File> {
        return if (force) {
            images
        } else {
            images.filterNot {
                translationPipeline.hasValidTranslation(
                    imageFile = it,
                    fullTranslate = fullTranslate,
                    useVlDirectTranslate = useVlDirectTranslate,
                    language = language,
                    detectionSelection = detectionSelection,
                    readingMode = readingMode
                )
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
