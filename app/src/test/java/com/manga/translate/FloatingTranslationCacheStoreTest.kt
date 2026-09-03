package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.storage.FloatingCacheScope
import com.manga.translate.storage.FloatingTranslationCacheStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingTranslationCacheStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val baseScope = FloatingCacheScope(
        language = TranslationLanguage.JA_TO_ZH,
        providerId = "primary",
        modelName = "model-a",
        promptAsset = "float_llm_prompts.json"
    )

    @Before
    fun setUp() {
        File(context.cacheDir, "floating_translate_cache.json").delete()
    }

    @Test
    fun `exact lookup returns translation for the same scope`() {
        val store = FloatingTranslationCacheStore(context)
        store.putTextTranslation("こんにちは", "你好", baseScope)

        val found = store.findTextTranslation("こんにちは", baseScope)

        assertNotNull(found)
        assertEquals("你好", found?.translation)
        assertEquals(false, found?.matchedBySimilarity)
    }

    @Test
    fun `lookup misses when the model changed`() {
        val store = FloatingTranslationCacheStore(context)
        store.putTextTranslation("こんにちは", "你好", baseScope)

        val found = store.findTextTranslation(
            "こんにちは",
            baseScope.copy(modelName = "model-b")
        )

        assertNull(found)
    }

    @Test
    fun `lookup misses when the provider changed`() {
        val store = FloatingTranslationCacheStore(context)
        store.putTextTranslation("こんにちは", "你好", baseScope)

        val found = store.findTextTranslation(
            "こんにちは",
            baseScope.copy(providerId = "secondary")
        )

        assertNull(found)
    }

    @Test
    fun `lookup misses when the prompt changed`() {
        val store = FloatingTranslationCacheStore(context)
        store.putTextTranslation("こんにちは", "你好", baseScope)

        val found = store.findTextTranslation(
            "こんにちは",
            baseScope.copy(promptAsset = "vl_bubble_prompts.json")
        )

        assertNull(found)
    }

    @Test
    fun `similarity matching does not cross scope boundaries`() {
        val store = FloatingTranslationCacheStore(context)
        // Long enough to be eligible for similarity matching.
        store.putTextTranslation("今日はとてもいい天気ですね", "今天天气真好呢", baseScope)

        val sameScope = store.findTextTranslation("今日はとてもいい天気ですね!", baseScope)
        assertNotNull(sameScope)
        assertTrue(sameScope!!.matchedBySimilarity)

        val otherModel = store.findTextTranslation(
            "今日はとてもいい天気ですね!",
            baseScope.copy(modelName = "model-b")
        )
        assertNull(otherModel)
    }

    @Test
    fun `image lookup is scoped to the model`() {
        val store = FloatingTranslationCacheStore(context)
        store.putImageTranslation("image-key", "你好", baseScope)

        assertEquals("你好", store.findImageTranslation("image-key", baseScope))
        assertNull(
            store.findImageTranslation("image-key", baseScope.copy(modelName = "model-b"))
        )
    }

    @Test
    fun `legacy cache files are discarded instead of reused across scopes`() {
        val legacy = """
            {
              "version": 2,
              "text_entries": [
                {
                  "key": "zh|JA_TO_ZH|こんにちは",
                  "translation": "旧译文",
                  "normalized": "こんにちは",
                  "updated_at": 1
                }
              ],
              "image_entries": []
            }
        """.trimIndent()
        File(context.cacheDir, "floating_translate_cache.json").writeText(legacy)

        val store = FloatingTranslationCacheStore(context)

        assertNull(store.findTextTranslation("こんにちは", baseScope))
    }
}
