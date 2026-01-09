package com.manga.translate

import android.content.Context

data class ApiSettings(
    val apiUrl: String,
    val apiKey: String,
    val modelName: String
) {
    fun isValid(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ApiSettings {
        val url = prefs.getString(KEY_API_URL, "") ?: ""
        val key = prefs.getString(KEY_API_KEY, "") ?: ""
        val model = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
        return ApiSettings(url, key, model)
    }

    fun save(settings: ApiSettings) {
        prefs.edit()
            .putString(KEY_API_URL, settings.apiUrl)
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL_NAME, settings.modelName)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "manga_translate_settings"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val DEFAULT_MODEL = "gpt-3.5-turbo"
    }
}
