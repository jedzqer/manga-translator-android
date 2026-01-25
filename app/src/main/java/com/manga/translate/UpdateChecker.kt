package com.manga.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val history: List<UpdateHistoryEntry>
)

data class UpdateHistoryEntry(
    val versionName: String,
    val releasedAt: String,
    val changelog: String
)

object UpdateChecker {
    private const val UPDATE_URL =
        "https://raw.githubusercontent.com/jedzqer/manga-translator/main/update.json"
    private const val DEFAULT_TIMEOUT_MS = 15_000

    suspend fun fetchUpdateInfo(timeoutMs: Int = DEFAULT_TIMEOUT_MS): UpdateInfo? =
        withContext(Dispatchers.IO) {
        val connection = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
            return@withContext try {
                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    AppLogger.log("UpdateChecker", "HTTP $code: $body")
                    null
                } else {
                    parseUpdateInfo(body)
                }
            } catch (e: Exception) {
                AppLogger.log("UpdateChecker", "Update request failed", e)
                null
            } finally {
                connection.disconnect()
            }
        }

    private fun parseUpdateInfo(body: String): UpdateInfo? {
        return try {
            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", -1)
            val versionName = json.optString("versionName").trim()
            val apkUrl = json.optString("apkUrl").trim()
            val changelog = json.optString("changelog").trim()
            val history = buildHistory(json)
            if (versionName.isBlank() || apkUrl.isBlank()) {
                AppLogger.log("UpdateChecker", "Invalid update json: $body")
                null
            } else {
                UpdateInfo(versionCode, versionName, apkUrl, changelog, history)
            }
        } catch (e: Exception) {
            AppLogger.log("UpdateChecker", "Parse update json failed", e)
            null
        }
    }

    private fun buildHistory(json: JSONObject): List<UpdateHistoryEntry> {
        val historyArray = json.optJSONArray("history") ?: return emptyList()
        val items = ArrayList<UpdateHistoryEntry>(historyArray.length())
        for (i in 0 until historyArray.length()) {
            val entry = historyArray.optJSONObject(i) ?: continue
            val versionName = entry.optString("versionName").trim()
            val releasedAt = entry.optString("releasedAt").trim()
            val changelog = entry.optString("changelog").trim()
            if (versionName.isBlank() || changelog.isBlank()) continue
            items.add(UpdateHistoryEntry(versionName, releasedAt, changelog))
        }
        return items
    }
}
