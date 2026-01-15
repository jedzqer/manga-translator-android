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

    fun loadUseHorizontalText(): Boolean {
        return prefs.getBoolean(KEY_HORIZONTAL_TEXT, false)
    }

    fun saveUseHorizontalText(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_HORIZONTAL_TEXT, enabled)
            .apply()
    }

    fun loadMaxConcurrency(): Int {
        val saved = prefs.getInt(KEY_MAX_CONCURRENCY, DEFAULT_MAX_CONCURRENCY)
        return saved.coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY)
    }

    fun saveMaxConcurrency(value: Int) {
        val normalized = value.coerceIn(MIN_MAX_CONCURRENCY, MAX_MAX_CONCURRENCY)
        prefs.edit()
            .putInt(KEY_MAX_CONCURRENCY, normalized)
            .apply()
    }

    fun loadThemeMode(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, ThemeMode.FOLLOW_SYSTEM.prefValue)
        return ThemeMode.fromPref(saved)
    }

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit()
            .putString(KEY_THEME_MODE, mode.prefValue)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "manga_translate_settings"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_HORIZONTAL_TEXT = "horizontal_text_layout"
        private const val KEY_MAX_CONCURRENCY = "max_concurrency"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val DEFAULT_MODEL = "gpt-3.5-turbo"
        private const val DEFAULT_MAX_CONCURRENCY = 3
        private const val MIN_MAX_CONCURRENCY = 1
        private const val MAX_MAX_CONCURRENCY = 50
    }
}
