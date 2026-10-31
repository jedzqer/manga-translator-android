package com.manga.translate.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Task-local context. Extraction is sequential; translation workers only read a completed window. */
internal class FullTranslationSynopsis(
    initialText: String = "",
    private val onCommit: (String) -> Unit = {}
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FullTranslationSynopsis> {
        const val MAX_LENGTH = 6000
    }

    var text: String = initialText.trim().take(MAX_LENGTH)
        private set
    private val completedPages = mutableSetOf<String>()

    fun isComplete(sourceId: String): Boolean = sourceId in completedPages

    fun commit(sourceIds: List<String>, synopsis: String) {
        // An explicit empty synopsis carries no new plot information.
        if (!synopsis.isBlank()) {
            val updated = synopsis.trim().take(MAX_LENGTH)
            onCommit(updated)
            text = updated
        }
        completedPages.addAll(sourceIds)
    }
}
