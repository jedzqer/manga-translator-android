package com.manga.translate

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.manga.translate.library.LibraryPreferencesGateway
import com.manga.translate.library.LibraryRepository
import com.manga.translate.model.ApiFormat
import com.manga.translate.network.PayloadBuilder
import com.manga.translate.settings.ApiSettings
import com.manga.translate.settings.SettingsStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class FolderTranslationStyleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val repository = LibraryRepository(context)
    private val prefs = context.getSharedPreferences("folder_style_test", Context.MODE_PRIVATE)
    private val gateway = LibraryPreferencesGateway(context, prefs, repository)

    @Test
    fun `text orientation follows collection and survives preference migration`() {
        val collection = requireNotNull(repository.createCollection("layout_${System.nanoTime()}"))
        val chapter = requireNotNull(repository.createChildFolder(collection, "chapter"))
        val target = requireNotNull(repository.createCollection("layout_target_${System.nanoTime()}"))
        try {
            val original = gateway.getUseHorizontalText(collection)
            gateway.setUseHorizontalText(collection, !original)
            assertEquals(!original, gateway.getUseHorizontalText(chapter))
            gateway.migrateFolderSettings(collection, target)
            assertEquals(!original, gateway.getUseHorizontalText(target))
            gateway.clearFolderSettings(target)
            assertEquals(original, gateway.getUseHorizontalText(target))
        } finally {
            collection.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w320dp-h480dp-night")
    fun `narrow dark dialog preserves draft on cancel and saves local or global selection`() {
        checkDialog()
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "w320dp-h480dp-notnight")
    fun `narrow light dialog supports long text and explicit empty style`() {
        checkDialog()
    }

    private fun checkDialog() {
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_MangaTranslator)
        controller.setup()
        val folder = requireNotNull(repository.createFolder("dialog_${System.nanoTime()}"))
        val longStyle = "Long style 文风描述 ".repeat(200)
        fun show() = com.manga.translate.library.showFolderTranslationStyleDialog(activity, folder, gateway, longStyle)
        try {
            var dialog = show()
            var toggle = dialog.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.follow_global_style)!!
            var input = dialog.findViewById<android.widget.EditText>(R.id.folder_style_input)!!
            assertTrue(toggle.isChecked)
            assertFalse(input.isEnabled)
            assertEquals(longStyle, input.text.toString())
            toggle.isChecked = false
            assertTrue(input.isEnabled)
            input.setText("draft")
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(gateway.getTranslationStyle(folder))
            dialog = show()
            toggle = dialog.findViewById(R.id.follow_global_style)!!
            input = dialog.findViewById(R.id.folder_style_input)!!
            toggle.isChecked = false
            input.setText("")
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("", gateway.getTranslationStyle(folder))
            dialog = show()
            toggle = dialog.findViewById(R.id.follow_global_style)!!
            assertFalse(toggle.isChecked)
            toggle.isChecked = true
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(gateway.getTranslationStyle(folder))
        } finally {
            gateway.clearFolderSettings(folder)
            folder.deleteRecursively()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `collection style inherits global dynamically and supports explicit empty override`() {
        val collection = requireNotNull(repository.createCollection("styles_${System.nanoTime()}"))
        val chapter = requireNotNull(repository.createChildFolder(collection, "chapter"))
        try {
            assertNull(gateway.getTranslationStyle(chapter))
            assertEquals("global", gateway.resolveTranslationStyle(chapter, "global"))
            gateway.setTranslationStyle(collection, " local ")
            assertEquals("local", gateway.resolveTranslationStyle(chapter, "changed global"))
            gateway.setTranslationStyle(collection, "")
            assertEquals("", gateway.resolveTranslationStyle(chapter, "global"))
            gateway.setTranslationStyle(collection, null)
            assertEquals("changed global", gateway.resolveTranslationStyle(chapter, "changed global"))
        } finally {
            gateway.clearFolderSettings(collection)
            collection.deleteRecursively()
        }
    }

    @Test
    fun `folder style migrates and clears with folder preferences`() {
        val from = requireNotNull(repository.createFolder("style_from_${System.nanoTime()}"))
        val to = requireNotNull(repository.createFolder("style_to_${System.nanoTime()}"))
        try {
            gateway.setTranslationStyle(from, "local")
            gateway.migrateFolderSettings(from, to)
            assertNull(gateway.getTranslationStyle(from))
            assertEquals("local", gateway.getTranslationStyle(to))
            gateway.clearFolderSettings(to)
            assertNull(gateway.getTranslationStyle(to))
        } finally {
            from.deleteRecursively()
            to.deleteRecursively()
        }
    }

    @Test
    fun `text and image requests isolate local style across all protocols`() {
        val store = SettingsStore(context)
        store.saveTranslationStyle("GLOBAL_STYLE_SENTINEL")
        val builder = PayloadBuilder(context, store)
        for (format in ApiFormat.entries) {
            val settings = ApiSettings("https://example.com", "key", "model", format)
            for (style in listOf("LOCAL_STYLE_SENTINEL", "", null, "OTHER_STYLE_SENTINEL")) {
                val request = settings.copy(translationStyle = style)
                val text = builder.buildPayload(request, "model", "prompts/llm_prompts.json", format, "hello").toString()
                val image = builder.buildImageTranslationPayload(request, "model", "image", "prompts/vl_bubble_prompts.json", format).toString()
                for (payload in listOf(text, image)) {
                    assertEquals(style == null, payload.contains("GLOBAL_STYLE_SENTINEL"))
                    assertEquals(style == "LOCAL_STYLE_SENTINEL", payload.contains("LOCAL_STYLE_SENTINEL"))
                    assertEquals(style == "OTHER_STYLE_SENTINEL", payload.contains("OTHER_STYLE_SENTINEL"))
                }
            }
        }
        assertEquals("GLOBAL_STYLE_SENTINEL", store.loadTranslationStyle())
    }

    @Test
    fun `translation requests preserve the example messages shown in each bundled template`() {
        val textModes = listOf("llm_prompts", "llm_prompts_FullTrans", "float_llm_prompts")
        val imageModes = listOf("vl_bubble_prompts", "vl_page_prompts")
        val locales = listOf("" to "zh-CN", "_en" to "en", "_hant" to "zh-TW",
            "_pt_br" to "pt-BR", "_ru" to "ru")
        val input = """{"items":[{"id":0,"text":"PAGE_INPUT_SENTINEL"}]}"""
        for (mode in textModes + imageModes) for ((locale, language) in locales) {
            val localizedContext = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(language)))
                })
            val builder = PayloadBuilder(localizedContext, SettingsStore(localizedContext))
            val asset = "prompts/$mode$locale.json"
            val template = JSONObject(localizedContext.assets.open(asset).bufferedReader().use { it.readText() })
            val examples = template.getJSONArray("example_messages")
            assertEquals(asset, "user", examples.getJSONObject(0).getString("role"))
            assertEquals(asset, "assistant", examples.getJSONObject(1).getString("role"))
            assertTrue(asset, examples.getJSONObject(1).getString("content").contains("{{STYLE_HINT}}"))
            for (format in ApiFormat.entries) for (style in listOf("", "STYLE \"quoted\"\n中文")) {
                val settings = ApiSettings("https://example.com", "key", "model", format, translationStyle = style)
                val payload = if (mode in textModes) {
                    builder.buildPayload(settings, "model", asset, format, input)
                } else {
                    builder.buildImageTranslationPayload(settings, "model", "image", asset, format,
                        requestedIds = if (mode == "vl_page_prompts") listOf(0) else null)
                }
                val (messages, offset) = when (format) {
                    ApiFormat.OPENAI_COMPATIBLE -> payload.getJSONArray("messages") to 1
                    ApiFormat.OPENAI_RESPONSES -> payload.getJSONArray("input") to 0
                    ApiFormat.GEMINI -> payload.getJSONArray("contents") to 0
                }
                val label = "$asset / $format"
                assertEquals(label, offset + examples.length() + 1, messages.length())
                for (index in 0 until examples.length()) {
                    val expected = examples.getJSONObject(index)
                    val message = messages.getJSONObject(offset + index)
                    val role = expected.getString("role")
                    assertEquals(label, if (format == ApiFormat.GEMINI && role == "assistant") "model" else role,
                        message.getString("role"))
                    val content = if (format == ApiFormat.GEMINI) {
                        message.getJSONArray("parts").getJSONObject(0).getString("text")
                    } else message.getString("content")
                    assertEquals(label, expected.getString("content").replace("{{STYLE_HINT}}", style), content)
                }
                val finalMessage = messages.getJSONObject(messages.length() - 1)
                val finalText = when {
                    format == ApiFormat.GEMINI -> finalMessage.getJSONArray("parts").getJSONObject(0).getString("text")
                    mode in imageModes -> finalMessage.getJSONArray("content").getJSONObject(0).getString("text")
                    else -> finalMessage.getString("content")
                }
                assertEquals(label, "user", finalMessage.getString("role"))
                assertTrue(label, finalText.startsWith(template.getString("user_prompt_prefix")))
                if (mode in textModes) assertTrue(label, finalText.endsWith(input))
                if (style.isNotEmpty()) assertFalse(label, finalText.contains(style))
            }
        }
    }
}
