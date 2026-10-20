package com.manga.translate.translation

import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Full translation keeps one OCR window and its webtoon lookahead in memory. */
internal class FolderFullTranslationWorkflow(
    private val translationPipeline: TranslationPipeline,
    private val settingsStore: SettingsStore,
    private val preferencesGateway: LibraryPreferencesGateway,
    private val repository: LibraryRepository,
    private val ocrPreparation: FolderOcrPreparation,
    private val scheduler: FolderTranslationScheduler,
    private val glossaryLifecycle: FolderTranslationGlossary,
    private val onModelResponseError: suspend (String) -> Boolean
) {
    /** Shared by folder, collection and batch full translation. Retain only one OCR window. */
    suspend fun execute(
        pages: List<File>,
        folder: File,
        force: Boolean,
        language: TranslationLanguage,
        onlyTranslateBubbles: Boolean = false,
        onPrepareProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onGlossaryProgress: suspend (processed: Int, total: Int, imageName: String) -> Unit,
        onCountUpdated: suspend (processedCount: Int, failedCount: Int) -> Unit
    ): PageExecutionSummary {
        val glossary = glossaryLifecycle.loadScopedGlossary(folder)
        val extractState = glossaryLifecycle.loadScopedExtractState(folder)
        val glossaryMutex = Mutex()
        val readingMode = preferencesGateway.getReadingMode(folder)
        val mergeAcrossPages = readingMode == FolderReadingMode.WEBTOON_SCROLL
        val pagePositions = if (mergeAcrossPages) withContext(Dispatchers.IO) { repository.listImages(folder).withIndex().associate { it.value to it.index } } else emptyMap()
        var failures = 0
        var hasFailures = false
        onCountUpdated(0, 0)
        forEachPreparedPageWindow(
            pages = pages,
            windowSize = translationWindowSize(settingsStore.loadTranslationBatchPages()),
            lookAhead = mergeAcrossPages,
            prepare = { window, offset ->
                ocrPreparation.prepareFullPagesConcurrent(
                    window, force, language, onlyTranslateBubbles
                ) { count, _, name ->
                    onPrepareProgress(offset + count, pages.size, name)
                }
            },
            transform = { prepared ->
                if (mergeAcrossPages) mergeOcrWindow(prepared, pagePositions) else prepared
            },
            process = { prepared, offset ->
                val ocrResults = prepared.filterNotNull()
                val preprocessFailures = prepared.size - ocrResults.size
                val glossaryImage = ocrResults.firstOrNull {
                    !translationPipeline.hasValidTranslation(it.imageFile) &&
                        !extractState.contains(it.imageFile.name)
                }?.imageFile?.name.orEmpty()
                if (glossaryLifecycle.extractWindow(
                    folder = folder,
                    pages = ocrResults,
                    glossary = glossary,
                    extractState = extractState,
                    shouldSkip = {
                        translationPipeline.hasValidTranslation(it.imageFile) ||
                            extractState.contains(it.imageFile.name)
                    },
                    onProgress = { onGlossaryProgress(offset, pages.size, it) },
                    onInvalidResponse = onModelResponseError
                )) {
                    hasFailures = true
                }
                onGlossaryProgress(offset + prepared.size, pages.size, glossaryImage)
                val summary = scheduler.executeConcurrentFullPages(
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



}
