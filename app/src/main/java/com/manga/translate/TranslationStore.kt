package com.manga.translate

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TranslationStore {
    fun load(imageFile: File): TranslationResult? {
        val jsonFile = translationFileFor(imageFile)
        if (!jsonFile.exists()) return null
        return try {
            val json = JSONObject(jsonFile.readText())
            val bubblesJson = json.optJSONArray("bubbles") ?: JSONArray()
            val bubbles = ArrayList<BubbleTranslation>(bubblesJson.length())
            for (i in 0 until bubblesJson.length()) {
                val item = bubblesJson.optJSONObject(i) ?: continue
                val id = if (item.has("id")) item.optInt("id") else i
                val rect = RectF(
                    item.optDouble("left").toFloat(),
                    item.optDouble("top").toFloat(),
                    item.optDouble("right").toFloat(),
                    item.optDouble("bottom").toFloat()
                )
                val text = item.optString("text", "")
                bubbles.add(BubbleTranslation(id, rect, text))
            }
            TranslationResult(
                imageName = json.optString("image", imageFile.name),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                bubbles = bubbles
            )
        } catch (e: Exception) {
            AppLogger.log("TranslationStore", "Failed to load ${jsonFile.name}", e)
            null
        }
    }

    fun save(imageFile: File, result: TranslationResult): File {
        val jsonFile = translationFileFor(imageFile)
        val json = JSONObject()
            .put("image", result.imageName)
            .put("width", result.width)
            .put("height", result.height)
        val bubbles = JSONArray()
        for (bubble in result.bubbles) {
            val item = JSONObject()
                .put("id", bubble.id)
                .put("left", bubble.rect.left)
                .put("top", bubble.rect.top)
                .put("right", bubble.rect.right)
                .put("bottom", bubble.rect.bottom)
                .put("text", bubble.text)
            bubbles.put(item)
        }
        json.put("bubbles", bubbles)
        jsonFile.writeText(json.toString())
        return jsonFile
    }

    fun translationFileFor(imageFile: File): File {
        val name = imageFile.nameWithoutExtension + ".json"
        return File(imageFile.parentFile, name)
    }
}
