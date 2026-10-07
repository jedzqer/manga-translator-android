package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.network.LlmBubbleTranslationItem
import com.manga.translate.network.LlmBubbleTranslationRequestItem
import com.manga.translate.network.LlmBubbleTranslationResult
import com.manga.translate.network.LlmGateway
import com.manga.translate.network.LlmResponseException
import com.manga.translate.settings.ApiSettings
import com.manga.translate.translation.TextBubbleTranslationCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TextBubbleTranslationCoordinatorTest {
    @Test
    fun `source echo is accepted as translation`() {
        val gateway = FakeLlmGateway(
            LlmBubbleTranslationResult(
                items = listOf(LlmBubbleTranslationItem(1, " 漫画\n原文 ")),
                glossaryUsed = emptyMap()
            )
        )
        val coordinator = TextBubbleTranslationCoordinator(gateway)

        val result = runBlocking {
            coordinator.translateBubbles(
                bubbles = listOf(pendingBubble(1, "漫画原文")),
                glossary = emptyMap(),
                promptAsset = "prompts/llm_prompts.json",
                logTag = "Test",
                translationMode = "standard"
            )
        }

        assertEquals("漫画\n原文", result?.bubbles?.single()?.translatedText)
    }

    @Test
    fun `missing response item raises model response error`() {
        val gateway = FakeLlmGateway(
            LlmBubbleTranslationResult(
                items = listOf(LlmBubbleTranslationItem(1, "译文一")),
                glossaryUsed = emptyMap()
            )
        )
        val coordinator = TextBubbleTranslationCoordinator(gateway)

        assertThrows(LlmResponseException::class.java) {
            runBlocking {
                coordinator.translateBubbles(
                    bubbles = listOf(
                        pendingBubble(1, "原文一"),
                        pendingBubble(2, "原文二")
                    ),
                    glossary = emptyMap(),
                    promptAsset = "prompts/llm_prompts.json",
                    logTag = "Test",
                    translationMode = "standard"
                )
            }
        }
    }

    @Test
    fun `blank response translation removes bubble without response error`() {
        val gateway = FakeLlmGateway(
            LlmBubbleTranslationResult(
                items = listOf(
                    LlmBubbleTranslationItem(1, "译文一"),
                    LlmBubbleTranslationItem(2, "")
                ),
                glossaryUsed = emptyMap()
            )
        )
        val coordinator = TextBubbleTranslationCoordinator(gateway)

        val result = runBlocking {
            coordinator.translateBubbles(
                bubbles = listOf(
                    pendingBubble(1, "原文一"),
                    pendingBubble(2, "无意义文本")
                ),
                glossary = emptyMap(),
                promptAsset = "prompts/llm_prompts.json",
                logTag = "Test",
                translationMode = "standard"
            )
        }

        assertEquals(listOf(1), result?.bubbles?.map { it.id })
        assertEquals(setOf(2), result?.removedBubbleIds)
    }

    @Test
    fun `request items are sent top to bottom`() {
        val gateway = CapturingLlmGateway(
            LlmBubbleTranslationResult(
                items = listOf(
                    LlmBubbleTranslationItem(0, "译文上"),
                    LlmBubbleTranslationItem(1, "译文中"),
                    LlmBubbleTranslationItem(2, "译文下")
                ),
                glossaryUsed = emptyMap()
            )
        )
        val coordinator = TextBubbleTranslationCoordinator(gateway)

        runBlocking {
            coordinator.translateBubbles(
                bubbles = listOf(
                    pendingBubble(2, "原文下", top = 200f),
                    pendingBubble(0, "原文上", top = 10f),
                    pendingBubble(1, "原文中", top = 100f)
                ),
                glossary = emptyMap(),
                promptAsset = "prompts/llm_prompts.json",
                logTag = "Test",
                translationMode = "standard"
            )
        }

        assertEquals(listOf(0, 1, 2), gateway.lastRequestIds)
        assertEquals(listOf("原文上", "原文中", "原文下"), gateway.lastRequestTexts)
    }

    private fun pendingBubble(id: Int, source: String, top: Float = 0f): BubbleTranslation {
        return BubbleTranslation.pending(id, RectF(0f, top, 10f, top + 10f), originalText = source)
    }
}

private class FakeLlmGateway(
    private val result: LlmBubbleTranslationResult
) : LlmGateway {
    override fun isConfigured(apiSettings: ApiSettings?): Boolean = true

    override fun isOcrConfigured(): Boolean = true

    override suspend fun translateBubbleItems(
        items: List<LlmBubbleTranslationRequestItem>,
        glossary: Map<String, String>,
        promptAsset: String,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?, glossaryProcessingEnabled: Boolean
    ): LlmBubbleTranslationResult = result

    override suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String> = emptyMap()

    override suspend fun recognizeImageText(
        image: Bitmap,
        language: TranslationLanguage
    ): String? = null

    override suspend fun translateImageBubble(
        imageBase64: String,
        promptAsset: String,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?
    ): com.manga.translate.network.LlmTranslationResult? = null

    override fun resourceContext(): Context = RuntimeEnvironment.getApplication()
}

private class CapturingLlmGateway(
    private val result: LlmBubbleTranslationResult
) : LlmGateway {
    var lastRequestIds: List<Int> = emptyList()
        private set
    var lastRequestTexts: List<String> = emptyList()
        private set

    override fun isConfigured(apiSettings: ApiSettings?): Boolean = true

    override fun isOcrConfigured(): Boolean = true

    override suspend fun translateBubbleItems(
        items: List<LlmBubbleTranslationRequestItem>,
        glossary: Map<String, String>,
        promptAsset: String,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?, glossaryProcessingEnabled: Boolean
    ): LlmBubbleTranslationResult {
        lastRequestIds = items.map { it.id }
        lastRequestTexts = items.map { it.text }
        return result
    }

    override suspend fun extractGlossary(
        text: String,
        glossary: Map<String, String>,
        promptAsset: String
    ): Map<String, String> = emptyMap()

    override suspend fun recognizeImageText(
        image: Bitmap,
        language: TranslationLanguage
    ): String? = null

    override suspend fun translateImageBubble(
        imageBase64: String,
        promptAsset: String,
        glossary: Map<String, String>,
        glossaryProcessingEnabled: Boolean,
        requestTimeoutMs: Int?,
        retryCount: Int,
        apiSettings: ApiSettings?
    ): com.manga.translate.network.LlmTranslationResult? = null

    override fun resourceContext(): Context = RuntimeEnvironment.getApplication()
}
