package com.manga.translate.settings

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.manga.translate.platform.PromptAssetResolver
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.security.MessageDigest

/** Overrides are isolated by the resolved language and asset; bundled assets are never changed. */
class CustomPromptStore(context: Context) {
    private val prefs = context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    internal data class PromptSource(val text: String, val isBuiltIn: Boolean)

    fun builtIn(context: Context, asset: String): String =
        context.assets.open(PromptAssetResolver.resolve(context, asset)).bufferedReader().use { it.readText() }

    private fun key(context: Context, asset: String) =
        "custom_prompt:" + PromptAssetResolver.translationTargetKey(context) + ":" + PromptAssetResolver.resolve(context, asset)

    fun read(context: Context, asset: String): String = readSource(context, asset).text

    internal fun readSource(context: Context, asset: String): PromptSource {
        val override = prefs.getString(key(context, asset), null)
        return if (override == null) PromptSource(builtIn(context, asset), isBuiltIn = true)
        else PromptSource(override, isBuiltIn = false)
    }

    /** Old overrides keep their instructions and display the current template documentation. */
    fun readForEditing(context: Context, asset: String): String {
        val source = read(context, asset)
        val template = JSONObject(builtIn(context, asset))
        return JSONObject().put("_说明", template.get("_说明")).apply {
            val prompt = JSONObject(source)
            for (field in prompt.keys()) {
                if (field != "_说明") put(field, prompt.get(field))
            }
        }.toString(2)
    }

    fun fingerprint(context: Context, asset: String): String {
        val custom = prefs.getString(key(context, asset), null) ?: return ""
        return MessageDigest.getInstance("SHA-256").digest(custom.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun save(context: Context, asset: String, text: String) {
        val normalized = validate(text, builtIn(context, asset))
        check(prefs.edit().putString(key(context, asset), normalized).commit())
    }

    /** Validate the entire package before committing any language-scoped overrides. */
    fun importPack(context: Context, input: java.io.InputStream): Map<String, String> {
        val documents = linkedMapOf<String, String>()
        java.util.zip.ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val asset = assets.singleOrNull { it.substringAfterLast('/') == entry.name }
                    ?: error("Unexpected prompt entry")
                require(!entry.isDirectory && asset !in documents)
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                val text = Charsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
                documents[asset] = validate(text, builtIn(context, asset), preserveDocumentation = true)
                zip.closeEntry()
            }
        }
        require(documents.keys == assets.toSet())
        val normalized = documents.mapValues { (asset, text) -> validate(text, builtIn(context, asset)) }
        val editor = prefs.edit()
        normalized.forEach { (asset, text) -> editor.putString(key(context, asset), text) }
        check(editor.commit())
        return documents
    }

    /** Snapshot all modes, including editor drafts, before opening the document picker. */
    fun exportPack(context: Context, drafts: Map<String, String> = emptyMap()): ByteArray {
        val documents = assets.associateWith { asset ->
            validate(drafts[asset] ?: readForEditing(context, asset), builtIn(context, asset),
                preserveDocumentation = true)
        }
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            documents.forEach { (asset, text) ->
                zip.putNextEntry(java.util.zip.ZipEntry(asset.substringAfterLast('/')))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun reset(context: Context, asset: String) {
        check(prefs.edit().remove(key(context, asset)).commit())
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        val assets = listOf("llm_prompts", "llm_prompts_FullTrans", "llm_prompts_abstract",
            "float_llm_prompts", "vl_page_prompts", "vl_bubble_prompts", "ocr_prompts")
            .map { "prompts/$it.json" }

        /** JsonReader rejects JSON extensions; duplicate keys and trailing input are rejected too. */
        fun validate(text: String, template: String, preserveDocumentation: Boolean = false): String {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val json = JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val result = readObject(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT)
                result
            }
            val expected = JSONObject(template).apply { remove("_说明") }
            val allowed = expected.keys().asSequence().toSet() + "_说明"
            require(json.keys().asSequence().all { it in allowed })
            for (field in expected.keys()) {
                when (expected.get(field)) {
                    is String -> require(json.opt(field) is String && json.getString(field).isNotBlank())
                    is JSONArray -> {
                        val messages = json.getJSONArray(field)
                        require(messages.length() <= 100)
                        for (i in 0 until messages.length()) {
                            val message = messages.getJSONObject(i)
                            require(message.keys().asSequence().toSet() == setOf("role", "content"))
                            require(message.opt("role") in setOf("system", "user", "assistant"))
                            require(message.opt("content") is String && message.getString("content").isNotBlank())
                        }
                    }
                }
            }
            // Style is expanded in example messages by PayloadBuilder.
            if (expected.optJSONArray("example_messages")?.toString()?.contains("{{STYLE_HINT}}") == true) {
                require(json.getJSONArray("example_messages").toString().contains("{{STYLE_HINT}}"))
            }
            // Each image request path must retain its own marker; documentation and
            // unrelated fields cannot satisfy another path's requirement.
            for (field in expected.keys()) {
                val value = expected.opt(field)
                if (value is String && value.contains("<image>")) {
                    require(json.getString(field).contains("<image>"))
                }
            }
            if (!preserveDocumentation) json.remove("_说明")
            return json.toString(2)
        }

        private fun readObject(reader: JsonReader, depth: Int): JSONObject {
            require(depth < 12)
            val result = JSONObject()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(!result.has(name))
                result.put(name, readValue(reader, depth + 1))
            }
            reader.endObject()
            return result
        }

        private fun readValue(reader: JsonReader, depth: Int): Any = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(reader, depth)
            JsonToken.BEGIN_ARRAY -> {
                require(depth < 12)
                val array = JSONArray()
                reader.beginArray()
                while (reader.hasNext()) array.put(readValue(reader, depth + 1))
                reader.endArray()
                array
            }
            JsonToken.STRING -> reader.nextString()
            else -> error("Expected string, object or array")
        }
    }
}
