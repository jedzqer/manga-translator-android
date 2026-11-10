package com.manga.translate.library

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Picks the directory a document-tree picker starts in.
 *
 * `DocumentsContract.EXTRA_INITIAL_URI` must name a document the provider can resolve. Some
 * OEM pickers (observed on Honor MagicOS) abort the picker activity when the initial document
 * is missing, which looks like the app closing right after the "choose export directory" toast.
 * Only directories known to exist are offered, otherwise the picker keeps its own default.
 */
internal object ExportInitialUriPolicy {

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Most specific first: the app's default export folder, then its parent. */
    val candidateDocIds: List<String> = listOf(
        "primary:Documents/manga-translate",
        "primary:Documents"
    )

    fun selectDocId(exists: (String) -> Boolean): String? = candidateDocIds.firstOrNull(exists)

    fun buildTreeUri(authority: String, docId: String): Uri? = runCatching {
        DocumentsContract.buildTreeDocumentUri(authority, docId)
    }.getOrNull()
}
