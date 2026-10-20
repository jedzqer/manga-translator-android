package com.manga.translate

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.settings.CustomPromptStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CustomPromptStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = CustomPromptStore(context)
    private val asset = CustomPromptStore.assets.first()

    @Test fun `all built in languages and modes pass validation`() {
        for (language in listOf("zh-CN", "zh-TW", "en", "pt-BR", "ru")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            })
            for (mode in CustomPromptStore.assets) {
                val source = store.builtIn(localized, mode)
                CustomPromptStore.validate(source, source)
            }
        }
    }

    @Test fun `overrides isolate language and mode and reset restores built in`() {
        val other = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("ru")))
        })
        store.reset(context, asset)
        store.reset(other, asset)
        val original = store.builtIn(context, asset)
        val custom = JSONObject(original).put("system_prompt", "custom system").toString()
        store.save(context, asset, custom)
        assertEquals("custom system", JSONObject(store.read(context, asset)).getString("system_prompt"))
        assertEquals(store.builtIn(other, asset), store.read(other, asset))
        assertEquals(store.builtIn(context, CustomPromptStore.assets[1]), store.read(context, CustomPromptStore.assets[1]))
        assertTrue(store.fingerprint(context, asset).isNotBlank())
        store.reset(context, asset)
        assertEquals(original, store.read(context, asset))
        assertEquals("", store.fingerprint(context, asset))
    }

    @Test fun `invalid import does not overwrite saved prompt`() {
        val original = store.builtIn(context, asset)
        store.save(context, asset, original)
        val saved = store.read(context, asset)
        val invalid = listOf(
            "{}", original + "{}", "/* comment */" + original,
            "{\"system_prompt\":\"a\",\"system_prompt\":\"b\"}",
            JSONObject(original).put("system_prompt", 123).toString(),
            JSONObject(original).put("unexpected", "x").toString(),
            original.replace("{{STYLE_HINT}}", "removed"),
            JSONObject(original).put("example_messages", org.json.JSONArray().put(
                JSONObject().put("role", "tool").put("content", "x"))).toString(),
            " ".repeat(CustomPromptStore.MAX_BYTES + 1)
        )
        for (text in invalid) {
            assertTrue(runCatching { store.save(context, asset, text) }.isFailure)
            assertEquals(saved, store.read(context, asset))
        }
        store.reset(context, asset)
    }

    @Test fun `each OCR request path requires its image marker`() {
        val original = store.builtIn(context, "prompts/ocr_prompts.json")
        for (field in listOf("user_prompt_prefix", "batch_prompt")) {
            val invalid = JSONObject(original)
                .put(field, JSONObject(original).getString(field).replace("<image>", ""))
                .put("_说明", "<image>")
            assertTrue(runCatching { CustomPromptStore.validate(invalid.toString(), original) }.isFailure)
        }
    }

    @Test fun `documentation survives export but never changes saved instructions or fingerprint`() {
        val original = store.builtIn(context, asset)
        val exported = JSONObject(CustomPromptStore.validate(original, original, preserveDocumentation = true))
        assertEquals(JSONObject(original).getJSONObject("_说明").toString(), exported.getJSONObject("_说明").toString())
        store.save(context, asset, original)
        val saved = store.read(context, asset)
        val fingerprint = store.fingerprint(context, asset)
        store.save(context, asset, exported.put("_说明", "different documentation").toString())
        assertEquals(saved, store.read(context, asset))
        assertEquals(fingerprint, store.fingerprint(context, asset))
        assertFalse(JSONObject(saved).has("_说明"))
        val editor = JSONObject(store.readForEditing(context, asset))
        assertTrue(editor.has("_说明"))
        assertEquals(JSONObject(saved).getString("system_prompt"), editor.getString("system_prompt"))
        store.reset(context, asset)
    }

    @Test fun `legacy documents without documentation remain valid`() {
        for (mode in CustomPromptStore.assets) {
            val original = store.builtIn(context, mode)
            val legacy = JSONObject(original).apply { remove("_说明") }.toString()
            assertEquals(CustomPromptStore.validate(original, original), CustomPromptStore.validate(legacy, original))
        }
    }

    @Test fun `documentation cannot introduce placeholder requirements`() {
        val original = JSONObject(store.builtIn(context, "prompts/llm_prompts_abstract.json"))
            .put("_说明", "{{STYLE_HINT}} <image>").toString()
        CustomPromptStore.validate(original, original)
    }

    @Test fun `export documentation is stripped from model prompt`() {
        val original = store.builtIn(context, asset)
        val exported = JSONObject(original).put("_说明", "format guide").toString()
        val result = JSONObject(CustomPromptStore.validate(exported, original))
        assertFalse(result.has("_说明"))
        assertEquals(JSONObject(original).getString("system_prompt"), result.getString("system_prompt"))
    }
}
