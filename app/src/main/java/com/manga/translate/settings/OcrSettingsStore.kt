package com.manga.translate.settings

import com.manga.translate.model.OcrApiFormat

internal class OcrSettingsStore(
    private val storage: SettingsStoreStorage
) {
    fun loadOcrApiSettings(): OcrApiSettings {
        val apiFormatPref = storage.prefs.getString(SettingsStore.KEY_OCR_API_FORMAT, null)
        val hasUnsupportedApiFormat = OcrApiFormat.isUnsupportedPref(apiFormatPref)
        val useLocal = storage.prefs.getBoolean(SettingsStore.KEY_OCR_USE_LOCAL, true) ||
            hasUnsupportedApiFormat
        val url = if (hasUnsupportedApiFormat) {
            ""
        } else {
            storage.prefs.getString(
                SettingsStore.KEY_OCR_API_URL,
                SettingsStore.DEFAULT_OCR_API_URL
            ) ?: SettingsStore.DEFAULT_OCR_API_URL
        }
        val key = if (hasUnsupportedApiFormat) {
            ""
        } else {
            storage.prefs.getString(SettingsStore.KEY_OCR_API_KEY, "") ?: ""
        }
        val model = if (hasUnsupportedApiFormat) {
            ""
        } else {
            storage.prefs.getString(
                SettingsStore.KEY_OCR_MODEL_NAME,
                SettingsStore.DEFAULT_OCR_MODEL_NAME
            ) ?: SettingsStore.DEFAULT_OCR_MODEL_NAME
        }
        val timeoutSeconds = storage.prefs.getInt(
            SettingsStore.KEY_OCR_API_TIMEOUT_SECONDS,
            SettingsStore.DEFAULT_OCR_API_TIMEOUT_SECONDS
        ).coerceIn(
            SettingsStore.MIN_OCR_API_TIMEOUT_SECONDS,
            SettingsStore.MAX_OCR_API_TIMEOUT_SECONDS
        )
        return OcrApiSettings(
            useLocalOcr = useLocal,
            apiSupplementEnabled = !hasUnsupportedApiFormat && storage.prefs.getBoolean(SettingsStore.KEY_OCR_API_SUPPLEMENT, false),
            apiSupplementLevel = storage.prefs.getInt(SettingsStore.KEY_OCR_API_SUPPLEMENT_LEVEL, 2).coerceIn(1, 3),
            manualBubblesUseApi = !hasUnsupportedApiFormat && storage.prefs.getBoolean(SettingsStore.KEY_OCR_MANUAL_API, false),
            apiUrl = url,
            apiKey = key,
            modelName = model,
            timeoutSeconds = timeoutSeconds,
            apiOcrConcurrencyLimit = storage.prefs.getInt(
                SettingsStore.KEY_OCR_API_CONCURRENCY,
                SettingsStore.DEFAULT_OCR_API_CONCURRENCY
            ).coerceIn(
                SettingsStore.MIN_OCR_API_CONCURRENCY,
                SettingsStore.MAX_OCR_API_CONCURRENCY
            ),
            // Retain the legacy value for profile/settings-file compatibility. It is no longer
            // consulted by local OCR scheduling.
            localOcrConcurrencyLimit = SettingsStore.DEFAULT_LOCAL_OCR_CONCURRENCY,
            ocrApiFormat = OcrApiFormat.fromPref(apiFormatPref)
        )
    }

    fun saveOcrApiSettings(settings: OcrApiSettings) {
        val normalizedTimeout = settings.timeoutSeconds.coerceIn(
            SettingsStore.MIN_OCR_API_TIMEOUT_SECONDS,
            SettingsStore.MAX_OCR_API_TIMEOUT_SECONDS
        )
        val normalizedConcurrency = settings.apiOcrConcurrencyLimit.coerceIn(
            SettingsStore.MIN_OCR_API_CONCURRENCY,
            SettingsStore.MAX_OCR_API_CONCURRENCY
        )
        storage.editSettings(
            setOf(
                SettingsStore.KEY_OCR_USE_LOCAL,
                SettingsStore.KEY_OCR_API_SUPPLEMENT,
                SettingsStore.KEY_OCR_API_SUPPLEMENT_LEVEL,
                SettingsStore.KEY_OCR_MANUAL_API,
                SettingsStore.KEY_OCR_API_URL,
                SettingsStore.KEY_OCR_API_KEY,
                SettingsStore.KEY_OCR_MODEL_NAME,
                SettingsStore.KEY_OCR_API_TIMEOUT_SECONDS,
                SettingsStore.KEY_OCR_API_CONCURRENCY,
                SettingsStore.KEY_OCR_API_FORMAT
            )
        ) {
            putBoolean(SettingsStore.KEY_OCR_USE_LOCAL, settings.useLocalOcr)
                .putBoolean(SettingsStore.KEY_OCR_API_SUPPLEMENT, settings.apiSupplementEnabled)
                .putInt(SettingsStore.KEY_OCR_API_SUPPLEMENT_LEVEL, settings.apiSupplementLevel.coerceIn(1, 3))
                .putBoolean(SettingsStore.KEY_OCR_MANUAL_API, settings.manualBubblesUseApi)
                .putString(SettingsStore.KEY_OCR_API_URL, settings.apiUrl)
                .putString(SettingsStore.KEY_OCR_API_KEY, settings.apiKey)
                .putString(SettingsStore.KEY_OCR_MODEL_NAME, settings.modelName)
                .putInt(SettingsStore.KEY_OCR_API_TIMEOUT_SECONDS, normalizedTimeout)
                .putInt(SettingsStore.KEY_OCR_API_CONCURRENCY, normalizedConcurrency)
                .putString(
                    SettingsStore.KEY_OCR_API_FORMAT,
                    settings.ocrApiFormat.prefValue
                )
        }
    }
}
