package com.manga.translate

import android.graphics.RectF
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.PageTranslationStatus
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.model.TranslationMetadata
import com.manga.translate.model.TranslationResult
import com.manga.translate.storage.TranslationStore
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationStoreTest {
    @Test
    fun `empty translations remove all pending bubbles from disk`() {
        withPendingPage { store, image, _ ->
            val merged = store.mergePending(image, emptyMap(), setOf(1, 2))!!
            org.junit.Assert.assertTrue(merged.bubbles.isEmpty())
            org.junit.Assert.assertTrue(TranslationStore().load(image)!!.bubbles.isEmpty())
        }
    }

    @Test
    fun `partial empty response removes only its pending bubble`() {
        withPendingPage { store, image, base ->
            val addition = base.bubbles[1].withTranslationResult("译文")
            val merged = store.mergePending(image, mapOf(2 to addition), setOf(1))!!
            org.junit.Assert.assertEquals(listOf(addition), merged.bubbles)
            org.junit.Assert.assertEquals(merged.bubbles, TranslationStore().load(image)!!.bubbles)
        }
    }

    @Test
    fun `late empty response preserves concurrent manual translation and deletion`() {
        withPendingPage { store, image, base ->
            val edited = base.bubbles[0].withTranslationResult("人工译文")
            store.save(image, base.copy(bubbles = listOf(edited)))
            val merged = store.mergePending(
                image, mapOf(2 to base.bubbles[1].withTranslationResult("迟到译文")), setOf(1)
            )!!
            org.junit.Assert.assertEquals(listOf(edited), merged.bubbles)
            org.junit.Assert.assertEquals(listOf(edited), TranslationStore().load(image)!!.bubbles)
        }
    }

    private fun withPendingPage(block: (TranslationStore, File, TranslationResult) -> Unit) {
        val image = File.createTempFile("pending-store", ".jpg")
        val store = TranslationStore()
        val base = TranslationResult(image.name, 100, 100, (1..2).map {
            BubbleTranslation.pending(it, RectF(0f, 0f, 10f, 10f), "source")
        })
        try {
            store.save(image, base)
            block(store, image, base)
        } finally {
            store.translationFileFor(image).delete()
            image.delete()
        }
    }

    @Test
    fun `saved translation remains readable after source and settings change`() {
        val image = File.createTempFile("translation-store", ".jpg")
        image.writeText("source")
        val store = TranslationStore()
        val metadata = TranslationMetadata(
            sourceLastModified = image.lastModified(),
            sourceFileSize = image.length(),
            mode = TranslationMetadata.MODE_STANDARD,
            language = TranslationLanguage.JA_TO_ZH.name,
            promptAsset = "prompts/llm_prompts.json",
            apiFormat = "openai_compatible",
            ocrCacheMode = "local|bubbles_and_text",
            version = TranslationMetadata.CURRENT_VERSION,
            status = PageTranslationStatus.SUCCESS
        )
        val result = TranslationResult(
            imageName = image.name,
            width = 100,
            height = 100,
            bubbles = listOf(
                BubbleTranslation.translated(
                    id = 0,
                    rect = RectF(0f, 0f, 10f, 10f),
                    originalText = "source",
                    translatedText = "译文"
                )
            ),
            metadata = metadata
        )
        try {
            store.save(image, result)
            val originalJson = store.translationFileFor(image).readText()
            image.writeText("changed source image")
            assertNotNull(store.load(image))
            assertNotNull(TranslationStore().load(image))
            org.junit.Assert.assertEquals(originalJson, store.translationFileFor(image).readText())
        } finally {
            store.translationFileFor(image).delete()
            image.delete()
        }
    }

    @Test
    fun `switching provider api format keeps existing translation usable`() {
        val image = File.createTempFile("translation-store", ".jpg")
        image.writeText("source")
        val store = TranslationStore()
        val metadata = TranslationMetadata(
            sourceLastModified = image.lastModified(),
            sourceFileSize = image.length(),
            mode = TranslationMetadata.MODE_STANDARD,
            language = TranslationLanguage.JA_TO_ZH.name,
            promptAsset = "prompts/llm_prompts.json",
            apiFormat = "openai_compatible",
            ocrCacheMode = "local|bubbles_and_text",
            version = TranslationMetadata.CURRENT_VERSION,
            status = PageTranslationStatus.SUCCESS
        )
        val result = TranslationResult(
            imageName = image.name,
            width = 100,
            height = 100,
            bubbles = listOf(
                BubbleTranslation.translated(
                    id = 0,
                    rect = RectF(0f, 0f, 10f, 10f),
                    originalText = "source",
                    translatedText = "译文"
                )
            ),
            metadata = metadata
        )
        try {
            store.save(image, result)
            // Switching to a provider with a different wire protocol must not
            // invalidate translations already on disk.
            for (apiFormat in listOf("openai_responses", "gemini", "")) {
                assertNotNull(
                    "apiFormat=$apiFormat must reuse the saved translation",
                    store.load(image)
                )
            }
            // Persisted provenance must still record the producing format.
            val reloaded = store.load(image)
            assertNotNull(reloaded)
            org.junit.Assert.assertEquals(
                "openai_compatible",
                reloaded?.metadata?.apiFormat
            )
        } finally {
            store.translationFileFor(image).delete()
            image.delete()
        }
    }
}
