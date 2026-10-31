package com.manga.translate

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.model.ApiFormat
import com.manga.translate.settings.*
import com.manga.translate.storage.AppBackupManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelContextSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
    private val profileFile get() = context.filesDir.resolve(SettingsStore.AI_PROVIDER_PROFILES_FILE_NAME)
    private val api = ApiSettings("https://example.test/v1", "key", "model")
    private val limits = ModelContextLimits(100000, 20000, 110000)
    private lateinit var store: SettingsStore

    @Before fun setUp() {
        prefs.edit().clear().commit()
        listOf("", ".bak", ".new").forEach { context.filesDir.resolve(profileFile.name + it).delete() }
        store = SettingsStore(context)
        store.save(api)
    }

    private fun save(value: ModelContextLimits = limits) {
        assertTrue(store.saveModelContextSettings(store.loadModelContextSettings().copy(limits = value)))
    }

    @Test fun `save reload and exact deployment isolation preserve independent maxima`() {
        assertTrue(store.loadModelContextSettings().limits.hasUnknownLimits)
        save()
        assertEquals(limits, SettingsStore(context).loadModelContextSettings().limits)
        listOf(api.copy(modelName = "other"), api.copy(apiUrl = "https://other.test/v1"),
            api.copy(apiFormat = ApiFormat.OPENAI_RESPONSES)).forEach {
            store.save(it)
            assertEquals(ModelContextLimits(), store.loadModelContextSettings().limits)
            save(ModelContextLimits(200))
        }
        store.save(api.copy(apiKey = "new-key"))
        assertEquals(limits, store.loadModelContextSettings().limits)
        assertEquals(EffectiveModelContextLimits(100000, 20000, 110000),
            store.loadModelContextSettings().effectiveLimits)
    }

    @Test fun `missing and partially configured limits resolve defaults without persisting them`() {
        assertEquals(EffectiveModelContextLimits(262144, 65536, 262144), store.loadModelContextSettings().effectiveLimits)
        assertFalse(prefs.contains(LlmParameterStore.MODEL_CONTEXT_KEY))
        save(ModelContextLimits(maxOutputTokens = 8192))
        val reloaded = SettingsStore(context).loadModelContextSettings()
        assertEquals(EffectiveModelContextLimits(262144, 8192, 262144), reloaded.effectiveLimits)
        assertNull(reloaded.limits.maxInputTokens)
        save(ModelContextLimits(2048, 512, 4096))
        assertEquals(EffectiveModelContextLimits(2048, 512, 4096), store.loadModelContextSettings().effectiveLimits)
        save(ModelContextLimits())
        assertEquals(EffectiveModelContextLimits(262144, 65536, 262144), store.loadModelContextSettings().effectiveLimits)
    }

    @Test fun `profiles isolate matching models and reject stale snapshots`() {
        assertTrue(store.saveCurrentAsAiProviderProfile("A"))
        save()
        val snapshot = store.loadModelContextSettings()
        assertTrue(store.saveCurrentAsAiProviderProfile("B"))
        save(ModelContextLimits(300))
        assertFalse(store.saveModelContextSettings(snapshot))
        assertTrue(store.applyAiProviderProfile("A"))
        assertEquals(limits, store.loadModelContextSettings().limits)
        assertTrue(store.applyAiProviderProfile("B"))
        assertEquals(ModelContextLimits(300), store.loadModelContextSettings().limits)
        val stale = store.loadModelContextSettings()
        store.save(api.copy(modelName = "new"))
        assertFalse(store.saveModelContextSettings(stale))
    }

    @Test fun `legacy profiles clear new capabilities instead of inheriting them`() {
        assertTrue(store.saveCurrentAsAiProviderProfile("legacy"))
        val root = JSONObject(profileFile.readText())
        root.getJSONArray("profiles").getJSONObject(0).remove("modelContextEntries")
        profileFile.writeText(root.toString())
        prefs.edit().putString(LlmParameterStore.MODEL_CONTEXT_KEY,
            ModelContextCodec.encode(listOf(ModelContextEntry(ModelContextIdentity.from(api), limits))).toString()).commit()
        assertTrue(store.applyAiProviderProfile("legacy"))
        assertEquals(ModelContextLimits(), store.loadModelContextSettings().limits)
    }

    @Test fun `invalid imported limits and ambiguous identities remain unknown`() {
        val entry = ModelContextCodec.encode(listOf(ModelContextEntry(ModelContextIdentity.from(api), limits))).getJSONObject(0)
        listOf(0, -1, 1.5, "123", 2147483648L, JSONObject.NULL).forEach { invalid ->
            entry.put("maxInputTokens", invalid)
            assertNull(ModelContextCodec.decode(JSONArray().put(entry)).single().limits.maxInputTokens)
        }
        assertTrue(ModelContextCodec.decode(JSONArray().put(entry).put(entry)).isEmpty())
        prefs.edit().putString(LlmParameterStore.MODEL_CONTEXT_KEY, "bad json").commit()
        assertEquals(ModelContextLimits(), store.loadModelContextSettings().limits)
        assertThrows(IllegalArgumentException::class.java) { ModelContextLimits(maxOutputTokens = 0) }
    }

    @Test fun `profile write failure preserves saved capabilities and current preferences`() {
        assertTrue(store.saveCurrentAsAiProviderProfile("A"))
        save()
        val before = profileFile.readText()
        val storage = SettingsStoreStorage(context)
        val failing = ProviderProfileStore(storage, ApiSettingsStore(storage), OcrSettingsStore(storage),
            LlmParameterStore(storage), AiProviderProfilesFileWriter { _, _ -> false })
        assertFalse(failing.saveModelContextSettings(store.loadModelContextSettings().copy(limits = ModelContextLimits())))
        assertEquals(before, profileFile.readText())
        assertEquals(limits, store.loadModelContextSettings().limits)
    }

    @Test fun `software backup roundtrip restores profile and current capabilities`() = runBlocking {
        assertTrue(store.saveCurrentAsAiProviderProfile("A"))
        save()
        val manager = AppBackupManager(context)
        val archive = context.cacheDir.resolve("model-context-backup.zip")
        manager.exportTo(Uri.fromFile(archive), AppBackupManager.ExportOptions(false, true))
        save(ModelContextLimits())
        manager.importFrom(Uri.fromFile(archive))
        assertEquals(limits, SettingsStore(context).loadModelContextSettings().limits)
        assertTrue(store.applyAiProviderProfile("A"))
        assertEquals(limits, store.loadModelContextSettings().limits)
    }
}
