package com.manga.translate

import android.graphics.RectF
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.OcrMetadata
import com.manga.translate.model.PageOcrResult
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.storage.OcrStore
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrStoreTest {
    @Test
    fun `out of bounds legacy merged cache is ignored when metadata is expected`() {
        val image = File.createTempFile("ocr-store", ".jpg")
        image.writeText("source")
        val store = OcrStore()
        val metadata = OcrMetadata(
            sourceLastModified = image.lastModified(),
            sourceFileSize = image.length(),
            cacheMode = "local|strategy|bubbles_and_text",
            language = TranslationLanguage.JA_TO_ZH.name,
            engineModel = "local:local|strategy|bubbles_and_text"
        )
        try {
            store.save(
                image,
                PageOcrResult(
                    imageFile = image,
                    width = 100,
                    height = 100,
                    bubbles = listOf(
                        OcrBubble(
                            id = 0,
                            rect = RectF(0f, 0f, 10f, 120f),
                            text = "spill",
                            source = BubbleSource.BUBBLE_DETECTOR
                        )
                    ),
                    cacheMode = metadata.cacheMode,
                    metadata = metadata
                )
            )
            assertNull(store.load(image, expectedMetadata = metadata))
        } finally {
            store.ocrFileFor(image).delete()
            image.delete()
        }
    }
}
