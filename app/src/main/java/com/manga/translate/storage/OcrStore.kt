package com.manga.translate.storage

import android.graphics.RectF
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrMetadata
import com.manga.translate.platform.AppLogger
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class OcrStore {
    fun load(imageFile: File, expectedMetadata: OcrMetadata? = null): PageOcrResult? {
        val jsonFile = ocrFileFor(imageFile)
        if (!jsonFile.exists()) return null
        return try {
            val json = JSONObject(jsonFile.readText())
            val metadata = parseMetadata(json.optJSONObject("metadata"))
            if (expectedMetadata != null && !metadata.matchesSource(imageFile)) {
                return null
            }
            if (expectedMetadata != null && !metadata.matches(expectedMetadata)) {
                return null
            }
            val bubblesJson = json.optJSONArray("bubbles") ?: JSONArray()
            val pageWidth = json.optInt("width", 0)
            val pageHeight = json.optInt("height", 0)
            val bubbles = ArrayList<OcrBubble>(bubblesJson.length())
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
                val source = BubbleSource.fromJson(if (item.has("source")) item.optString("source") else null)
                val maskContourJson = item.optJSONArray("maskContour")
                val maskContour = if (maskContourJson != null && maskContourJson.length() >= 6) {
                    FloatArray(maskContourJson.length()) { i -> maskContourJson.optDouble(i).toFloat() }
                } else null
                bubbles.add(OcrBubble(id, rect, text, source, maskContour))
            }
            // Cross-page merging in older versions could persist a bubble whose
            // rectangle spills outside its source page. Such a cache is not safe
            // to reuse for a normal page, so force a fresh OCR pass instead of
            // returning mixed page coordinates.
            if (expectedMetadata != null && pageWidth > 0 && pageHeight > 0 && bubbles.any {
                    it.rect.left < 0f || it.rect.top < 0f ||
                        it.rect.right > pageWidth || it.rect.bottom > pageHeight
                }) {
                AppLogger.log("OcrStore", "Ignoring out-of-bounds OCR cache for ${imageFile.name}")
                return null
            }
            PageOcrResult(
                imageFile = imageFile,
                width = pageWidth,
                height = pageHeight,
                bubbles = bubbles,
                cacheMode = metadata.cacheMode,
                metadata = metadata
            )
        } catch (e: Exception) {
            AppLogger.log("OcrStore", "Failed to load ${jsonFile.name}", e)
            null
        }
    }

    fun save(imageFile: File, result: PageOcrResult): File {
        val jsonFile = ocrFileFor(imageFile)
        val metadata = result.metadata.copy(
            sourceLastModified = imageFile.lastModified(),
            sourceFileSize = imageFile.length(),
            cacheMode = result.cacheMode.ifBlank { result.metadata.cacheMode }
        )
        val json = JSONObject()
            .put("image", result.imageFile.name)
            .put("width", result.width)
            .put("height", result.height)
            .put("metadata", JSONObject().apply {
                put("sourceLastModified", metadata.sourceLastModified)
                put("sourceFileSize", metadata.sourceFileSize)
                put("cacheMode", metadata.cacheMode)
                put("language", metadata.language)
                put("engineModel", metadata.engineModel)
                put("version", metadata.version)
            })
        val bubbles = JSONArray()
        for (bubble in result.bubbles) {
            val item = JSONObject()
                .put("id", bubble.id)
                .put("left", bubble.rect.left)
                .put("top", bubble.rect.top)
                .put("right", bubble.rect.right)
                .put("bottom", bubble.rect.bottom)
                .put("text", bubble.text)
                .put("source", bubble.source.jsonValue)
            if (bubble.maskContour != null) {
                val contourArr = JSONArray()
                for (v in bubble.maskContour) contourArr.put(v.toDouble())
                item.put("maskContour", contourArr)
            }
            bubbles.put(item)
        }
        json.put("bubbles", bubbles)
        writeFileAtomically(jsonFile, json.toString())
        return jsonFile
    }

    fun ocrFileFor(imageFile: File): File {
        val name = imageFile.nameWithoutExtension + ".ocr.json"
        return File(imageFile.parentFile, name)
    }

    private fun parseMetadata(json: JSONObject?): OcrMetadata {
        return OcrMetadata(
            sourceLastModified = json?.optLong("sourceLastModified") ?: 0L,
            sourceFileSize = json?.optLong("sourceFileSize") ?: 0L,
            cacheMode = json?.optString("cacheMode").orEmpty(),
            language = json?.optString("language").orEmpty(),
            engineModel = json?.optString("engineModel").orEmpty(),
            version = json?.let { it.optInt("version", OcrMetadata.CURRENT_VERSION) }
                ?: OcrMetadata.CURRENT_VERSION
        )
    }
}
