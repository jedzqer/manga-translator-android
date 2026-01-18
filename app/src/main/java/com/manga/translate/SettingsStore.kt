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

    fun loadApiTimeoutSeconds(): Int {
        val saved = prefs.getInt(KEY_API_TIMEOUT_SECONDS, DEFAULT_API_TIMEOUT_SECONDS)
        return saved.coerceIn(MIN_API_TIMEOUT_SECONDS, MAX_API_TIMEOUT_SECONDS)
    }

    fun loadApiTimeoutMs(): Int {
        return loadApiTimeoutSeconds() * 1000
    }

    fun saveApiTimeoutSeconds(value: Int) {
        val normalized = value.coerceIn(MIN_API_TIMEOUT_SECONDS, MAX_API_TIMEOUT_SECONDS)
        prefs.edit()
            .putInt(KEY_API_TIMEOUT_SECONDS, normalized)
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

    fun loadReadingDisplayMode(): ReadingDisplayMode {
        val saved = prefs.getString(KEY_READING_DISPLAY_MODE, ReadingDisplayMode.FIT_WIDTH.prefValue)
        return ReadingDisplayMode.fromPref(saved)
    }

    fun saveReadingDisplayMode(mode: ReadingDisplayMode) {
        prefs.edit()
            .putString(KEY_READING_DISPLAY_MODE, mode.prefValue)
            .apply()
    }

    fun loadLlmParameters(): LlmParameterSettings {
        return LlmParameterSettings(
            temperature = readDoubleWithDefault(KEY_LLM_TEMPERATURE, DEFAULT_LLM_TEMPERATURE),
            topP = readDoubleWithDefault(KEY_LLM_TOP_P, DEFAULT_LLM_TOP_P),
            topK = readIntOptional(KEY_LLM_TOP_K),
            maxOutputTokens = readIntWithDefault(
                KEY_LLM_MAX_OUTPUT_TOKENS,
                DEFAULT_LLM_MAX_OUTPUT_TOKENS
            ),
            frequencyPenalty = readDoubleWithDefault(
                KEY_LLM_FREQUENCY_PENALTY,
                DEFAULT_LLM_FREQUENCY_PENALTY
            ),
            presencePenalty = readDoubleWithDefault(
                KEY_LLM_PRESENCE_PENALTY,
                DEFAULT_LLM_PRESENCE_PENALTY
            )
        )
    }

    fun saveLlmParameters(settings: LlmParameterSettings) {
        prefs.edit()
            .putOptionalString(KEY_LLM_TEMPERATURE, settings.temperature)
            .putOptionalString(KEY_LLM_TOP_P, settings.topP)
            .putOptionalString(KEY_LLM_TOP_K, settings.topK)
            .putOptionalString(KEY_LLM_MAX_OUTPUT_TOKENS, settings.maxOutputTokens)
            .putOptionalString(KEY_LLM_FREQUENCY_PENALTY, settings.frequencyPenalty)
            .putOptionalString(KEY_LLM_PRESENCE_PENALTY, settings.presencePenalty)
            .apply()
    }

    private fun readDoubleWithDefault(key: String, defaultValue: Double): Double? {
        if (!prefs.contains(key)) return defaultValue
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toDoubleOrNull()
    }

    private fun readIntWithDefault(key: String, defaultValue: Int): Int? {
        if (!prefs.contains(key)) return defaultValue
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toIntOrNull()
    }

    private fun readIntOptional(key: String): Int? {
        if (!prefs.contains(key)) return null
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toIntOrNull()
    }

    companion object {
        private const val PREFS_NAME = "manga_translate_settings"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_HORIZONTAL_TEXT = "horizontal_text_layout"
        private const val KEY_MAX_CONCURRENCY = "max_concurrency"
        private const val KEY_API_TIMEOUT_SECONDS = "api_timeout_seconds"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_READING_DISPLAY_MODE = "reading_display_mode"
        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_TOP_P = "llm_top_p"
        private const val KEY_LLM_TOP_K = "llm_top_k"
        private const val KEY_LLM_MAX_OUTPUT_TOKENS = "llm_max_output_tokens"
        private const val KEY_LLM_FREQUENCY_PENALTY = "llm_frequency_penalty"
        private const val KEY_LLM_PRESENCE_PENALTY = "llm_presence_penalty"
        private const val DEFAULT_LLM_TEMPERATURE = 0.8
        private const val DEFAULT_LLM_TOP_P = 1.0
        private const val DEFAULT_LLM_MAX_OUTPUT_TOKENS = 8192
        private const val DEFAULT_LLM_FREQUENCY_PENALTY = 0.4
        private const val DEFAULT_LLM_PRESENCE_PENALTY = 0.2
        private const val DEFAULT_MODEL = "gpt-3.5-turbo"
        private const val DEFAULT_MAX_CONCURRENCY = 3
        private const val MIN_MAX_CONCURRENCY = 1
        private const val MAX_MAX_CONCURRENCY = 50
        private const val DEFAULT_API_TIMEOUT_SECONDS = 300
        private const val MIN_API_TIMEOUT_SECONDS = 30
        private const val MAX_API_TIMEOUT_SECONDS = 1200
    }
}

data class LlmParameterSettings(
    val temperature: Double?,
    val topP: Double?,
    val topK: Int?,
    val maxOutputTokens: Int?,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?
)

private fun android.content.SharedPreferences.Editor.putOptionalString(
    key: String,
    value: Number?
): android.content.SharedPreferences.Editor {
    putString(key, value?.toString().orEmpty())
    return this
}
