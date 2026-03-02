package com.manga.translate

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class FolderTranslationCoordinator(
    context: Context,
    private val translationPipeline: TranslationPipeline,
    private val glossaryStore: GlossaryStore,
    private val extractStateStore: ExtractStateStore,
    private val translationStore: TranslationStore,
    private val settingsStore: SettingsStore,
    private val ui: LibraryUiCallbacks
) {
    private val appContext = context.applicationContext
    private val translationRunning = AtomicBoolean(false)

    fun translateFolder(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        fullTranslate: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ) {
        if (fullTranslate) {
            translateFolderFull(scope, folder, images, force, language, onTranslateEnabled)
        } else {
            translateFolderStandard(scope, folder, images, force, language, onTranslateEnabled)
        }
    }

    private fun translateFolderStandard(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ) {
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }
        val pendingImages = resolvePendingImages(images, force)
        if (pendingImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            return
        }
        val llmClient = LlmClient(appContext)
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return
        }
        if (!translationRunning.compareAndSet(false, true)) {
            ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
            return
        }

        onTranslateEnabled(false)
        try {
            TranslationKeepAliveService.start(appContext)
            TranslationKeepAliveService.updateStatus(
                appContext,
                appContext.getString(R.string.translation_preparing)
            )
            AppLogger.log(
                "Library",
                "Start translating folder ${folder.name}, ${pendingImages.size} images"
            )

            scope.launch {
                var failed = false
                try {
                    val glossary = glossaryStore.load(folder)
                    var translatedCount = 0
                    ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
                    for (image in pendingImages) {
                        val result = try {
                            translationPipeline.translateImage(image, glossary, force, language) { }
                        } catch (e: LlmRequestException) {
                            AppLogger.log("Library", "Translation aborted for ${image.name}", e)
                            ui.showApiError(e.errorCode)
                            failed = true
                            break
                        } catch (e: LlmResponseException) {
                            AppLogger.log("Library", "Invalid model response for ${image.name}", e)
                            ui.showModelError(e.responseContent)
                            failed = true
                            break
                        } catch (e: Exception) {
                            AppLogger.log("Library", "Translation failed for ${image.name}", e)
                            null
                        }
                        if (result != null) {
                            translationPipeline.saveResult(image, result)
                            translatedCount += 1
                        } else {
                            failed = true
                        }
                        if (glossary.isNotEmpty()) {
                            glossaryStore.save(folder, glossary)
                        }
                        ui.setFolderStatus(
                            appContext.getString(
                                R.string.folder_translation_count,
                                translatedCount,
                                pendingImages.size
                            )
                        )
                        TranslationKeepAliveService.updateProgress(
                            appContext,
                            translatedCount,
                            pendingImages.size
                        )
                        if (failed) break
                    }
                    ui.setFolderStatus(
                        if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                            R.string.translation_done
                        )
                    )
                    AppLogger.log(
                        "Library",
                        "Folder translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                    )
                    ui.refreshImages(folder)
                } finally {
                    onTranslateEnabled(true)
                    TranslationKeepAliveService.stop(appContext)
                    translationRunning.set(false)
                }
            }
        } catch (e: Exception) {
            onTranslateEnabled(true)
            TranslationKeepAliveService.stop(appContext)
            translationRunning.set(false)
            AppLogger.log("Library", "Failed to start folder translation ${folder.name}", e)
            ui.setFolderStatus(appContext.getString(R.string.translation_failed))
        }
    }

    private fun translateFolderFull(
        scope: CoroutineScope,
        folder: File,
        images: List<File>,
        force: Boolean,
        language: TranslationLanguage,
        onTranslateEnabled: (Boolean) -> Unit
    ) {
        if (images.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.folder_images_empty))
            return
        }
        val pendingImages = resolvePendingImages(images, force)
        if (pendingImages.isEmpty()) {
            ui.setFolderStatus(appContext.getString(R.string.translation_done))
            return
        }
        val llmClient = LlmClient(appContext)
        if (!llmClient.isConfigured()) {
            ui.setFolderStatus(appContext.getString(R.string.missing_api_settings))
            return
        }
        if (!translationRunning.compareAndSet(false, true)) {
            ui.setFolderStatus(appContext.getString(R.string.translation_preparing))
            return
        }

        onTranslateEnabled(false)
        try {
            TranslationKeepAliveService.start(appContext)
            TranslationKeepAliveService.updateStatus(
                appContext,
                appContext.getString(R.string.translation_preparing)
            )
            AppLogger.log(
                "Library",
                "Start full-page translating folder ${folder.name}, ${pendingImages.size} images"
            )

            scope.launch {
                var failed = false
                try {
                    val glossary = glossaryStore.load(folder).toMutableMap()
                    val extractState = extractStateStore.load(folder)
                    val ocrResults = ArrayList<PageOcrResult>(pendingImages.size)
                    reportPreprocessProgress(
                        stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                        processed = 0,
                        total = pendingImages.size
                    )
                    for ((index, image) in pendingImages.withIndex()) {
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            processed = index,
                            total = pendingImages.size,
                            imageName = image.name
                        )
                        val result = try {
                            translationPipeline.ocrImage(image, force, language) { }
                        } catch (e: Exception) {
                            AppLogger.log("Library", "OCR failed for ${image.name}", e)
                            null
                        }
                        if (result != null) {
                            ocrResults.add(result)
                        } else {
                            failed = true
                        }
                        reportPreprocessProgress(
                            stage = appContext.getString(R.string.folder_preprocess_stage_ocr),
                            processed = index + 1,
                            total = pendingImages.size,
                            imageName = image.name
                        )
                    }

                    val glossaryPages = ocrResults.filterNot {
                        translationStore.translationFileFor(it.imageFile).exists() ||
                            extractState.contains(it.imageFile.name)
                    }
                    val glossaryText = buildGlossaryText(glossaryPages)
                    if (glossaryText.isNotBlank()) {
                        val glossaryStage = appContext.getString(R.string.folder_preprocess_stage_glossary)
                        val glossaryImage = glossaryPages.firstOrNull()?.imageFile?.name
                        reportPreprocessProgress(
                            stage = glossaryStage,
                            processed = 0,
                            total = 1,
                            imageName = glossaryImage.orEmpty()
                        )
                        val abstractPromptAsset = "llm_prompts_abstract.json"
                        val extracted = llmClient.extractGlossary(glossaryText, glossary, abstractPromptAsset)
                        if (extracted != null) {
                            if (extracted.isNotEmpty()) {
                                for ((key, value) in extracted) {
                                    if (!glossary.containsKey(key)) {
                                        glossary[key] = value
                                    }
                                }
                                glossaryStore.save(folder, glossary)
                            }
                            for (page in glossaryPages) {
                                extractState.add(page.imageFile.name)
                            }
                            extractStateStore.save(folder, extractState)
                        }
                        reportPreprocessProgress(
                            stage = glossaryStage,
                            processed = 1,
                            total = 1,
                            imageName = glossaryImage.orEmpty()
                        )
                    }

                    val maxConcurrency = settingsStore.loadMaxConcurrency()
                    val semaphore = Semaphore(maxConcurrency)
                    val translatedCount = AtomicInteger(0)
                    val hasFailures = AtomicBoolean(false)
                    val requestFailed = AtomicBoolean(false)
                    val reportedModelError = AtomicBoolean(false)
                    ui.setFolderStatus(appContext.getString(R.string.translation_preparing))

                    coroutineScope {
                        val tasks = ocrResults.map { page ->
                            async {
                                semaphore.withPermit {
                                    if (requestFailed.get()) {
                                        return@withPermit
                                    }
                                    val fullTransPromptAsset = "llm_prompts_FullTrans.json"
                                    val result = try {
                                        translationPipeline.translateFullPage(
                                            page,
                                            glossary,
                                            fullTransPromptAsset,
                                            language
                                        ) { }
                                    } catch (e: LlmResponseException) {
                                        AppLogger.log(
                                            "Library",
                                            "Invalid model response for ${page.imageFile.name}",
                                            e
                                        )
                                        hasFailures.set(true)
                                        if (reportedModelError.compareAndSet(false, true)) {
                                            withContext(Dispatchers.Main) {
                                                ui.showModelError(e.responseContent)
                                            }
                                        }
                                        null
                                    } catch (e: LlmRequestException) {
                                        requestFailed.set(true)
                                        throw e
                                    } catch (e: Exception) {
                                        AppLogger.log(
                                            "Library",
                                            "Full-page translation failed for ${page.imageFile.name}",
                                            e
                                        )
                                        null
                                    }
                                    if (requestFailed.get()) {
                                        return@withPermit
                                    }
                                    if (result != null) {
                                        translationPipeline.saveResult(page.imageFile, result)
                                        translatedCount.incrementAndGet()
                                    } else {
                                        hasFailures.set(true)
                                    }
                                    withContext(Dispatchers.Main) {
                                        val count = translatedCount.get()
                                        ui.setFolderStatus(
                                            appContext.getString(
                                                R.string.folder_translation_count,
                                                count,
                                                pendingImages.size
                                            )
                                        )
                                        TranslationKeepAliveService.updateProgress(
                                            appContext,
                                            count,
                                            pendingImages.size
                                        )
                                    }
                                }
                            }
                        }
                        tasks.awaitAll()
                    }

                    failed = failed || hasFailures.get()
                    ui.setFolderStatus(
                        if (failed) appContext.getString(R.string.translation_failed) else appContext.getString(
                            R.string.translation_done
                        )
                    )
                    AppLogger.log(
                        "Library",
                        "Full-page translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                    )
                    ui.refreshImages(folder)
                } catch (e: LlmRequestException) {
                    AppLogger.log("Library", "Full-page translation aborted", e)
                    ui.showApiError(e.errorCode)
                    ui.setFolderStatus(appContext.getString(R.string.translation_failed))
                } finally {
                    onTranslateEnabled(true)
                    TranslationKeepAliveService.stop(appContext)
                    translationRunning.set(false)
                }
            }
        }
        catch (e: Exception) {
            onTranslateEnabled(true)
            TranslationKeepAliveService.stop(appContext)
            translationRunning.set(false)
            AppLogger.log("Library", "Failed to start full-page translation ${folder.name}", e)
            ui.setFolderStatus(appContext.getString(R.string.translation_failed))
        }
    }

    private fun buildGlossaryText(pages: List<PageOcrResult>): String {
        val builder = StringBuilder()
        for (page in pages) {
            for (bubble in page.bubbles) {
                val text = bubble.text.trim()
                if (text.isNotBlank()) {
                    builder.append("<b>").append(text).append("</b>\n")
                }
            }
        }
        return builder.toString().trim()
    }

    private fun resolvePendingImages(images: List<File>, force: Boolean): List<File> {
        return if (force) {
            images
        } else {
            images.filterNot { translationStore.translationFileFor(it).exists() }
        }
    }

    private fun reportPreprocessProgress(
        stage: String,
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
            appContext.getString(R.string.translation_keepalive_message)
        )
    }
}
