package com.manga.translate.translation

import com.manga.translate.storage.SynopsisStore.Snapshot
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Task-local context. Extraction is sequential; translation workers only read a completed window. */
internal class FullTranslationSynopsis(
    initial: Snapshot,
    private val onCommit: (Snapshot, String?) -> Unit
) : AbstractCoroutineContextElement(Key) {
    constructor(initialText: String = "", onCommit: (String) -> Unit = {}) : this(
        Snapshot(initialText), { _, newText -> newText?.let(onCommit) }
    )

    companion object Key : CoroutineContext.Key<FullTranslationSynopsis> {
        const val MAX_LENGTH = 6000
    }

    private var snapshot = initial
    val text: String get() = snapshot.text.trim().take(MAX_LENGTH)

    fun isComplete(sourceId: String): Boolean = pageName(sourceId) in snapshot.completedPages

    fun commit(sourceIds: List<String>, synopsis: String) {
        val newText = synopsis.trim().take(MAX_LENGTH).takeIf { it.isNotEmpty() }
        val updated = Snapshot(
            text = newText ?: snapshot.text,
            completedPages = snapshot.completedPages + sourceIds.map(::pageName)
        )
        onCommit(updated, newText)
        snapshot = updated
    }

    // Page names are unique inside the folder and survive a folder move.
    private fun pageName(sourceId: String): String = File(sourceId).name
}
