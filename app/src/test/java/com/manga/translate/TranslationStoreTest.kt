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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationStoreTest {
    @Test
    fun `complete metadata does not reuse translation across modes or OCR settings`() {
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
            assertNotNull(store.load(image, expectedMetadata = metadata))
            assertNull(
                store.load(
                    image,
                    expectedMetadata = metadata.copy(mode = TranslationMetadata.MODE_FULL_PAGE)
                )
            )
            assertNull(
                store.load(
                    image,
                    expectedMetadata = metadata.copy(ocrCacheMode = "local|text")
                )
            )
            assertNull(
                store.load(
                    image,
                    expectedMetadata = metadata.copy(promptAsset = "prompts/llm_prompts_FullTrans.json")
                )
            )
            assertNull(
                store.load(
                    image,
                    expectedMetadata = metadata.copy(language = TranslationLanguage.EN_TO_ZH.name)
                )
            )
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
                    store.load(image, expectedMetadata = metadata.copy(apiFormat = apiFormat))
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
