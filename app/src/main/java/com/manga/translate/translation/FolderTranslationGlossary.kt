package com.manga.translate.translation

import com.manga.translate.model.PageOcrResult
import com.manga.translate.network.LlmClient
import com.manga.translate.network.LlmResponseException
import com.manga.translate.network.executeWithModelResponseRetries
import com.manga.translate.platform.AppLogger
import com.manga.translate.storage.ExtractStateStore
import com.manga.translate.storage.GlossaryStore
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns folder-scoped glossary and extraction state for the lifetime of a task. */
internal class FolderTranslationGlossary(
    private val glossaryStore: GlossaryStore,
    private val extractStateStore: ExtractStateStore,
    private val llmClient: LlmClient,
    private val targetKeyProvider: () -> String
) {
    private val translationTargetKey: String get() = targetKeyProvider()

    fun loadScopedGlossary(folder: File): MutableMap<String, String> {
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

    suspend fun saveScopedGlossary(
        folder: File,
        glossary: MutableMap<String, String>,
        additions: Map<String, String>
    ) {
        if (additions.isEmpty()) return
        val changed = additions.filter { (key, value) ->
            key.isNotBlank() && value.isNotBlank() && !glossary.containsKey(key)
        }
        changed.forEach { (key, value) -> glossary[key] = value }
        if (changed.isNotEmpty()) glossaryWriter.submit(folder, changed)
    }

    /** Lands any coalesced glossary write still pending. Survives cancellation. */
    suspend fun flushPendingGlossary() {
        withContext(NonCancellable) {
            try {
                glossaryWriter.flushAll()
            } catch (e: Exception) {
                AppLogger.log("Library", "Failed to flush glossary", e)
            }
        }
    }

    fun loadScopedExtractState(folder: File): MutableSet<String> {
        return extractStateStore.load(folder, translationTargetKey)
    }

    fun saveScopedExtractState(folder: File, extracted: Set<String>) {
        extractStateStore.save(folder, extracted, translationTargetKey)
    }

    suspend fun flush(folder: File) = glossaryWriter.flush(folder)

    suspend fun snapshot(glossary: MutableMap<String, String>, mutex: Mutex): Map<String, String> =
        mutex.withLock { LinkedHashMap(glossary) }

    /** Complete extraction for every chunk before recording the window as extracted. */
    suspend fun extractWindow(
        folder: File,
        pages: List<PageOcrResult>,
        glossary: MutableMap<String, String>,
        extractState: MutableSet<String>,
        shouldSkip: (PageOcrResult) -> Boolean,
        onProgress: suspend (String) -> Unit,
        onInvalidResponse: suspend (String) -> Boolean
    ): Boolean {
        val glossaryPages = pages.filterNot(shouldSkip)
        var extractionComplete = true
        var hadModelError = false
        val glossaryImage = glossaryPages.firstOrNull()?.imageFile?.name.orEmpty()
        for (text in glossaryTextBatches(glossaryPages)) {
            currentCoroutineContext().ensureActive()
            onProgress(glossaryImage)
            while (true) {
                try {
                    val extracted = executeWithModelResponseRetries("Library") {
                        llmClient.extractGlossary(text, glossary, "prompts/llm_prompts_abstract.json")
                    }
                    if (extracted == null) {
                        extractionComplete = false
                    } else if (extracted.isNotEmpty()) {
                        // Existing translations remain authoritative during extraction.
                        val additions = extracted.filterKeys { !glossary.containsKey(it) }
                        saveScopedGlossary(folder, glossary, additions)
                    }
                    break
                } catch (e: LlmResponseException) {
                    AppLogger.log("Library", "Full-page glossary response invalid", e)
                    if (onInvalidResponse(e.responseContent)) {
                        extractionComplete = false
                        hadModelError = true
                        break
                    }
                }
            }
        }
        // A page can span requests. Failed/cancelled extraction remains retryable.
        if (extractionComplete && glossaryPages.isNotEmpty()) {
            glossaryPages.forEach { extractState.add(it.imageFile.name) }
            saveScopedExtractState(folder, extractState)
        }
        return hadModelError
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
    suspend fun mergeGlossary(
        glossary: MutableMap<String, String>,
        additions: Map<String, String>,
        glossaryMutex: Mutex,
        folder: File
    ) {
        if (additions.isEmpty()) return
        val additionsToSave = glossaryMutex.withLock {
            // The first accepted translation is authoritative for this task.
            // Concurrent requests may return conflicting guesses for the same
            // source term; never let a later response rewrite the glossary.
            val changed = additions.filter { (key, value) ->
                key.isNotBlank() && value.isNotBlank() && !glossary.containsKey(key)
            }
            changed.forEach { (key, value) -> glossary[key] = value }
            changed
        }
        if (additionsToSave.isNotEmpty()) glossaryWriter.submit(folder, additionsToSave)
    }

}
