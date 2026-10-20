package com.manga.translate.di

import android.content.Context
import com.manga.translate.app.MangaTranslateApp
import com.manga.translate.detection.LocalModelMemoryManager
import com.manga.translate.detection.OnnxRuntimeSupport
import com.manga.translate.floating.FloatingEmptyBubbleCoordinator
import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.library.LibraryUiCallbacks
import com.manga.translate.library.ExportTaskHost
import com.manga.translate.network.LlmClient
import com.manga.translate.ocr.BubbleTextRecognizer
import com.manga.translate.ocr.OcrEngineRegistry
import com.manga.translate.reader.ReadingEmptyBubbleCoordinator
import com.manga.translate.settings.SettingsStore
import com.manga.translate.storage.CrashStateStore
import com.manga.translate.storage.ExtractStateStore
import com.manga.translate.storage.FloatingTranslationCacheStore
import com.manga.translate.storage.GlossaryStore
import com.manga.translate.storage.OcrStore
import com.manga.translate.storage.ReadingProgressStore
import com.manga.translate.storage.TranslationProgressStore
import com.manga.translate.storage.TranslationStore
import com.manga.translate.storage.UpdateIgnoreStore
import com.manga.translate.translation.FloatingBubbleTranslationCoordinator
import com.manga.translate.translation.FolderTranslationCoordinator
import com.manga.translate.translation.PendingBubbleRetranslator
import com.manga.translate.translation.TextBubbleTranslationCoordinator
import com.manga.translate.translation.TranslationPipeline
import com.manga.translate.translation.TranslationStrategyResolver
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

internal class AppContainer(private val appContext: Context) {
    private val translationPipelines = CopyOnWriteArrayList<WeakReference<TranslationPipeline>>()
    val customPromptStore = com.manga.translate.settings.CustomPromptStore(appContext)
    val settingsStore = SettingsStore(appContext)
    val crashStateStore = CrashStateStore(appContext)
    val updateIgnoreStore = UpdateIgnoreStore(appContext)
    val readingProgressStore = ReadingProgressStore(appContext)
    val libraryRepository = LibraryRepository(appContext)
    val exportTaskHost = ExportTaskHost()
    val llmClient = LlmClient(appContext, settingsStore)
    val ocrEngineRegistry = com.manga.translate.ocr.OcrEngineRegistry(appContext, settingsStore)
    val localModelMemoryManager = LocalModelMemoryManager {
        releasePipelineModels()
        ocrEngineRegistry.releaseLoadedEngines()
        OnnxRuntimeSupport.closeCachedSessions()
    }
    val bubbleTextRecognizer =
        com.manga.translate.ocr.BubbleTextRecognizer(llmClient, ocrEngineRegistry)
    val translationStore = TranslationStore()
    val ocrStore = OcrStore()
    val glossaryStore = GlossaryStore()
    val extractStateStore = ExtractStateStore()
    val translationProgressStore = TranslationProgressStore()
    val floatingTranslationCacheStore = FloatingTranslationCacheStore(appContext)
    val vlPageTranslationCoordinator = com.manga.translate.translation.VlPageTranslationCoordinator(llmClient, floatingTranslationCacheStore)
    val textBubbleTranslationCoordinator = TextBubbleTranslationCoordinator(llmClient = llmClient)
    private val libraryPrefs = appContext.getSharedPreferences(LIBRARY_PREFS_NAME, Context.MODE_PRIVATE)

    val libraryPreferencesGateway = LibraryPreferencesGateway(appContext, libraryPrefs, libraryRepository)
    val translationStrategyResolver = TranslationStrategyResolver { folder, globalStyle ->
        libraryPreferencesGateway.resolveTranslationStyle(folder, globalStyle)
    }

    fun createTranslationPipeline(): TranslationPipeline {
        return TranslationPipeline(
            context = appContext,
            llmClient = llmClient,
            settingsStore = settingsStore,
            store = translationStore,
            ocrStore = ocrStore,
            ocrEngineRegistry = ocrEngineRegistry,
            bubbleTextRecognizer = bubbleTextRecognizer,
            textBubbleTranslationCoordinator = textBubbleTranslationCoordinator,
            vlPageTranslationCoordinator = vlPageTranslationCoordinator,
            strategyResolver = translationStrategyResolver
        ).also { pipeline ->
            translationPipelines.add(WeakReference(pipeline))
        }
    }

    private fun releasePipelineModels() {
        translationPipelines.removeAll { reference ->
            val pipeline = reference.get()
            if (pipeline == null) {
                true
            } else {
                pipeline.releaseLoadedModels()
                false
            }
        }
    }

    fun createFolderTranslationCoordinator(
        translationPipeline: TranslationPipeline,
        ui: LibraryUiCallbacks,
        taskProgress: com.manga.translate.platform.TaskProgressWriter
    ): FolderTranslationCoordinator {
        return FolderTranslationCoordinator(
            context = appContext,
            translationPipeline = translationPipeline,
            glossaryStore = glossaryStore,
            extractStateStore = extractStateStore,
            translationStore = translationStore,
            ocrStore = ocrStore,
            settingsStore = settingsStore,
            preferencesGateway = libraryPreferencesGateway,
            repository = libraryRepository,
            llmClient = llmClient,
            ui = ui,
            progressStore = translationProgressStore,
            pendingBubbleRetranslator = createPendingBubbleRetranslator(),
            taskProgress = taskProgress
        )
    }

    fun createPendingBubbleRetranslator(): PendingBubbleRetranslator {
        return PendingBubbleRetranslator(
            context = appContext,
            settingsStore = settingsStore,
            bubbleTextRecognizer = bubbleTextRecognizer,
            textBubbleTranslationCoordinator = textBubbleTranslationCoordinator
        )
    }

    fun createReadingEmptyBubbleCoordinator(): ReadingEmptyBubbleCoordinator {
        return ReadingEmptyBubbleCoordinator(
            context = appContext,
            translationStore = translationStore,
            glossaryStore = glossaryStore,
            repository = libraryRepository,
            preferencesGateway = libraryPreferencesGateway,
            settingsStore = settingsStore,
            bubbleTextRecognizer = bubbleTextRecognizer,
            textBubbleTranslationCoordinator = textBubbleTranslationCoordinator,
            localModelMemoryManager = localModelMemoryManager,
            translationStrategyResolver = translationStrategyResolver,
            translationPipelineFactory = ::createTranslationPipeline
        )
    }

    fun createFloatingEmptyBubbleCoordinator(): FloatingEmptyBubbleCoordinator {
        return FloatingEmptyBubbleCoordinator(
            context = appContext,
            llmClient = llmClient,
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore,
            bubbleTextRecognizer = bubbleTextRecognizer,
            floatingBubbleTranslationCoordinator = createFloatingBubbleTranslationCoordinator()
        )
    }

    fun createFloatingBubbleTranslationCoordinator(): FloatingBubbleTranslationCoordinator {
        return FloatingBubbleTranslationCoordinator(
            llmClient = llmClient,
            floatingTranslationCacheStore = floatingTranslationCacheStore,
            settingsStore = settingsStore,
            vlPageCoordinator = vlPageTranslationCoordinator
        )
    }

    companion object {
        private const val LIBRARY_PREFS_NAME = "library_prefs"
    }
}

internal val Context.appContainer: AppContainer
    get() = (applicationContext as MangaTranslateApp).appContainer
