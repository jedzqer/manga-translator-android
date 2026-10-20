package com.manga.translate.translation

import android.content.Context
import com.manga.translate.di.appContainer
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.R
import com.manga.translate.detection.PageRegionDetectionResult
import com.manga.translate.detection.PageRegionDetector
import com.manga.translate.detection.mapPageLineRectsToCrop
import com.manga.translate.detection.shouldUseLongImageTiling
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.OcrMetadata
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.OcrRecognitionException
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationCoreDefaults
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.deriveStatus
import com.manga.translate.model.textOrThrow
import com.manga.translate.network.LlmClient
import com.manga.translate.network.DynamicConcurrencyLimiter
import com.manga.translate.network.LlmGateway
import com.manga.translate.network.LlmResponseException
import com.manga.translate.network.executeWithModelResponseRetries
import com.manga.translate.network.RequestTimeoutPolicy
import com.manga.translate.ocr.BubbleTextRecognizer
import com.manga.translate.ocr.OcrEngine
import com.manga.translate.ocr.OcrEngineRegistry
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.AvifBitmapDecoder
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.platform.ImageFileSupport
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.PerformanceTrace
import com.manga.translate.platform.PromptAssetResolver
import com.manga.translate.platform.recycleSafely
import com.manga.translate.ocr.OcrTextSanitizer
import com.manga.translate.settings.OCR_PROVIDER_ID
import com.manga.translate.settings.OcrApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.FloatingTranslationCacheStore
import com.manga.translate.storage.OcrStore
import com.manga.translate.storage.TranslationStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TranslationPipeline(
    context: Context,
    private val llmClient: LlmGateway = LlmClient(context.applicationContext),
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext),
    private val store: TranslationStore = TranslationStore(),
    private val ocrStore: OcrStore = OcrStore(),
    private val ocrEngineRegistry: OcrEngineRegistry =
        OcrEngineRegistry(context.applicationContext, settingsStore),
    private val bubbleTextRecognizer: BubbleTextRecognizer =
        BubbleTextRecognizer(llmClient, ocrEngineRegistry),
    private val textBubbleTranslationCoordinator: TextBubbleTranslationCoordinator =
        TextBubbleTranslationCoordinator(llmClient = llmClient),
    private val vlPageTranslationCoordinator: VlPageTranslationCoordinator =
        VlPageTranslationCoordinator(llmClient, FloatingTranslationCacheStore(context.applicationContext)),
    private val strategyResolver: TranslationStrategyResolver =
        TranslationStrategyResolver { _, globalStyle -> globalStyle },
    private val pageRegionDetector: PageRegionDetector =
        PageRegionDetector(context.applicationContext, settingsStore)
) {
    private val appContext = context.applicationContext

    private val ocrRegionLimiter = DynamicConcurrencyLimiter()

    private fun translationSettings(imageFile: File) = settingsStore.load().copy(
        translationStyle = strategyResolver.resolveTranslationStyle(
            requireNotNull(imageFile.absoluteFile.parentFile), settingsStore.loadTranslationStyle()
        )
    )

    suspend fun translateImage(
        imageFile: File,
        glossary: MutableMap<String, String>,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        glossaryProcessingEnabled: Boolean = true,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val resolvedApiSettings = translationSettings(imageFile)
        if (!llmClient.isConfigured(resolvedApiSettings)) {
            onProgress(appContext.getString(R.string.missing_api_settings))
            AppLogger.log("Pipeline", "Missing API settings")
            return@withContext null
        }
        val page = ocrImage(
            imageFile,
            forceOcr,
            language,
            onProgress = onProgress
        ) ?: return@withContext null
        translateStandardPage(
            page = page,
            imageFile = imageFile,
            glossary = glossary,
            language = language,
            glossaryProcessingEnabled = glossaryProcessingEnabled,
            onProgress = onProgress
        )
    }

    suspend fun translateStandardPage(
        page: PageOcrResult,
        imageFile: File,
        glossary: MutableMap<String, String>,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        glossaryProcessingEnabled: Boolean = true,
        onProgress: (String) -> Unit
    ): TranslationResult? {
        val translated = translateStandardPageWithGlossary(
            page = page,
            imageFile = imageFile,
            glossary = glossary,
            language = language,
            glossaryProcessingEnabled = glossaryProcessingEnabled,
            onProgress = onProgress
        ) ?: return null
        if (glossaryProcessingEnabled && translated.glossaryUsed.isNotEmpty()) {
            glossary.putAll(translated.glossaryUsed)
        }
        return translated.result
    }

    suspend fun translateStandardPageWithGlossary(
        page: PageOcrResult,
        imageFile: File,
        glossary: Map<String, String>,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        glossaryProcessingEnabled: Boolean = true,
        onProgress: (String) -> Unit
    ): PipelinePageTranslationOutcome? = withContext(Dispatchers.Default) {
        val trace = PerformanceTrace(
            tag = "Pipeline",
            operation = "translate:${imageFile.name}",
            enabled = settingsStore.loadModelIoLogging()
        )
        try {
            AppLogger.log("Pipeline", "Translate image ${imageFile.name}")
            val outcome = translateSingleOcrPage(
                page, imageFile, glossary, STANDARD_PROMPT_ASSET,
                TranslationMetadata.MODE_STANDARD, language, glossaryProcessingEnabled,
                onProgress, trace
            )
            if (outcome != null) AppLogger.log("Pipeline", "Translation finished for ${imageFile.name}")
            outcome
        } finally {
            trace.logSummary()
        }
    }

    suspend fun ocrImage(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onlyTranslateBubbles: Boolean = false,
        onProgress: (String) -> Unit
    ): PageOcrResult? = withContext(Dispatchers.Default) {
        val trace = PerformanceTrace(
            tag = "Pipeline",
            operation = "ocr:${imageFile.name}",
            enabled = settingsStore.loadModelIoLogging()
        )
        try {
        val ocrSettings = settingsStore.loadResolvedOcrApiSettings()
        val resolvedLanguage = TranslationLanguage.resolveForOcr(language, ocrSettings.useLocalOcr)
        val effectiveUseLocalOcr = ocrSettings.useLocalOcr && resolvedLanguage.supportsLocalOcr()
        val useBubbleOnly = onlyTranslateBubbles && effectiveUseLocalOcr
        val cacheMode = buildOcrCacheMode(
            imageFile,
            effectiveUseLocalOcr,
            resolvedLanguage,
            onlyTranslateBubbles = useBubbleOnly
        )
        val expectedMetadata = buildOcrMetadata(imageFile, language, ocrSettings, cacheMode)
        if (!forceOcr) {
            val cached = ocrStore.load(imageFile, expectedMetadata = expectedMetadata)
            if (cached != null) {
                trace.attribute("cache", "reuse")
                trace.attribute("bubbles", cached.bubbles.size)
                AppLogger.log("Pipeline", "Reuse OCR for ${imageFile.name}")
                return@withContext cached
            }
        }
        val useLocalOcr = effectiveUseLocalOcr
        val ocrEngine: OcrEngine? = if (useLocalOcr) {
            bubbleTextRecognizer.getLocalOcrEngine(resolvedLanguage, "Pipeline")
        } else {
            null
        }
        if (!useLocalOcr && !llmClient.isOcrConfigured()) {
            onProgress(appContext.getString(R.string.missing_ocr_api_settings))
            AppLogger.log("Pipeline", "Missing OCR API settings")
            return@withContext null
        }
        if (useLocalOcr && ocrEngine == null) {
            return@withContext null
        }
        trace.measure("decode") { PipelineBitmapDecoder.openCropSource(imageFile) }?.use { cropSource ->
            trace.attribute("size", "${cropSource.width}x${cropSource.height}")
            trace.attribute("longImage", shouldUseLongImageTiling(cropSource.width, cropSource.height))
            trace.attribute("ocrMode", if (useLocalOcr) "local" else "api")
            onProgress(appContext.getString(R.string.detecting_bubbles))
            val pageRegions = trace.measure("detection") {
                pageRegionDetector.detect(
                    cropSource = cropSource,
                    pageWidth = cropSource.width,
                    pageHeight = cropSource.height,
                    logTag = "Pipeline"
                )
            } ?: return@withContext null
            val detectionForOcr = selectPageRegionsForOcr(pageRegions, useBubbleOnly)
            trace.attribute("detection", if (detectionForOcr === pageRegions) {
                "bubbles_and_text"
            } else {
                "bubbles_only"
            })
            val regions = detectionForOcr.regions
            trace.attribute("regions", regions.size)
            AppLogger.log("Pipeline", "Detected ${regions.size} regions in ${imageFile.name}")
            if (regions.isEmpty()) {
                val emptyResult = PageOcrResult(
                    imageFile,
                    pageRegions.width,
                    pageRegions.height,
                    emptyList(),
                    cacheMode,
                    expectedMetadata
                )
                if (pageRegions.detectionComplete) {
                    trace.measure("persist") { ocrStore.save(imageFile, emptyResult) }
                } else {
                    AppLogger.log("Pipeline", "Skipping OCR cache for incomplete page detection")
                }
                return@withContext emptyResult
            }
            onProgress(
                appContext.getString(R.string.recognizing_bubbles, regions.size)
            )
            val bubbles = trace.measure("ocr") {
                recognizeBubblesIndividually(
                    cropSource = cropSource,
                    detection = detectionForOcr,
                    language = resolvedLanguage,
                    useLocalOcr = useLocalOcr
                )
            }
            val result = PageOcrResult(
                imageFile,
                pageRegions.width,
                pageRegions.height,
                bubbles,
                cacheMode,
                expectedMetadata
            )
            trace.attribute("recognized", bubbles.count { it.text.isNotBlank() })
            if (pageRegions.detectionComplete) {
                trace.measure("persist") { ocrStore.save(imageFile, result) }
            } else {
                AppLogger.log("Pipeline", "Skipping OCR cache for incomplete page detection")
            }
            result
        } ?: run {
            AppLogger.log("Pipeline", "Failed to open crop source for ${imageFile.name}")
            null
        }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed region invalidates this attempt, never the user's saved results.
            AppLogger.log("Pipeline", "OCR page failed for ${imageFile.name}", e)
            null
        } finally {
            trace.logSummary()
        }
    }

    suspend fun translatePagesWithGlossary(
        pages: List<PageOcrResult>,
        glossary: Map<String, String>,
        promptAsset: String,
        language: TranslationLanguage,
        mode: String,
        glossaryProcessingEnabled: Boolean = true
    ): List<PipelinePageTranslationOutcome>? = withContext(Dispatchers.Default) {
        if (pages.isEmpty()) return@withContext emptyList()
        val apiSettings = translationSettings(pages.first().imageFile)
        // Different folders may use different styles; never merge their instructions.
        if (pages.any { translationSettings(it.imageFile).translationStyle != apiSettings.translationStyle }) {
            val outcomes = ArrayList<PipelinePageTranslationOutcome>(pages.size)
            for (page in pages) {
                outcomes += translatePagesWithGlossary(listOf(page), glossary, promptAsset, language, mode, glossaryProcessingEnabled)
                    ?: return@withContext null
            }
            return@withContext outcomes
        }
        val pageMetadata = pages.map { page ->
            buildTranslationMetadata(
                imageFile = page.imageFile,
                language = language,
                mode = mode,
                promptAsset = promptAsset,
                ocrCacheMode = page.cacheMode,
                translationStyle = requireNotNull(apiSettings.translationStyle)
            )
        }
        val recognizedPages = pages.map { it.withRecognizedTextBubblesOnly("Pipeline") }
        var nextId = 0
        val pageBubbles = recognizedPages.map { page ->
            page.bubbles.sortedWith(compareBy({ it.rect.top }, { it.rect.left }, { it.id })).map { bubble ->
                val original = BubbleTranslation.pending(
                    id = bubble.id,
                    rect = bubble.rect,
                    originalText = bubble.text,
                    source = bubble.source,
                    maskContour = bubble.maskContour
                )
                original to original.copy(id = nextId++)
            }
        }
        val requestBubbles = pageBubbles.flatMap { bubbles -> bubbles.map { it.second } }
        val requestPages = pageBubbles.count { it.isNotEmpty() }.coerceAtLeast(1)
        val translated = try {
            executeWithModelResponseRetries("Pipeline") {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = requestBubbles,
                    glossary = glossary,
                    promptAsset = promptAsset,
                    requestTimeoutMs = RequestTimeoutPolicy.callTimeoutMs(settingsStore.loadApiTimeoutMs(), requestPages),
                    retryCount = settingsStore.loadApiRetryCount(),
                    apiSettings = apiSettings,
                    language = language,
                    logTag = "Pipeline",
                    translationMode = mode,
                    preserveInputOrder = true,
                    glossaryProcessingEnabled = glossaryProcessingEnabled
                )
            }
        } catch (error: LlmResponseException) {
            throw error.withPageName(pages.joinToString { it.imageFile.name })
        } ?: return@withContext null
        val translationById = translated.bubbles.associateBy { it.id }
        recognizedPages.mapIndexed { index, page ->
            val bubbles = pageBubbles[index].mapNotNull { (original, request) ->
                if (request.id in translated.removedBubbleIds) null else
                    requireNotNull(translationById[request.id]).copy(id = original.id)
            }
            val metadata = pageMetadata[index]
            val result = TranslationResult(page.imageFile.name, page.width, page.height, bubbles, metadata)
            PipelinePageTranslationOutcome(
                result.copy(metadata = metadata.copy(status = result.deriveStatus())),
                translated.glossaryUsed
            )
        }
    }

    suspend fun translateFullPage(
        page: PageOcrResult,
        glossary: Map<String, String>,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): TranslationResult? {
        return translateFullPageWithGlossary(
            page = page,
            glossary = glossary,
            promptAsset = promptAsset,
            language = language,
            onProgress = onProgress
        )?.result
    }

    suspend fun translateFullPageWithGlossary(
        page: PageOcrResult,
        glossary: Map<String, String>,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): PipelinePageTranslationOutcome? = withContext(Dispatchers.Default) {
        translateSingleOcrPage(
            page, page.imageFile, glossary, promptAsset,
            TranslationMetadata.MODE_FULL_PAGE, language, true, onProgress
        )
    }

    private suspend fun translateSingleOcrPage(
        page: PageOcrResult,
        imageFile: File,
        glossary: Map<String, String>,
        promptAsset: String,
        mode: String,
        language: TranslationLanguage,
        glossaryProcessingEnabled: Boolean,
        onProgress: (String) -> Unit,
        trace: PerformanceTrace? = null
    ): PipelinePageTranslationOutcome? {
        val apiSettings = translationSettings(imageFile)
        val metadata = buildTranslationMetadata(
            imageFile = imageFile,
            language = language,
            mode = mode,
            promptAsset = promptAsset,
            ocrCacheMode = page.cacheMode,
            translationStyle = requireNotNull(apiSettings.translationStyle)
        )
        val ocrPage = page.withRecognizedTextBubblesOnly("Pipeline")
        val pendingBubbles = ocrPage.bubbles.map { bubble ->
            BubbleTranslation.pending(bubble.id, bubble.rect, bubble.text, bubble.source, bubble.maskContour)
        }
        trace?.attribute("size", "${ocrPage.width}x${ocrPage.height}")
        trace?.attribute("bubbles", pendingBubbles.size)
        trace?.attribute("ocrMode", page.cacheMode)
        if (pendingBubbles.isEmpty()) {
            return PipelinePageTranslationOutcome(
                TranslationResult(imageFile.name, ocrPage.width, ocrPage.height,
                    emptyList(), metadata.copy(status = PageTranslationStatus.SUCCESS)),
                emptyMap()
            )
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val request: suspend () -> TextBubbleTranslationBatchResult? = {
            executeWithModelResponseRetries("Pipeline") {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = pendingBubbles,
                    glossary = glossary,
                    promptAsset = promptAsset,
                    apiSettings = apiSettings,
                    language = language,
                    logTag = "Pipeline",
                    translationMode = if (mode == TranslationMetadata.MODE_STANDARD) "standard" else "full_page",
                    glossaryProcessingEnabled = glossaryProcessingEnabled
                )
            }
        }
        val translatedBatch = try {
            if (trace != null) trace.measure("llm") { request() } else request()
        } catch (e: LlmResponseException) {
            throw e.withPageName(imageFile.name)
        } ?: return null
        val translationMap = translatedBatch.bubbles.associateBy { it.id }
        val bubbles = pendingBubbles.filterNot { it.id in translatedBatch.removedBubbleIds }
            .map { translationMap[it.id] ?: it }
        val result = TranslationResult(imageFile.name, ocrPage.width, ocrPage.height, bubbles, metadata)
        return PipelinePageTranslationOutcome(
            result.copy(metadata = metadata.copy(status = result.deriveStatus())),
            translatedBatch.glossaryUsed
        )
    }

    suspend fun translateImageWithVl(
        imageFile: File,
        language: TranslationLanguage,
        glossary: Map<String, String> = emptyMap(),
        glossaryProcessingEnabled: Boolean = false,
        baseTranslation: TranslationResult? = null,
        fullVisual: Boolean = false
    ): FolderVlTranslateOutcome = withContext(Dispatchers.Default) {
        if (baseTranslation != null && (baseTranslation.bubbles.none { it.needsTranslationRetry() } || baseTranslation.hasCrossPageBubbleGeometry())) {
            return@withContext FolderVlTranslateOutcome(result = baseTranslation)
        }
        val settings = translationSettings(imageFile)
        if (!llmClient.isConfigured(settings)) return@withContext FolderVlTranslateOutcome()
        val source = PipelineBitmapDecoder.openCropSource(imageFile) ?: return@withContext FolderVlTranslateOutcome()
        source.use {
            val detection = pageRegionDetector.detect(source, source.width, source.height)
                ?: return@withContext FolderVlTranslateOutcome()
            if (!detection.detectionComplete) return@withContext FolderVlTranslateOutcome()
            val layout = if (baseTranslation == null) detection.vlLayout() else VlPageLayout(source.width, source.height,
                baseTranslation.bubbles.filter { it.needsTranslationRetry() }, tiles = detection.tiles)
            val outcome = vlPageTranslationCoordinator.translate(source, layout, settings, language,
                settingsStore.loadApiTimeoutMs(), settingsStore.loadApiRetryCount(), glossary, glossaryProcessingEnabled,
                fullVisual = fullVisual)
            if (outcome.timedOut || outcome.requiresVlModel) return@withContext FolderVlTranslateOutcome(
                timedOut = outcome.timedOut, requiresVlModel = outcome.requiresVlModel)
            val metadata = buildTranslationMetadata(imageFile, language,
                mode = TranslationMetadata.MODE_VL_DIRECT, promptAsset = VL_PROMPT_ASSET,
                ocrCacheMode = "", translationStyle = requireNotNull(settings.translationStyle))
            val merged = baseTranslation?.let { mergeVlTargetResults(it.bubbles, layout.targets, outcome.bubbles) }
                ?: outcome.bubbles
            val result = baseTranslation?.copy(bubbles = merged) ?: TranslationResult(imageFile.name, source.width, source.height, merged, metadata)
            FolderVlTranslateOutcome(result = result.copy(metadata = result.metadata.copy(status = result.deriveStatus())),
                glossaryUsed = outcome.glossaryUsed)
        }
    }

    suspend fun translateImagesWithVl(
        images: List<File>,
        language: TranslationLanguage,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean,
        fullVisual: Boolean = false
    ): List<FolderVlTranslateOutcome>? = withContext(Dispatchers.Default) {
        if (images.isEmpty()) return@withContext emptyList()
        val settings = translationSettings(images.first())
        if (!llmClient.isConfigured(settings)) return@withContext null
        // Keep folder-specific style instructions isolated.
        if (images.any { translationSettings(it).translationStyle != settings.translationStyle }) {
            return@withContext images.map { translateImageWithVl(it, language, glossary, glossaryProcessingEnabled, fullVisual = fullVisual) }
        }
        val prepared = mutableListOf<VlPageTranslationCoordinator.PreparedPage>()
        var nextId = 0
        for (image in images) {
            val source = PipelineBitmapDecoder.openCropSource(image) ?: return@withContext null
            source.use {
                val detection = pageRegionDetector.detect(source, source.width, source.height)
                    ?: return@withContext null
                if (!detection.detectionComplete) return@withContext null
                val page = vlPageTranslationCoordinator.preparePage(source, detection.vlLayout(), nextId, fullVisual)
                nextId += page.localIds.size
                prepared.add(page)
            }
        }
        val outcomes = vlPageTranslationCoordinator.translatePreparedPages(
            prepared, settings,
            RequestTimeoutPolicy.callTimeoutMs(settingsStore.loadApiTimeoutMs(), prepared.count { it.images.isNotEmpty() }.coerceAtLeast(1)),
            settingsStore.loadApiRetryCount(), glossary, glossaryProcessingEnabled
        )
        images.mapIndexed { index, image ->
            val metadata = buildTranslationMetadata(image, language,
                mode = TranslationMetadata.MODE_VL_DIRECT, promptAsset = VL_PROMPT_ASSET,
                ocrCacheMode = "", translationStyle = requireNotNull(settings.translationStyle))
            val layout = prepared[index].layout
            val outcome = outcomes[index]
            val result = TranslationResult(image.name, layout.width, layout.height, outcome.bubbles, metadata)
            FolderVlTranslateOutcome(result = result.copy(metadata = metadata.copy(status = result.deriveStatus())),
                glossaryUsed = outcome.glossaryUsed)
        }
    }

    fun hasValidTranslation(imageFile: File): Boolean {
        val translation = loadValidTranslation(imageFile) ?: return false
        if (translation.metadata.isManual()) return true
        return translation.metadata.status == PageTranslationStatus.SUCCESS
    }

    fun loadValidTranslation(imageFile: File): TranslationResult? {
        // Saved translations are user data. Setting changes never invalidate them.
        return store.load(imageFile)
    }

    fun loadAnyTranslation(imageFile: File): TranslationResult? {
        return store.load(imageFile)
    }

    fun saveResult(imageFile: File, result: TranslationResult): File {
        val trace = PerformanceTrace(
            tag = "Pipeline",
            operation = "save:${imageFile.name}",
            enabled = settingsStore.loadModelIoLogging()
        )
        val saved = trace.measureBlocking("persist") { store.save(imageFile, result) }
        if (result.metadata.status == PageTranslationStatus.SUCCESS) {
            val ocrFile = ocrStore.ocrFileFor(imageFile)
            if (ocrFile.exists()) {
                trace.measureBlocking("cleanup_ocr") { ocrFile.delete() }
            }
        }
        trace.logSummary()
        return saved
    }

    suspend fun buildBlankTranslationResult(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val page = ocrImage(imageFile, forceOcr, language) { }
            ?: return@withContext null
        buildBlankTranslationResult(
            page = page,
            mode = TranslationMetadata.MODE_STANDARD,
            promptAsset = STANDARD_PROMPT_ASSET,
            language = language
        )
    }

    fun buildBlankTranslationResult(
        page: PageOcrResult,
        mode: String,
        promptAsset: String,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH
    ): TranslationResult {
        val metadata = buildTranslationMetadata(
            imageFile = page.imageFile,
            language = language,
            mode = mode,
            promptAsset = promptAsset,
            ocrCacheMode = page.cacheMode
        )
        val ocrPage = page.withRecognizedTextBubblesOnly("Pipeline")
        val bubbles = ocrPage.bubbles.map { bubble ->
            BubbleTranslation.pending(bubble.id, bubble.rect, "", bubble.source, bubble.maskContour)
        }
        return TranslationResult(
            imageName = ocrPage.imageFile.name,
            width = ocrPage.width,
            height = ocrPage.height,
            bubbles = bubbles,
            metadata = metadata
        )
    }

    fun translationFileFor(imageFile: File): File {
        return store.translationFileFor(imageFile)
    }

    fun releaseLoadedModels() {
        pageRegionDetector.releaseLoadedDetectors()
    }

    private suspend fun recognizeBubblesIndividually(
        cropSource: BitmapCropSource,
        detection: PageRegionDetectionResult,
        language: TranslationLanguage,
        useLocalOcr: Boolean
    ): List<OcrBubble> {
        val regions = detection.regions
        val bubbles = ArrayList<OcrBubble>(regions.size)
        val batchSupplement = useLocalOcr && settingsStore.loadOcrApiSettings().apiSupplementEnabled
        if (useLocalOcr && !batchSupplement) {
            for (region in regions) {
                val text = recognizeRegionFromSource(
                    cropSource = cropSource,
                    rect = region.rect,
                    language = language,
                    useLocalOcr = useLocalOcr,
                    logTag = "Pipeline",
                    bubbleSource = region.source,
                    detectedLineRects = region.textLineRects
                )
                if (text.isBlank()) continue
                bubbles.add(
                    OcrBubble(
                        id = region.id,
                        rect = region.rect,
                        text = text,
                        source = region.source,
                        maskContour = region.maskContour
                    )
                )
            }
        } else {
            val limit = settingsStore.loadOcrApiSettings().apiOcrConcurrencyLimit.coerceAtLeast(1)
            val results = arrayOfNulls<OcrBubble>(regions.size)
            val supplement = BooleanArray(regions.size)
            if (useLocalOcr) withContext(Dispatchers.IO) {
                regions.forEachBounded(limit) { index, region ->
                    // Bound the entire crop lifetime across concurrent pages as well.
                    ocrRegionLimiter.withPermit(limit) {
                        val text = recognizeRegionFromSource(
                            cropSource = cropSource,
                            rect = region.rect,
                            language = language,
                            useLocalOcr = useLocalOcr,
                            logTag = "Pipeline",
                            bubbleSource = region.source,
                            detectedLineRects = region.textLineRects,
                            deferSupplement = batchSupplement && region.source != BubbleSource.MANUAL,
                            onSupplementNeeded = { supplement[index] = true }
                        )
                        if (text.isNotBlank()) results[index] = OcrBubble(
                            id = region.id,
                            rect = region.rect,
                            text = text,
                            source = region.source,
                            maskContour = region.maskContour
                        )
                    }
                }
            }
            val pending = if (useLocalOcr) regions.indices.filter { supplement[it] }
                else regions.indices.toList()
            if (pending.isNotEmpty()) {
                val targets = pending.mapIndexed { id, index ->
                    BubbleTranslation.pending(id, regions[index].rect, "", regions[index].source,
                        regions[index].maskContour)
                }
                val layout = VlPageLayout(
                    width = detection.width,
                    height = detection.height,
                    targets = targets,
                    tiles = detection.tiles
                )
                val resultIndexes = pending.mapIndexed { id, index -> id to index }.toMap()
                for (chunk in layout.chunks()) {
                    ocrRegionLimiter.withPermit(limit) {
                        val recognized = withPageAnnotation(cropSource, layout, chunk,
                            decodeFailure = {
                                OcrRecognitionException(IllegalStateException("Unable to decode OCR page chunk"))
                            }
                        ) { annotated ->
                            llmClient.recognizePageRegions(annotated, chunk.targets.map { it.id })
                        }
                        val chunkIds = chunk.targets.map { it.id }.toSet()
                        if (recognized.keys != chunkIds) {
                            throw OcrRecognitionException(IllegalStateException("Page OCR ID mismatch"))
                        }
                        chunk.targets.forEach { target ->
                            val index = resultIndexes.getValue(target.id)
                            val text = OcrTextSanitizer.sanitize(recognized.getValue(target.id), language, "Pipeline")
                            results[index] = if (text.isBlank() || text.equals("None", ignoreCase = true)) null
                                else OcrBubble(regions[index].id, regions[index].rect, text,
                                    regions[index].source, regions[index].maskContour)
                        }
                    }
                }
            }
            results.filterNotNullTo(bubbles)
        }
        return bubbles
    }

    private suspend fun recognizeRegionFromSource(
        cropSource: BitmapCropSource,
        rect: RectF,
        language: TranslationLanguage,
        useLocalOcr: Boolean,
        logTag: String,
        bubbleSource: BubbleSource = BubbleSource.UNKNOWN,
        detectedLineRects: List<RectF>? = null,
        deferSupplement: Boolean = false,
        onSupplementNeeded: (() -> Unit)? = null
    ): String {
        val clamped = PipelineBitmapDecoder.clampRect(rect, cropSource.width, cropSource.height)
            ?: throw OcrRecognitionException(IllegalStateException("Invalid OCR region"))
        val crop = cropSource.decodeRegion(clamped)
            ?: throw OcrRecognitionException(IllegalStateException("Unable to decode OCR region"))
        return try {
            bubbleTextRecognizer.recognizeCrop(
                crop = crop,
                language = language,
                useLocalOcr = useLocalOcr,
                logTag = logTag,
                bubbleSource = bubbleSource,
                detectedLineRects = mapPageLineRectsToCrop(
                    detectedLineRects, clamped, crop.width, crop.height
                ),
                deferSupplement = deferSupplement,
                onSupplementNeeded = onSupplementNeeded
            ).textOrThrow()
        } finally {
            crop.recycleSafely()
        }
    }

    companion object {
        private const val STANDARD_PROMPT_ASSET = "prompts/llm_prompts.json"
        private const val FULL_TRANS_PROMPT_ASSET = "prompts/llm_prompts_FullTrans.json"
        private const val VL_PROMPT_ASSET = "prompts/vl_page_prompts.json"
    }

    private fun buildTranslationMetadata(
        imageFile: File,
        language: TranslationLanguage,
        mode: String,
        promptAsset: String,
        ocrCacheMode: String,
        translationStyle: String = requireNotNull(translationSettings(imageFile).translationStyle)
    ): TranslationMetadata {
        val apiSettings = settingsStore.load()
        return TranslationMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            mode = mode,
            language = language.name,
            promptAsset = PromptAssetResolver.resolve(appContext, promptAsset),
            apiFormat = apiSettings.apiFormat.prefValue,
            styleFingerprint = TranslationMetadata.fingerprintStyle(translationStyle),
            ocrCacheMode = ocrCacheMode
        )
    }

    private fun buildOcrMetadata(
        imageFile: File,
        language: TranslationLanguage,
        ocrSettings: OcrApiSettings,
        cacheMode: String
    ): OcrMetadata {
        val effectiveUseLocalOcr = ocrSettings.useLocalOcr && language.supportsLocalOcr()
        val engineModel = if (effectiveUseLocalOcr) {
            "local:$cacheMode" + if (ocrSettings.apiSupplementEnabled) {
                "|supplement-v3-masked-tiles:${ocrSettings.apiSupplementLevel.coerceIn(1, 3)}:${ocrSettings.ocrApiFormat.prefValue}:${ocrSettings.apiUrl}:${ocrSettings.modelName}"
            } else ""
        } else {
            "api:${ocrSettings.modelName}|masked-tiles-v1"
        }
        return OcrMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            cacheMode = cacheMode,
            language = language.name,
            engineModel = engineModel + if (!effectiveUseLocalOcr || ocrSettings.apiSupplementEnabled) {
                appContext.appContainer.customPromptStore.fingerprint(appContext, "prompts/ocr_prompts.json")
                    .takeIf { it.isNotEmpty() }?.let { "|prompt:$it" }.orEmpty()
            } else ""
        )
    }

    private fun buildOcrCacheMode(
        imageFile: File,
        useLocalOcr: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false
    ): String {
        // Detection and local recognition identify the reusable OCR cache here.
        // Supplemental API settings are tracked separately in engineModel.
        val baseMode = if (!useLocalOcr) {
            "api"
        } else {
            when (language) {
                TranslationLanguage.JA_TO_ZH,
                TranslationLanguage.EN_TO_ZH,
                TranslationLanguage.ZH_HANS_TO_TARGET,
                TranslationLanguage.ZH_HANT_TO_TARGET,
                TranslationLanguage.CHN_ENG_TO_ZH,
                TranslationLanguage.FR_TO_ZH,
                TranslationLanguage.ES_TO_ZH,
                TranslationLanguage.PT_TO_ZH,
                TranslationLanguage.DE_TO_ZH,
                TranslationLanguage.IT_TO_ZH -> "local_ppocrv6_small_rec"
                TranslationLanguage.KO_TO_ZH -> "local_ko"
                TranslationLanguage.RU_TO_ZH -> "api"
            }
        }
        val strategyTag = PipelineBitmapDecoder.readImageSize(imageFile)?.let { size ->
            buildDetectionStrategyTag(size.width, size.height)
        } ?: "det_full_yolo26nseg1472_paddle_blocks_v3"
        return "$baseMode|$strategyTag|${ocrRegionCacheMode(onlyTranslateBubbles, useLocalOcr)}"
    }

    private fun LlmResponseException.withPageName(pageName: String): LlmResponseException {
        val pagePrefix = appContext.getString(R.string.error_page_prefix)
        if (responseContent.startsWith(pagePrefix)) return this
        return LlmResponseException(
            errorCode = errorCode,
            responseContent = "$pagePrefix$pageName\n$responseContent",
            cause = this
        )
    }

}

