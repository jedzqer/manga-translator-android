package com.manga.translate.reader

import com.manga.translate.model.TranslationResult
import com.manga.translate.storage.TranslationStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ReadingPageIdentity(val absolutePath: String)

/** Serializes editor saves independently from the view lifecycle and disk thread. */
internal class ReadingTranslationSaveCoordinator(
    private val translationStore: TranslationStore,
    private val onSaved: (ReadingPageIdentity) -> Unit = {},
    private val onFailure: (ReadingPageIdentity, Throwable) -> Unit = { _, _ -> }
) {
    private data class Request(
        val identity: ReadingPageIdentity,
        val imageFile: File,
        val result: TranslationResult,
        val revision: Long,
        val completion: CompletableDeferred<Result<File>>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val revisions = AtomicLong(0L)
    private val latestRevision = ConcurrentHashMap<String, Long>()
    private var pending = 0
    private var idle = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val worker: Job = scope.launch {
        for (request in requests) {
            try {
                val isCurrent = latestRevision[request.identity.absolutePath] == request.revision
                val saved = if (isCurrent) {
                    translationStore.save(request.imageFile, request.result)
                } else {
                    translationStore.translationFileFor(request.imageFile)
                }
                request.completion.complete(Result.success(saved))
                if (isCurrent) {
                    withContext(Dispatchers.Main.immediate) { onSaved(request.identity) }
                }
            } catch (error: Throwable) {
                request.completion.complete(Result.failure(error))
                withContext(Dispatchers.Main.immediate) { onFailure(request.identity, error) }
            } finally {
                markComplete()
            }
        }
    }

    fun submit(imageFile: File, result: TranslationResult): kotlinx.coroutines.Deferred<Result<File>> {
        val identity = ReadingPageIdentity(imageFile.absolutePath)
        val completion = CompletableDeferred<Result<File>>()
        val revision = revisions.incrementAndGet()
        synchronized(this) {
            if (pending == 0) idle = CompletableDeferred()
            pending += 1
            latestRevision[identity.absolutePath] = revision
        }
        val sendResult = requests.trySend(Request(identity, imageFile, result, revision, completion))
        if (sendResult.isFailure) {
            val error = sendResult.exceptionOrNull() ?: IllegalStateException("save queue closed")
            completion.complete(Result.failure(error))
            markComplete()
        }
        return completion
    }

    suspend fun flush() {
        val waitFor = synchronized(this) { idle }
        waitFor.await()
    }

    fun closeAfterPending() {
        scope.launch {
            flush()
            requests.close()
            worker.join()
            scope.cancel()
        }
    }

    private fun markComplete() {
        synchronized(this) {
            pending = (pending - 1).coerceAtLeast(0)
            if (pending == 0 && !idle.isCompleted) idle.complete(Unit)
        }
    }
}
