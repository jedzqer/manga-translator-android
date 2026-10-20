package com.manga.translate.translation

import com.manga.translate.R
import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryUiCallbacks
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmRequestException
import com.manga.translate.network.LlmResponseException
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Executes one page through the shared pipeline and handles model-error decisions. */
internal class FolderPageTranslationExecutor(
    private val translationPipeline: TranslationPipeline,
    private val settingsStore: SettingsStore,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val ui: LibraryUiCallbacks,
    private val pendingBubbleRetranslator: PendingBubbleRetranslator?,
    private val glossaryLifecycle: FolderTranslationGlossary,
    private val onModelResponseError: suspend (String) -> Boolean
) {
    private suspend fun executeStandardPageTranslation(
        folder: File,
        image: File,
        page: PageOcrResult?,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean,
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
            onlyTranslateBubbles = onlyTranslateBubbles,
        ) { }
            ?: return PageTranslationExecutionResult()
        var lastResponseException: LlmResponseException? = null
        var lastRequestException: LlmRequestException? = null
        try {
            val glossarySnapshot = glossaryLifecycle.snapshot(glossary, glossaryMutex)
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
                    glossaryLifecycle.mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
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
            val glossarySnapshot = glossaryLifecycle.snapshot(glossary, glossaryMutex)
            val translated = translationPipeline.translateFullPageWithGlossary(
                page = page,
                glossary = glossarySnapshot,
                promptAsset = promptAsset,
                language = language
            ) { }
            if (translated != null) {
                val glossaryUsed = translated.glossaryUsed
                glossaryLifecycle.mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
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

    suspend fun executeVlPageTranslation(
        image: File,
        language: TranslationLanguage,
        glossary: MutableMap<String, String>,
        glossaryMutex: Mutex,
        folder: File,
        glossaryProcessingEnabled: Boolean,
        force: Boolean,
        fullVisual: Boolean = false
    ): PageTranslationExecutionResult {
        val existing = if (force) null else translationPipeline.loadAnyTranslation(image)
        if (existing != null && (existing.bubbles.none { it.needsTranslationRetry() } || existing.hasCrossPageBubbleGeometry())) {
            return PageTranslationExecutionResult(result = existing)
        }
        val outcome = translationPipeline.translateImageWithVl(
            imageFile = image,
            language = language,
            glossary = glossaryLifecycle.snapshot(glossary, glossaryMutex),
            glossaryProcessingEnabled = glossaryProcessingEnabled,
            fullVisual = fullVisual,
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
                if (glossaryProcessingEnabled) glossaryLifecycle.mergeGlossary(glossary, outcome.glossaryUsed, glossaryMutex, folder)
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
        if (preferencesGateway.getReadingMode(folder) != FolderReadingMode.WEBTOON_SCROLL && existing.hasCrossPageBubbleGeometry()) {
            return preserved
        }
        val glossarySnapshot = glossaryLifecycle.snapshot(glossary, glossaryMutex)
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
            glossaryLifecycle.mergeGlossary(glossary, glossaryUsed, glossaryMutex, folder)
        }
        AppLogger.log("Library", "Partial refill applied for ${image.name}")
        return PageTranslationExecutionResult(
            result = outcome.translation,
            glossaryUsed = glossaryUsed
        )
    }


    suspend fun skipStandardImage(
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
     * returns an invalid response, asks the user via [onModelResponseError] whether to
     * retry the page or skip it. SKIP executes [onSkipPage] (which marks the page
     * as skipped) and returns immediately; RETRY loops back into the guarded block.
     */
    suspend fun <Result> executeGuardedTranslation(
        apiSemaphore: Semaphore,
        execute: suspend () -> Result,
        onSkipPage: suspend () -> Result
    ): Result {
        while (true) {
            try {
                return apiSemaphore.withPermit { execute() }
            } catch (e: LlmResponseException) {
                if (onModelResponseError(e.responseContent)) {
                    return onSkipPage()
                }
            }
        }
    }

    suspend fun executeStandardPageWithModelErrorResolution(
        apiSemaphore: Semaphore,
        folder: File,
        image: File,
        page: PageOcrResult?,
        force: Boolean,
        glossaryProcessingEnabled: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false,
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
                    onlyTranslateBubbles = onlyTranslateBubbles,
                    glossary = glossary,
                    glossaryMutex = glossaryMutex
                )
            },
            onSkipPage = {
                val pageToSkip = page ?: translationPipeline.ocrImage(
                    image,
                    force,
                    language,
                    onlyTranslateBubbles = onlyTranslateBubbles,
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

    suspend fun executeFullPageWithModelErrorResolution(
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

    suspend fun skipFullPageImage(
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

}
