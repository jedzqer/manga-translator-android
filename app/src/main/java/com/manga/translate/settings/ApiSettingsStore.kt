package com.manga.translate.settings

import com.manga.translate.model.ApiFormat
import com.manga.translate.model.FloatingBallGestureAction

internal class ApiSettingsStore(
    private val storage: SettingsStoreStorage
) {
    fun load(): ApiSettings {
        val url = storage.prefs.getString(SettingsStore.KEY_API_URL, SettingsStore.DEFAULT_API_URL)
            ?: SettingsStore.DEFAULT_API_URL
        val key = storage.prefs.getString(SettingsStore.KEY_API_KEY, "") ?: ""
        val model = storage.prefs.getString(SettingsStore.KEY_MODEL_NAME, SettingsStore.DEFAULT_MODEL)
            ?: SettingsStore.DEFAULT_MODEL
        val apiFormat = ApiFormat.fromPref(storage.prefs.getString(SettingsStore.KEY_API_FORMAT, null))
        return ApiSettings(url, key, model, apiFormat, PRIMARY_PROVIDER_ID)
    }

    fun save(settings: ApiSettings) {
        storage.editSettings(
            setOf(
                SettingsStore.KEY_API_URL,
                SettingsStore.KEY_API_KEY,
                SettingsStore.KEY_MODEL_NAME,
                SettingsStore.KEY_API_FORMAT
            )
        ) {
            putString(SettingsStore.KEY_API_URL, settings.apiUrl)
                .putString(SettingsStore.KEY_API_KEY, settings.apiKey)
                .putString(SettingsStore.KEY_MODEL_NAME, settings.modelName)
                .putString(SettingsStore.KEY_API_FORMAT, settings.apiFormat.prefValue)
        }
    }

    fun loadFloatingTranslateApiSettings(): FloatingTranslateApiSettings {
        val legacyAiConcurrency = storage.prefs.getInt(
            SettingsStore.KEY_FLOATING_VL_TRANSLATE_CONCURRENCY,
            SettingsStore.DEFAULT_FLOATING_AI_API_CONCURRENCY
        ).coerceIn(
            SettingsStore.MIN_FLOATING_AI_API_CONCURRENCY,
            SettingsStore.MAX_FLOATING_AI_API_CONCURRENCY
        )
        val ocrConcurrency = when {
            storage.prefs.contains(SettingsStore.KEY_FLOATING_OCR_CONCURRENCY) -> storage.prefs.getInt(
                SettingsStore.KEY_FLOATING_OCR_CONCURRENCY,
                SettingsStore.DEFAULT_FLOATING_OCR_CONCURRENCY
            )
            storage.prefs.contains(SettingsStore.KEY_FLOATING_VL_TRANSLATE_CONCURRENCY) -> legacyAiConcurrency
            else -> SettingsStore.DEFAULT_FLOATING_OCR_CONCURRENCY
        }.coerceIn(
            SettingsStore.MIN_FLOATING_OCR_CONCURRENCY,
            SettingsStore.MAX_FLOATING_OCR_CONCURRENCY
        )
        val detectionTopInsetPercent = storage.prefs.getInt(
            SettingsStore.KEY_FLOATING_DETECTION_TOP_INSET_PERCENT,
            0
        ).coerceIn(0, 90)
        val detectionBottomInsetPercent = storage.prefs.getInt(
            SettingsStore.KEY_FLOATING_DETECTION_BOTTOM_INSET_PERCENT,
            0
        ).coerceIn(0, 90 - detectionTopInsetPercent)
        return FloatingTranslateApiSettings(
            apiUrl = storage.prefs.getString(SettingsStore.KEY_FLOATING_API_URL, "") ?: "",
            apiKey = storage.prefs.getString(SettingsStore.KEY_FLOATING_API_KEY, "") ?: "",
            modelName = storage.prefs.getString(SettingsStore.KEY_FLOATING_MODEL_NAME, "") ?: "",
            timeoutSeconds = storage.prefs.getInt(
                SettingsStore.KEY_FLOATING_TIMEOUT_SECONDS,
                SettingsStore.DEFAULT_FLOATING_API_TIMEOUT_SECONDS
            ).coerceIn(
                SettingsStore.MIN_FLOATING_API_TIMEOUT_SECONDS,
                SettingsStore.MAX_FLOATING_API_TIMEOUT_SECONDS
            ),
            useVlDirectTranslate = storage.prefs.getBoolean(
                SettingsStore.KEY_FLOATING_USE_VL_DIRECT_TRANSLATE,
                false
            ),
            ocrConcurrencyLimit = ocrConcurrency,
            aiApiConcurrencyLimit = legacyAiConcurrency,
            proofreadingModeEnabled = storage.prefs.getBoolean(
                SettingsStore.KEY_FLOATING_PROOFREADING_MODE_ENABLED,
                false
            ),
            autoCloseOnScreenChangeEnabled = storage.prefs.getBoolean(
                SettingsStore.KEY_FLOATING_AUTO_CLOSE_ON_SCREEN_CHANGE_ENABLED,
                false
            ),
            singleTapAction = FloatingBallGestureAction.fromPref(
                storage.prefs.getString(SettingsStore.KEY_FLOATING_SINGLE_TAP_ACTION, null),
                SettingsStore.DEFAULT_FLOATING_SINGLE_TAP_ACTION
            ),
            doubleTapAction = FloatingBallGestureAction.fromPref(
                storage.prefs.getString(SettingsStore.KEY_FLOATING_DOUBLE_TAP_ACTION, null),
                SettingsStore.DEFAULT_FLOATING_DOUBLE_TAP_ACTION
            ),
            longPressAction = FloatingBallGestureAction.fromPref(
                storage.prefs.getString(SettingsStore.KEY_FLOATING_LONG_PRESS_ACTION, null),
                SettingsStore.DEFAULT_FLOATING_LONG_PRESS_ACTION
            ),
            tripleTapAction = FloatingBallGestureAction.fromPref(
                storage.prefs.getString(SettingsStore.KEY_FLOATING_TRIPLE_TAP_ACTION, null),
                SettingsStore.DEFAULT_FLOATING_TRIPLE_TAP_ACTION
            ),
            detectionTopInsetPercent = detectionTopInsetPercent,
            detectionBottomInsetPercent = detectionBottomInsetPercent
        )
    }

    fun loadResolvedFloatingTranslateApiSettings(): ApiSettings {
        val floating = loadFloatingTranslateApiSettings()
        val main = load()
        return ApiSettings(
            apiUrl = floating.apiUrl.ifBlank { main.apiUrl },
            apiKey = floating.apiKey.ifBlank { main.apiKey },
            modelName = floating.modelName.ifBlank { main.modelName },
            apiFormat = main.apiFormat,
            providerId = PRIMARY_PROVIDER_ID
        )
    }

    fun saveFloatingTranslateApiSettings(settings: FloatingTranslateApiSettings) {
        val normalizedOcrConcurrency = settings.ocrConcurrencyLimit.coerceIn(
            SettingsStore.MIN_FLOATING_OCR_CONCURRENCY,
            SettingsStore.MAX_FLOATING_OCR_CONCURRENCY
        )
        val normalizedAiConcurrency = settings.aiApiConcurrencyLimit.coerceIn(
            SettingsStore.MIN_FLOATING_AI_API_CONCURRENCY,
            SettingsStore.MAX_FLOATING_AI_API_CONCURRENCY
        )
        val normalizedTimeout = settings.timeoutSeconds.coerceIn(
            SettingsStore.MIN_FLOATING_API_TIMEOUT_SECONDS,
            SettingsStore.MAX_FLOATING_API_TIMEOUT_SECONDS
        )
        val normalizedTopInset = settings.detectionTopInsetPercent.coerceIn(0, 90)
        val normalizedBottomInset = settings.detectionBottomInsetPercent.coerceIn(
            0,
            90 - normalizedTopInset
        )
        storage.editSettings(
            setOf(
                SettingsStore.KEY_FLOATING_API_URL,
                SettingsStore.KEY_FLOATING_API_KEY,
                SettingsStore.KEY_FLOATING_MODEL_NAME,
                SettingsStore.KEY_FLOATING_TIMEOUT_SECONDS,
                SettingsStore.KEY_FLOATING_USE_VL_DIRECT_TRANSLATE,
                SettingsStore.KEY_FLOATING_OCR_CONCURRENCY,
                SettingsStore.KEY_FLOATING_VL_TRANSLATE_CONCURRENCY,
                SettingsStore.KEY_FLOATING_PROOFREADING_MODE_ENABLED,
                SettingsStore.KEY_FLOATING_AUTO_CLOSE_ON_SCREEN_CHANGE_ENABLED,
                SettingsStore.KEY_FLOATING_SINGLE_TAP_ACTION,
                SettingsStore.KEY_FLOATING_DOUBLE_TAP_ACTION,
                SettingsStore.KEY_FLOATING_LONG_PRESS_ACTION,
                SettingsStore.KEY_FLOATING_TRIPLE_TAP_ACTION,
                SettingsStore.KEY_FLOATING_DETECTION_TOP_INSET_PERCENT,
                SettingsStore.KEY_FLOATING_DETECTION_BOTTOM_INSET_PERCENT
            )
        ) {
            putString(SettingsStore.KEY_FLOATING_API_URL, settings.apiUrl)
                .putString(SettingsStore.KEY_FLOATING_API_KEY, settings.apiKey)
                .putString(SettingsStore.KEY_FLOATING_MODEL_NAME, settings.modelName)
                .putInt(SettingsStore.KEY_FLOATING_TIMEOUT_SECONDS, normalizedTimeout)
                .putBoolean(
                    SettingsStore.KEY_FLOATING_USE_VL_DIRECT_TRANSLATE,
                    settings.useVlDirectTranslate
                )
                .putInt(SettingsStore.KEY_FLOATING_OCR_CONCURRENCY, normalizedOcrConcurrency)
                .putInt(
                    SettingsStore.KEY_FLOATING_VL_TRANSLATE_CONCURRENCY,
                    normalizedAiConcurrency
                )
                .putBoolean(
                    SettingsStore.KEY_FLOATING_PROOFREADING_MODE_ENABLED,
                    settings.proofreadingModeEnabled
                )
                .putBoolean(
                    SettingsStore.KEY_FLOATING_AUTO_CLOSE_ON_SCREEN_CHANGE_ENABLED,
                    settings.autoCloseOnScreenChangeEnabled
                )
                .putString(
                    SettingsStore.KEY_FLOATING_SINGLE_TAP_ACTION,
                    settings.singleTapAction.prefValue
                )
                .putString(
                    SettingsStore.KEY_FLOATING_DOUBLE_TAP_ACTION,
                    settings.doubleTapAction.prefValue
                )
                .putString(
                    SettingsStore.KEY_FLOATING_LONG_PRESS_ACTION,
                    settings.longPressAction.prefValue
                )
                .putString(
                    SettingsStore.KEY_FLOATING_TRIPLE_TAP_ACTION,
                    settings.tripleTapAction.prefValue
                )
                .putInt(SettingsStore.KEY_FLOATING_DETECTION_TOP_INSET_PERCENT, normalizedTopInset)
                .putInt(SettingsStore.KEY_FLOATING_DETECTION_BOTTOM_INSET_PERCENT, normalizedBottomInset)
        }
    }

    fun loadModelIoLogging(): Boolean {
        return storage.prefs.getBoolean(SettingsStore.KEY_MODEL_IO_LOGGING, false)
    }

    fun saveModelIoLogging(enabled: Boolean) {
        storage.editSettings(setOf(SettingsStore.KEY_MODEL_IO_LOGGING)) {
            putBoolean(SettingsStore.KEY_MODEL_IO_LOGGING, enabled)
        }
    }

    fun persistMainSettings(form: SettingsMainForm): SettingsPersistenceResult {
        val normalizedTimeout = form.apiTimeoutSeconds.coerceIn(
            SettingsStore.MIN_API_TIMEOUT_SECONDS,
            SettingsStore.MAX_API_TIMEOUT_SECONDS
        )
        val normalizedRetryCount = form.apiRetryCount.coerceIn(
            SettingsStore.MIN_API_RETRY_COUNT,
            SettingsStore.MAX_API_RETRY_COUNT
        )
        val normalizedConcurrency = form.maxConcurrency.coerceIn(
            SettingsStore.MIN_MAX_CONCURRENCY,
            SettingsStore.MAX_MAX_CONCURRENCY
        )
        val changedKeys = buildSet {
            add(SettingsStore.KEY_API_URL)
            add(SettingsStore.KEY_API_KEY)
            add(SettingsStore.KEY_MODEL_NAME)
            add(SettingsStore.KEY_API_FORMAT)
            add(SettingsStore.KEY_API_TIMEOUT_SECONDS)
            add(SettingsStore.KEY_API_RETRY_COUNT)
            add(SettingsStore.KEY_MAX_CONCURRENCY)
        }
        storage.editSettings(changedKeys) {
            putString(SettingsStore.KEY_API_URL, form.apiUrl)
            putString(SettingsStore.KEY_API_KEY, form.apiKey)
            putString(SettingsStore.KEY_MODEL_NAME, form.modelName)
            putString(SettingsStore.KEY_API_FORMAT, form.apiFormat.prefValue)
            putInt(SettingsStore.KEY_API_TIMEOUT_SECONDS, normalizedTimeout)
            putInt(SettingsStore.KEY_API_RETRY_COUNT, normalizedRetryCount)
            putInt(SettingsStore.KEY_MAX_CONCURRENCY, normalizedConcurrency)
        }

        return SettingsPersistenceResult(
            apiTimeoutSeconds = normalizedTimeout,
            apiRetryCount = normalizedRetryCount,
            maxConcurrency = normalizedConcurrency
        )
    }

    fun loadApiRetryCount(): Int {
        val saved = storage.prefs.getInt(
            SettingsStore.KEY_API_RETRY_COUNT,
            SettingsStore.DEFAULT_API_RETRY_COUNT
        )
        return saved.coerceIn(
            SettingsStore.MIN_API_RETRY_COUNT,
            SettingsStore.MAX_API_RETRY_COUNT
        )
    }

    fun saveApiRetryCount(value: Int) {
        val normalized = value.coerceIn(
            SettingsStore.MIN_API_RETRY_COUNT,
            SettingsStore.MAX_API_RETRY_COUNT
        )
        storage.editSettings(setOf(SettingsStore.KEY_API_RETRY_COUNT)) {
            putInt(SettingsStore.KEY_API_RETRY_COUNT, normalized)
        }
    }

    fun loadMaxConcurrency(): Int {
        val saved = storage.prefs.getInt(
            SettingsStore.KEY_MAX_CONCURRENCY,
            SettingsStore.DEFAULT_MAX_CONCURRENCY
        )
        return saved.coerceIn(
            SettingsStore.MIN_MAX_CONCURRENCY,
            SettingsStore.MAX_MAX_CONCURRENCY
        )
    }

    fun saveMaxConcurrency(value: Int) {
        val normalized = value.coerceIn(
            SettingsStore.MIN_MAX_CONCURRENCY,
            SettingsStore.MAX_MAX_CONCURRENCY
        )
        storage.editSettings(setOf(SettingsStore.KEY_MAX_CONCURRENCY)) {
            putInt(SettingsStore.KEY_MAX_CONCURRENCY, normalized)
        }
    }

    fun loadApiTimeoutSeconds(): Int {
        val saved = storage.prefs.getInt(
            SettingsStore.KEY_API_TIMEOUT_SECONDS,
            SettingsStore.DEFAULT_API_TIMEOUT_SECONDS
        )
        return saved.coerceIn(
            SettingsStore.MIN_API_TIMEOUT_SECONDS,
            SettingsStore.MAX_API_TIMEOUT_SECONDS
        )
    }

    fun loadApiTimeoutMs(): Int = loadApiTimeoutSeconds() * 1000

    fun saveApiTimeoutSeconds(value: Int) {
        val normalized = value.coerceIn(
            SettingsStore.MIN_API_TIMEOUT_SECONDS,
            SettingsStore.MAX_API_TIMEOUT_SECONDS
        )
        storage.editSettings(setOf(SettingsStore.KEY_API_TIMEOUT_SECONDS)) {
            putInt(SettingsStore.KEY_API_TIMEOUT_SECONDS, normalized)
        }
    }
}
