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
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class CustomPromptPackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = CustomPromptStore(context)
    private val assets = CustomPromptStore.assets
    private fun localized(tag: String) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag(tag)))
    })
    private fun pack(documents: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> documents.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray()
    }

    @Test fun roundTripIncludesAllModesAndDraftsAndIsolatesLanguage() {
        val english = localized("en")
        val russian = localized("ru")
        val drafts = assets.associateWith { JSONObject(store.builtIn(english, it))
            .put("system_prompt", "Draft for $it").toString() }
        val before = assets.associateWith { store.read(russian, it) }
        val bytes = store.exportPack(english, drafts)
        val names = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name; zip.closeEntry() }
        }
        assertEquals(assets.map { it.substringAfterLast('/') }, names)
        val imported = store.importPack(english, bytes.inputStream())
        assets.forEach {
            assertEquals("Draft for $it", JSONObject(store.read(english, it)).getString("system_prompt"))
            assertTrue(JSONObject(imported.getValue(it)).has("_说明"))
            assertFalse(JSONObject(store.read(english, it)).has("_说明"))
            assertEquals(before[it], store.read(russian, it))
        }
    }

    @Test fun invalidPacksLeaveEveryModeUnchanged() {
        val ctx = localized("en")
        val original = assets.associateWith { store.read(ctx, it) }
        val valid = assets.associate { it.substringAfterLast('/') to store.builtIn(ctx, it).toByteArray() }
        val last = assets.last().substringAfterLast('/')
        val invalid = listOf(
            pack(valid - last),
            pack(valid + (last to "{}".toByteArray())),
            pack(valid + (last to ByteArray(CustomPromptStore.MAX_BYTES + 1) { 32 })),
            pack(valid + (last to byteArrayOf(0xC3.toByte(), 0x28))),
            pack(valid + ("../unexpected.json" to "{}".toByteArray())),
            byteArrayOf(1, 2, 3)
        )
        invalid.forEach { bytes ->
            assertTrue(runCatching { store.importPack(ctx, bytes.inputStream()) }.isFailure)
            assets.forEach { assertEquals(original[it], store.read(ctx, it)) }
        }
    }

    @Test fun exportUsesSavedOverridesAndBuiltInsAndRejectsInvalidDrafts() {
        val ctx = localized("pt-BR")
        val first = assets.first()
        store.save(ctx, first, JSONObject(store.builtIn(ctx, first)).put("system_prompt", "Saved").toString())
        val documents = store.importPack(ctx, store.exportPack(ctx).inputStream())
        assertEquals("Saved", JSONObject(documents.getValue(first)).getString("system_prompt"))
        assets.drop(1).forEach {
            assertEquals(CustomPromptStore.validate(store.builtIn(ctx, it), store.builtIn(ctx, it)), store.read(ctx, it))
        }
        assertTrue(runCatching { store.exportPack(ctx, mapOf(first to "{}")) }.isFailure)
    }
}
