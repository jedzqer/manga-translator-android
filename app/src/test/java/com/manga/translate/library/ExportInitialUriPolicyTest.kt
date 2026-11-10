package com.manga.translate.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportInitialUriPolicyTest {

    @Test
    fun `prefers app export folder when it exists`() {
        val probed = mutableListOf<String>()
        val docId = ExportInitialUriPolicy.selectDocId { candidate ->
            probed += candidate
            candidate == "primary:Documents/manga-translate"
        }

        assertEquals("primary:Documents/manga-translate", docId)
        assertEquals(listOf("primary:Documents/manga-translate"), probed)
    }

    @Test
    fun `falls back to Documents when app folder is missing`() {
        val docId = ExportInitialUriPolicy.selectDocId { it == "primary:Documents" }

        assertEquals("primary:Documents", docId)
    }

    @Test
    fun `returns null when no candidate directory exists`() {
        assertNull(ExportInitialUriPolicy.selectDocId { false })
    }

    @Test
    fun `builds tree uri for candidate doc ids`() {
        val uri = ExportInitialUriPolicy.buildTreeUri(
            ExportInitialUriPolicy.EXTERNAL_STORAGE_AUTHORITY,
            "primary:Documents/manga-translate"
        )

        assertEquals("com.android.externalstorage.documents", uri?.authority)
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fmanga-translate",
            uri?.toString()
        )
    }
}