internal fun selectPageRegionsForOcr(
    detection: PageRegionDetectionResult,
    bubbleOnlyEnabled: Boolean
): PageRegionDetectionResult {
    if (!bubbleOnlyEnabled) return detection
    val bubbleRegions = detection.regions.filter { it.source == BubbleSource.BUBBLE_DETECTOR }
    if (bubbleRegions.isEmpty()) return detection
    return detection.copy(regions = bubbleRegions, textRects = emptyList())
}

internal fun ocrRegionCacheMode(onlyTranslateBubbles: Boolean, useLocalOcr: Boolean): String =
    if (onlyTranslateBubbles && useLocalOcr) "bubbles_only" else "bubbles_and_text"

internal fun buildDetectionStrategyTag(
    pageWidth: Int,
    pageHeight: Int
): String {
    return if (shouldUseLongImageTiling(pageWidth, pageHeight)) {
        "det_vertical_tiled_yolo26nseg1472_paddle_blocks_v3"
    } else {
        "det_full_yolo26nseg1472_paddle_blocks_v3"
    }
}

internal fun PageOcrResult.withRecognizedTextBubblesOnly(logTag: String? = null): PageOcrResult {
    val filtered = bubbles.filter { it.text.isNotBlank() }
    if (filtered.size == bubbles.size) return this
    logTag?.let {
        AppLogger.log(it, "Dropped OCR bubbles without text: ${bubbles.size} -> ${filtered.size}")
    }
    return copy(bubbles = filtered)
}

data class FolderVlTranslateOutcome(
    val glossaryUsed: Map<String, String> = emptyMap(),
    val result: TranslationResult? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

data class PipelinePageTranslationOutcome(
    val result: TranslationResult,
    val glossaryUsed: Map<String, String> = emptyMap()
)
