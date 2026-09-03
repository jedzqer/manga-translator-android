package com.manga.translate.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.R
import com.manga.translate.detection.PageRegion
import com.manga.translate.detection.PageRegionDetector
import com.manga.translate.detection.RegionDetectionSelection
import com.manga.translate.detection.mapPageLineRectsToCrop
import com.manga.translate.detection.shouldUseLongImageTiling
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.OcrMetadata
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.OcrRecognitionResult
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationCoreDefaults
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.model.TranslationResult
import com.manga.translate.model.deriveStatus
import com.manga.translate.model.textOrEmpty
import com.manga.translate.network.LlmClient
import com.manga.translate.network.LlmGateway
import com.manga.translate.network.LlmResponseException
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
import com.manga.translate.settings.OCR_PROVIDER_ID
import com.manga.translate.settings.OcrApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.FloatingTranslationCacheStore
import com.manga.translate.storage.OcrStore
import com.manga.translate.storage.TranslationStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val floatingBubbleTranslationCoordinator: FloatingBubbleTranslationCoordinator =
        FloatingBubbleTranslationCoordinator(
            llmClient = llmClient,
            floatingTranslationCacheStore = FloatingTranslationCacheStore(context.applicationContext),
            settingsStore = settingsStore
        ),
    private val pageRegionDetector: PageRegionDetector =
        PageRegionDetector(context.applicationContext, settingsStore)
) {
    private val appContext = context.applicationContext

    suspend fun translateImage(
        imageFile: File,
        glossary: MutableMap<String, String>,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        detectionSelection: RegionDetectionSelection = RegionDetectionSelection.BUBBLES_AND_TEXT,
        onProgress: (String) -> Unit
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val resolvedApiSettings = settingsStore.load()
        if (!llmClient.isConfigured(resolvedApiSettings)) {
            onProgress(appContext.getString(R.string.missing_api_settings))
            AppLogger.log("Pipeline", "Missing API settings")
            return@withContext null
        }
        val page = ocrImage(
            imageFile,
            forceOcr,
            language,
            detectionSelection,
            onProgress
        ) ?: return@withContext null
        translateStandardPage(
            page = page,
            imageFile = imageFile,
            glossary = glossary,
            language = language,
            onProgress = onProgress
        )
    }

    suspend fun translateStandardPage(
        page: PageOcrResult,
        imageFile: File,
        glossary: MutableMap<String, String>,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): TranslationResult? {
        val translated = translateStandardPageWithGlossary(
            page = page,
            imageFile = imageFile,
            glossary = glossary,
            language = language,
            onProgress = onProgress
        ) ?: return null
        if (translated.glossaryUsed.isNotEmpty()) {
            glossary.putAll(translated.glossaryUsed)
        }
        return translated.result
    }

    suspend fun translateStandardPageWithGlossary(
        page: PageOcrResult,
        imageFile: File,
        glossary: Map<String, String>,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        onProgress: (String) -> Unit
    ): PipelinePageTranslationOutcome? = withContext(Dispatchers.Default) {
        val trace = PerformanceTrace(
            tag = "Pipeline",
            operation = "translate:${imageFile.name}",
            enabled = settingsStore.loadModelIoLogging()
        )
        try {
        val resolvedApiSettings = settingsStore.load()
        val metadata = buildTranslationMetadata(
            imageFile = imageFile,
            language = language,
            mode = TranslationMetadata.MODE_STANDARD,
            promptAsset = STANDARD_PROMPT_ASSET,
            ocrCacheMode = page.cacheMode
        )
        AppLogger.log("Pipeline", "Translate image ${imageFile.name}")
        val ocrPage = page.withRecognizedTextBubblesOnly("Pipeline")
        val translatable = ocrPage.bubbles
        trace.attribute("size", "${ocrPage.width}x${ocrPage.height}")
        trace.attribute("bubbles", translatable.size)
        trace.attribute("ocrMode", page.cacheMode)
        if (translatable.isEmpty()) {
            val emptyTranslations = ocrPage.bubbles.map {
                BubbleTranslation.pending(it.id, it.rect, "", it.source, it.maskContour)
            }
            return@withContext TranslationResult(
                imageFile.name,
                ocrPage.width,
                ocrPage.height,
                emptyTranslations,
                metadata.copy(status = PageTranslationStatus.SUCCESS)
            ).let { PipelinePageTranslationOutcome(it, emptyMap()) }
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val promptAsset = STANDARD_PROMPT_ASSET
        val translatedBatch = try {
            val translated = trace.measure("llm") {
                executeWithModelResponseRetries("Pipeline") {
                    textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = translatable.map {
                        BubbleTranslation.pending(
                            id = it.id,
                            rect = it.rect,
                            originalText = it.text,
                            source = it.source,
                            maskContour = it.maskContour
                        )
                    },
                    glossary = glossary,
                    promptAsset = promptAsset,
                    apiSettings = resolvedApiSettings,
                    language = language,
                    logTag = "Pipeline",
                    translationMode = "standard"
                    )
                }
            } ?: return@withContext null
            translated
        } catch (e: LlmResponseException) {
            throw e.withPageName(imageFile.name)
        }
        val translationMap = translatedBatch.bubbles.associateBy { it.id }
        val bubbles = ocrPage.bubbles.filterNot { it.id in translatedBatch.removedBubbleIds }.map { bubble ->
            translationMap[bubble.id] ?: BubbleTranslation.pending(
                id = bubble.id,
                rect = bubble.rect,
                originalText = bubble.text,
                source = bubble.source,
                maskContour = bubble.maskContour
            )
        }
        AppLogger.log("Pipeline", "Translation finished for ${imageFile.name}")
        val resultBase = TranslationResult(imageFile.name, ocrPage.width, ocrPage.height, bubbles, metadata)
        PipelinePageTranslationOutcome(
            result = resultBase.copy(metadata = metadata.copy(status = resultBase.deriveStatus())),
            glossaryUsed = translatedBatch.glossaryUsed
        )
        } finally {
            trace.logSummary()
        }
    }

    suspend fun ocrImage(
        imageFile: File,
        forceOcr: Boolean,
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        detectionSelection: RegionDetectionSelection = RegionDetectionSelection.BUBBLES_AND_TEXT,
        onProgress: (String) -> Unit
    ): PageOcrResult? = withContext(Dispatchers.Default) {
        val trace = PerformanceTrace(
            tag = "Pipeline",
            operation = "ocr:${imageFile.name}",
            enabled = settingsStore.loadModelIoLogging()
        )
        try {
        val ocrSettings = settingsStore.loadOcrApiSettings()
        val resolvedLanguage = TranslationLanguage.resolveForOcr(language, ocrSettings.useLocalOcr)
        val effectiveUseLocalOcr = ocrSettings.useLocalOcr && resolvedLanguage.supportsLocalOcr()
        val cacheMode = buildOcrCacheMode(
            imageFile,
            effectiveUseLocalOcr,
            resolvedLanguage,
            detectionSelection
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
            trace.attribute("detection", detectionSelection.prefValue)
            onProgress(appContext.getString(R.string.detecting_bubbles))
            val pageRegions = trace.measure("detection") {
                pageRegionDetector.detect(
                    cropSource = cropSource,
                    pageWidth = cropSource.width,
                    pageHeight = cropSource.height,
                    logTag = "Pipeline",
                    detectionSelection = detectionSelection
                )
            } ?: return@withContext null
            val regions = pageRegions.regions
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
                    regions = regions,
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
        } finally {
            trace.logSummary()
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
        val metadata = buildTranslationMetadata(
            imageFile = page.imageFile,
            language = language,
            mode = TranslationMetadata.MODE_FULL_PAGE,
            promptAsset = promptAsset,
            ocrCacheMode = page.cacheMode
        )
        val ocrPage = page.withRecognizedTextBubblesOnly("Pipeline")
        val translatable = ocrPage.bubbles
        if (translatable.isEmpty()) {
            val emptyTranslations = ocrPage.bubbles.map {
                BubbleTranslation.pending(it.id, it.rect, "", it.source, it.maskContour)
            }
            return@withContext TranslationResult(
                ocrPage.imageFile.name,
                ocrPage.width,
                ocrPage.height,
                emptyTranslations,
                metadata.copy(status = PageTranslationStatus.SUCCESS)
            ).let { PipelinePageTranslationOutcome(it, emptyMap()) }
        }
        onProgress(appContext.getString(R.string.translating_bubbles))
        val translatedBatch = try {
            val translated = executeWithModelResponseRetries("Pipeline") {
                textBubbleTranslationCoordinator.translateBubbles(
                    bubbles = translatable.map {
                        BubbleTranslation.pending(
                            id = it.id,
                            rect = it.rect,
                            originalText = it.text,
                            source = it.source,
                            maskContour = it.maskContour
                        )
                    },
                    glossary = glossary,
                    promptAsset = promptAsset,
                    apiSettings = settingsStore.load(),
                    language = language,
                    logTag = "Pipeline",
                    translationMode = "full_page"
                )
            } ?: return@withContext null
            translated
        } catch (e: LlmResponseException) {
            throw e.withPageName(ocrPage.imageFile.name)
        }
        val translationMap = translatedBatch.bubbles.associateBy { it.id }
        val bubbles = ocrPage.bubbles.filterNot { it.id in translatedBatch.removedBubbleIds }.map { bubble ->
            translationMap[bubble.id] ?: BubbleTranslation.pending(
                id = bubble.id,
                rect = bubble.rect,
                originalText = bubble.text,
                source = bubble.source,
                maskContour = bubble.maskContour
            )
        }
        val resultBase = TranslationResult(ocrPage.imageFile.name, ocrPage.width, ocrPage.height, bubbles, metadata)
        PipelinePageTranslationOutcome(
            result = resultBase.copy(metadata = metadata.copy(status = resultBase.deriveStatus())),
            glossaryUsed = translatedBatch.glossaryUsed
        )
    }

    suspend fun translateImageWithVl(
        imageFile: File,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection = RegionDetectionSelection.BUBBLES_AND_TEXT
    ): FolderVlTranslateOutcome =
        withContext(Dispatchers.Default) {
            if (!llmClient.isConfigured()) {
                AppLogger.log("Pipeline", "Missing API settings for VL direct translate")
                return@withContext FolderVlTranslateOutcome()
            }
            val bitmap = if (ImageFileSupport.isAvifFile(imageFile.name)) {
                AvifBitmapDecoder.decode(imageFile)
            } else {
                android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
            } ?: run {
                AppLogger.log("Pipeline", "Failed to decode ${imageFile.name} for VL direct translate")
                return@withContext FolderVlTranslateOutcome()
            }
            try {
                val page = detectImageBubbles(
                    imageFile,
                    bitmap,
                    detectionSelection
                ) ?: return@withContext FolderVlTranslateOutcome()
                if (page.bubbles.isEmpty()) {
                    return@withContext FolderVlTranslateOutcome(
                        result = TranslationResult(
                            imageFile.name,
                            page.width,
                            page.height,
                            emptyList(),
                            buildTranslationMetadata(
                                imageFile = imageFile,
                                language = language,
                                mode = TranslationMetadata.MODE_VL_DIRECT,
                                promptAsset = VL_PROMPT_ASSET,
                                ocrCacheMode = ""
                            ).copy(status = PageTranslationStatus.SUCCESS)
                        )
                    )
                }
                val floatingSettings = settingsStore.loadFloatingTranslateApiSettings()
                val outcome = floatingBubbleTranslationCoordinator.translateImageBubbles(
                    bitmap = bitmap,
                    bubbles = page.bubbles.map { bubble ->
                        BubbleTranslation.pending(
                            bubble.id,
                            expandVlBubbleRect(bubble.rect, bitmap.width, bitmap.height),
                            "",
                            bubble.source,
                            bubble.maskContour
                        )
                    },
                    timeoutMs = settingsStore.loadApiTimeoutMs(),
                    retryCount = 3,
                    promptAsset = VL_PROMPT_ASSET,
                    apiSettings = settingsStore.load(),
                    language = language,
                    concurrency = floatingSettings.aiApiConcurrencyLimit,
                    maxConcurrency = 16,
                    useCache = false,
                    logTag = "Pipeline"
                )
                if (outcome.requiresVlModel || outcome.timedOut) {
                    return@withContext FolderVlTranslateOutcome(
                        timedOut = outcome.timedOut,
                        requiresVlModel = outcome.requiresVlModel
                    )
                }
                val vlMetadata = buildTranslationMetadata(
                    imageFile = imageFile,
                    language = language,
                    mode = TranslationMetadata.MODE_VL_DIRECT,
                    promptAsset = VL_PROMPT_ASSET,
                    ocrCacheMode = ""
                )
                val resultBase = TranslationResult(
                    imageFile.name,
                    page.width,
                    page.height,
                    outcome.bubbles,
                    vlMetadata
                )
                FolderVlTranslateOutcome(
                    result = resultBase.copy(
                        metadata = vlMetadata.copy(status = resultBase.deriveStatus())
                    )
                )
            } finally {
                bitmap.recycleSafely()
            }
        }

    fun hasValidTranslation(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection = RegionDetectionSelection.BUBBLES_AND_TEXT,
        readingMode: FolderReadingMode = FolderReadingMode.STANDARD
    ): Boolean {
        val translation = loadValidTranslation(
            imageFile = imageFile,
            fullTranslate = fullTranslate,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language,
            detectionSelection = detectionSelection,
            readingMode = readingMode
        ) ?: return false
        if (translation.metadata.isManual()) return true
        return translation.metadata.status == PageTranslationStatus.SUCCESS
    }

    fun loadValidTranslation(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection = RegionDetectionSelection.BUBBLES_AND_TEXT,
        readingMode: FolderReadingMode = FolderReadingMode.STANDARD
    ): TranslationResult? {
        val expected = buildExpectedTranslationMetadata(
            imageFile = imageFile,
            fullTranslate = fullTranslate,
            useVlDirectTranslate = useVlDirectTranslate,
            language = language,
            detectionSelection = detectionSelection
        )
        val result = store.load(imageFile, expectedMetadata = expected) ?: return null
        // 条漫模式翻译产生的跨页合并坐标在普通模式下无法正确渲染，必须视为缓存未命中重新翻译。
        if (readingMode != FolderReadingMode.WEBTOON_SCROLL && result.hasCrossPageBubbleGeometry()) {
            AppLogger.log(
                "Pipeline",
                "Discarding cross-page merged translation for ${imageFile.name}: " +
                    "reading mode is $readingMode"
            )
            return null
        }
        return result
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
        language: TranslationLanguage = TranslationLanguage.JA_TO_ZH,
        detectionSelection: RegionDetectionSelection = RegionDetectionSelection.BUBBLES_AND_TEXT
    ): TranslationResult? = withContext(Dispatchers.Default) {
        val page = ocrImage(imageFile, forceOcr, language, detectionSelection) { }
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

    private suspend fun detectImageBubbles(
        imageFile: File,
        sourceBitmap: Bitmap,
        detectionSelection: RegionDetectionSelection
    ): PageOcrResult? =
        withContext(Dispatchers.Default) {
            PipelineBitmapDecoder.openCropSource(sourceBitmap).use { cropSource ->
                val pageRegions = pageRegionDetector.detect(
                    cropSource = cropSource,
                    pageWidth = sourceBitmap.width,
                    pageHeight = sourceBitmap.height,
                    logTag = "Pipeline",
                    detectionSelection = detectionSelection
                ) ?: return@withContext null
                val bubbles = pageRegions.regions.map { region ->
                    OcrBubble(
                        id = region.id,
                        rect = region.rect,
                        text = "",
                        source = region.source,
                        maskContour = region.maskContour
                    )
                }
                PageOcrResult(imageFile, pageRegions.width, pageRegions.height, bubbles)
            }
        }

    private suspend fun recognizeBubblesIndividually(
        cropSource: BitmapCropSource,
        regions: List<PageRegion>,
        language: TranslationLanguage,
        useLocalOcr: Boolean
    ): List<OcrBubble> {
        val bubbles = ArrayList<OcrBubble>(regions.size)
        if (useLocalOcr) {
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
            val results = coroutineScope {
                regions.map { region ->
                    async(Dispatchers.IO) {
                        val text = recognizeRegionFromSource(
                            cropSource = cropSource,
                            rect = region.rect,
                            language = language,
                            useLocalOcr = false,
                            logTag = "Pipeline",
                            bubbleSource = region.source,
                            detectedLineRects = region.textLineRects
                        )
                        if (text.isBlank()) null
                        else OcrBubble(
                            id = region.id,
                            rect = region.rect,
                            text = text,
                            source = region.source,
                            maskContour = region.maskContour
                        )
                    }
                }.awaitAll()
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
        detectedLineRects: List<RectF>? = null
    ): String {
        val clamped = PipelineBitmapDecoder.clampRect(rect, cropSource.width, cropSource.height) ?: return ""
        val crop = cropSource.decodeRegion(clamped) ?: return ""
        return try {
            when (
                val result = bubbleTextRecognizer.recognizeCrop(
                    crop = crop,
                    language = language,
                    useLocalOcr = useLocalOcr,
                    logTag = logTag,
                    bubbleSource = bubbleSource,
                    detectedLineRects = mapPageLineRectsToCrop(
                        detectedLineRects,
                        clamped,
                        crop.width,
                        crop.height
                    )
                )
            ) {
                is OcrRecognitionResult.Success -> result.text
                is OcrRecognitionResult.Failure -> {
                    AppLogger.log(logTag, "OCR failed for region", result.error)
                    ""
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLogger.log(logTag, "OCR threw for region", e)
            ""
        } finally {
            crop.recycleSafely()
        }
    }

    private fun expandVlBubbleRect(rect: RectF, bitmapWidth: Int, bitmapHeight: Int): RectF {
        val h = maxOf(1f, rect.height())
            val pad = maxOf(
                TranslationCoreDefaults.VlBubbleExpandMin,
                TranslationCoreDefaults.VlBubbleExpandRatio * h
            )
        return RectF(
            (rect.left - pad).coerceIn(0f, bitmapWidth.toFloat()),
            (rect.top - pad).coerceIn(0f, bitmapHeight.toFloat()),
            (rect.right + pad).coerceIn(0f, bitmapWidth.toFloat()),
            (rect.bottom + pad).coerceIn(0f, bitmapHeight.toFloat())
        )
    }

    companion object {
        private const val STANDARD_PROMPT_ASSET = "prompts/llm_prompts.json"
        private const val FULL_TRANS_PROMPT_ASSET = "prompts/llm_prompts_FullTrans.json"
        private const val VL_PROMPT_ASSET = "prompts/vl_bubble_prompts.json"
        private const val MODEL_RESPONSE_SILENT_RETRY_COUNT = 3
    }

    /**
     * 按当前请求与设置构建“本次翻译将会写入”的期望 [TranslationMetadata]。
     *
     * 这是期望值的唯一来源：常规缓存读取（[loadValidTranslation]）与批量补填
     * 的 metadata 匹配都必须经由此函数构建期望值，再交给
     * [TranslationStore.matchesTranslationRequest] 比较，保证期望值与写入路径
     * 永远一致，新增影响译文的维度时不会在调用方漂移。
     */
    fun buildExpectedTranslationMetadata(
        imageFile: File,
        fullTranslate: Boolean,
        useVlDirectTranslate: Boolean,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection
    ): TranslationMetadata {
        val baseMetadata = when {
            useVlDirectTranslate -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_VL_DIRECT,
                promptAsset = VL_PROMPT_ASSET,
                ocrCacheMode = ""
            )
            fullTranslate -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_FULL_PAGE,
                promptAsset = FULL_TRANS_PROMPT_ASSET,
                ocrCacheMode = buildOcrCacheMode(
                    imageFile,
                    settingsStore.loadOcrApiSettings().useLocalOcr,
                    language,
                    detectionSelection
                )
            )
            else -> buildTranslationMetadata(
                imageFile = imageFile,
                language = language,
                mode = TranslationMetadata.MODE_STANDARD,
                promptAsset = STANDARD_PROMPT_ASSET,
                ocrCacheMode = buildOcrCacheMode(
                    imageFile,
                    settingsStore.loadOcrApiSettings().useLocalOcr,
                    language,
                    detectionSelection
                )
            )
        }
        return baseMetadata
    }

    private fun buildTranslationMetadata(
        imageFile: File,
        language: TranslationLanguage,
        mode: String,
        promptAsset: String,
        ocrCacheMode: String
    ): TranslationMetadata {
        val apiSettings = settingsStore.load()
        return TranslationMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            mode = mode,
            language = language.name,
            promptAsset = PromptAssetResolver.resolve(appContext, promptAsset),
            apiFormat = apiSettings.apiFormat.prefValue,
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
            "local:$cacheMode"
        } else {
            "api:${ocrSettings.modelName}"
        }
        return OcrMetadata(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            cacheMode = cacheMode,
            language = language.name,
            engineModel = engineModel
        )
    }

    private fun buildOcrCacheMode(
        imageFile: File,
        useLocalOcr: Boolean,
        language: TranslationLanguage,
        detectionSelection: RegionDetectionSelection = RegionDetectionSelection.BUBBLES_AND_TEXT
    ): String {
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
        return "$baseMode|$strategyTag|${detectionSelection.prefValue}"
    }

    private suspend fun <T> executeWithModelResponseRetries(
        logTag: String,
        block: suspend () -> T?
    ): T? {
        var lastError: LlmResponseException? = null
        repeat(MODEL_RESPONSE_SILENT_RETRY_COUNT) { attempt ->
            try {
                return block()
            } catch (e: LlmResponseException) {
                lastError = e
                AppLogger.log(
                    logTag,
                    "Model response invalid, retry ${attempt + 1}/$MODEL_RESPONSE_SILENT_RETRY_COUNT",
                    e
                )
            }
        }
        throw requireNotNull(lastError)
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
    val result: TranslationResult? = null,
    val timedOut: Boolean = false,
    val requiresVlModel: Boolean = false
)

data class PipelinePageTranslationOutcome(
    val result: TranslationResult,
    val glossaryUsed: Map<String, String> = emptyMap()
)
