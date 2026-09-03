package com.manga.translate.settings

import com.manga.translate.R
import com.manga.translate.model.ApiFormat
import com.manga.translate.model.FloatingBallGestureAction
import com.manga.translate.model.OcrApiFormat
import com.manga.translate.model.ThinkingLength
import com.manga.translate.platform.AppLogger
import org.json.JSONArray
import org.json.JSONObject

internal class ProviderProfileStore(
    private val storage: SettingsStoreStorage,
    private val apiSettingsStore: ApiSettingsStore,
    private val ocrSettingsStore: OcrSettingsStore,
    private val llmParameterStore: LlmParameterStore,
    private val profileFileWriter: AiProviderProfilesFileWriter = AtomicAiProviderProfilesFileWriter
) {
    fun loadCustomRequestParameters(): List<CustomRequestParameter> {
        val raw = storage.prefs.getString(SettingsStore.KEY_CUSTOM_REQUEST_PARAMETERS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = storage.parseVersionedArrayPayload(
                raw = raw,
                arrayKey = "items",
                label = SettingsStore.KEY_CUSTOM_REQUEST_PARAMETERS
            )
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").trim()
                    val value = item.optString("value")
                    val enabled = item.optBoolean("enabled", true)
                    if (key.isBlank() && value.isBlank()) continue
                    add(
                        CustomRequestParameter(
                            key = key,
                            value = value,
                            enabled = enabled
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomRequestParameters(parameters: List<CustomRequestParameter>) {
        val array = JSONArray()
        parameters.forEach { parameter ->
            val key = parameter.key.trim()
            val value = parameter.value
            if (key.isBlank() && value.isBlank()) return@forEach
            array.put(
                JSONObject()
                    .put("key", key)
                    .put("value", value)
                    .put("enabled", parameter.enabled)
            )
        }
        storage.editSettings(setOf(SettingsStore.KEY_CUSTOM_REQUEST_PARAMETERS)) {
            putString(
                SettingsStore.KEY_CUSTOM_REQUEST_PARAMETERS,
                JSONObject()
                    .put("version", SettingsStore.SETTINGS_JSON_SCHEMA_VERSION)
                    .put("items", array)
                    .toString()
            )
        }
    }

    fun loadAiProviderProfilesState(): AiProviderProfilesState {
        val raw = runCatching {
            if (storage.aiProviderProfilesFile.exists()) {
                storage.aiProviderProfilesFile.readText()
            } else {
                ""
            }
        }.getOrDefault("")
        if (raw.isBlank()) {
            return AiProviderProfilesState(activeProfileName = null, profiles = emptyList())
        }
        return runCatching {
            val root = JSONObject(raw)
            val version = when {
                !root.has("version") -> SettingsStore.LEGACY_SETTINGS_JSON_VERSION
                else -> root.optInt("version", SettingsStore.LEGACY_SETTINGS_JSON_VERSION)
            }
            if (version !in SettingsStore.LEGACY_SETTINGS_JSON_VERSION..SettingsStore.SETTINGS_JSON_SCHEMA_VERSION) {
                AppLogger.log(
                    "SettingsStore",
                    "Skip ai provider profiles for unsupported version=$version"
                )
                return AiProviderProfilesState(activeProfileName = null, profiles = emptyList())
            }
            val profilesJson = root.optJSONArray("profiles") ?: JSONArray()
            val profiles = buildList {
                for (index in 0 until profilesJson.length()) {
                    val item = profilesJson.optJSONObject(index) ?: continue
                    parseAiProviderProfile(item)?.let(::add)
                }
            }
            val activeProfileName = root.optString("activeProfileName").trim().ifBlank { null }
            val normalizedActive = activeProfileName?.takeIf { active ->
                profiles.any { it.name == active }
            }
            AiProviderProfilesState(
                activeProfileName = normalizedActive,
                profiles = profiles.sortedBy { it.name.lowercase() }
            )
        }.getOrDefault(AiProviderProfilesState(activeProfileName = null, profiles = emptyList()))
    }

    fun saveCurrentAsAiProviderProfile(name: String): Boolean {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) return false
        val currentState = loadAiProviderProfilesState()
        if (currentState.profiles.any { it.name == normalizedName }) return false
        val updatedProfiles = currentState.profiles + captureCurrentAiProviderProfile(normalizedName)
        return writeAiProviderProfilesState(
            AiProviderProfilesState(
                activeProfileName = normalizedName,
                profiles = updatedProfiles
            )
        )
    }

    fun overwriteActiveAiProviderProfile(): Boolean {
        val currentState = loadAiProviderProfilesState()
        val activeProfileName = currentState.activeProfileName ?: return false
        val updatedProfiles = currentState.profiles.map { profile ->
            if (profile.name == activeProfileName) {
                captureCurrentAiProviderProfile(activeProfileName)
            } else {
                profile
            }
        }
        return writeAiProviderProfilesState(currentState.copy(profiles = updatedProfiles))
    }

    fun applyAiProviderProfile(name: String): Boolean {
        val currentState = loadAiProviderProfilesState()
        val profile = currentState.profiles.firstOrNull { it.name == name } ?: return false
        if (!canApplyAiProviderProfile(profile)) return false
        if (!writeAiProviderProfilesState(currentState.copy(activeProfileName = profile.name))) {
            return false
        }
        apiSettingsStore.save(profile.mainSettings)
        apiSettingsStore.saveApiTimeoutSeconds(profile.apiTimeoutSeconds)
        apiSettingsStore.saveApiRetryCount(profile.apiRetryCount)
        apiSettingsStore.saveMaxConcurrency(profile.maxConcurrency)
        ocrSettingsStore.saveOcrApiSettings(profile.ocrSettings)
        apiSettingsStore.saveFloatingTranslateApiSettings(profile.floatingTranslateSettings)
        llmParameterStore.saveLlmParameters(profile.llmParameters)
        saveCustomRequestParameters(profile.customRequestParameters)
        return true
    }

    fun deleteAiProviderProfile(name: String): Boolean {
        val currentState = loadAiProviderProfilesState()
        val updatedProfiles = currentState.profiles.filterNot { it.name == name }
        if (updatedProfiles.size == currentState.profiles.size) return false
        val updatedActive = currentState.activeProfileName?.takeIf { it != name }
        return writeAiProviderProfilesState(
            AiProviderProfilesState(
                activeProfileName = updatedActive,
                profiles = updatedProfiles
            )
        )
    }

    private fun captureCurrentAiProviderProfile(name: String): AiProviderProfile {
        return AiProviderProfile(
            name = name,
            mainSettings = apiSettingsStore.load(),
            apiTimeoutSeconds = apiSettingsStore.loadApiTimeoutSeconds(),
            apiRetryCount = apiSettingsStore.loadApiRetryCount(),
            maxConcurrency = apiSettingsStore.loadMaxConcurrency(),
            ocrSettings = ocrSettingsStore.loadOcrApiSettings(),
            floatingTranslateSettings = apiSettingsStore.loadFloatingTranslateApiSettings(),
            llmParameters = llmParameterStore.loadLlmParameters(),
            customRequestParameters = loadCustomRequestParameters()
        )
    }

    private fun canApplyAiProviderProfile(profile: AiProviderProfile): Boolean {
        if (profile.apiRetryCount !in SettingsStore.MIN_API_RETRY_COUNT..SettingsStore.MAX_API_RETRY_COUNT) {
            return false
        }
        if (profile.maxConcurrency !in SettingsStore.MIN_MAX_CONCURRENCY..SettingsStore.MAX_MAX_CONCURRENCY) {
            return false
        }
        return profile.maxConcurrency >= 1
    }

    private fun writeAiProviderProfilesState(state: AiProviderProfilesState): Boolean {
        val root = JSONObject()
        root.put("version", SettingsStore.SETTINGS_JSON_SCHEMA_VERSION)
        root.put("activeProfileName", state.activeProfileName.orEmpty())
        val profilesArray = JSONArray()
        state.profiles
            .sortedBy { it.name.lowercase() }
            .forEach { profile ->
                profilesArray.put(serializeAiProviderProfile(profile))
            }
        root.put("profiles", profilesArray)
        if (!profileFileWriter.write(storage.aiProviderProfilesFile, root.toString())) {
            AppLogger.log("Settings", "Failed to write AI provider profiles")
            return false
        }
        storage.settingsObserver.publish(setOf(SettingsStore.KEY_AI_PROVIDER_PROFILES_STATE))
        return true
    }

    private fun serializeAiProviderProfile(profile: AiProviderProfile): JSONObject {
        return JSONObject()
            .put("name", profile.name)
            .put(
                "mainSettings",
                JSONObject()
                    .put("apiUrl", profile.mainSettings.apiUrl)
                    .put("apiKey", profile.mainSettings.apiKey)
                    .put("modelName", profile.mainSettings.modelName)
                    .put("apiFormat", profile.mainSettings.apiFormat.prefValue)
                    .put("apiTimeoutSeconds", profile.apiTimeoutSeconds)
                    .put("apiRetryCount", profile.apiRetryCount)
                    .put("maxConcurrency", profile.maxConcurrency)
            )
            .put(
                "ocrSettings",
                JSONObject()
                    .put("useLocalOcr", profile.ocrSettings.useLocalOcr)
                    .put("apiUrl", profile.ocrSettings.apiUrl)
                    .put("apiKey", profile.ocrSettings.apiKey)
                    .put("modelName", profile.ocrSettings.modelName)
                    .put("timeoutSeconds", profile.ocrSettings.timeoutSeconds)
                    .put("apiOcrConcurrencyLimit", profile.ocrSettings.apiOcrConcurrencyLimit)
                    .put("localOcrConcurrencyLimit", profile.ocrSettings.localOcrConcurrencyLimit)
                    .put("ocrApiFormat", profile.ocrSettings.ocrApiFormat.prefValue)
            )
            .put(
                "floatingTranslateSettings",
                JSONObject()
                    .put("apiUrl", profile.floatingTranslateSettings.apiUrl)
                    .put("apiKey", profile.floatingTranslateSettings.apiKey)
                    .put("modelName", profile.floatingTranslateSettings.modelName)
                    .put("timeoutSeconds", profile.floatingTranslateSettings.timeoutSeconds)
                    .put(
                        "useVlDirectTranslate",
                        profile.floatingTranslateSettings.useVlDirectTranslate
                    )
                    .put(
                        "ocrConcurrencyLimit",
                        profile.floatingTranslateSettings.ocrConcurrencyLimit
                    )
                    .put(
                        "aiApiConcurrencyLimit",
                        profile.floatingTranslateSettings.aiApiConcurrencyLimit
                    )
                    .put(
                        "proofreadingModeEnabled",
                        profile.floatingTranslateSettings.proofreadingModeEnabled
                    )
                    .put(
                        "autoCloseOnScreenChangeEnabled",
                        profile.floatingTranslateSettings.autoCloseOnScreenChangeEnabled
                    )
                    .put(
                        "detectionTopInsetPercent",
                        profile.floatingTranslateSettings.detectionTopInsetPercent
                    )
                    .put(
                        "detectionBottomInsetPercent",
                        profile.floatingTranslateSettings.detectionBottomInsetPercent
                    )
                    .put(
                        "singleTapAction",
                        profile.floatingTranslateSettings.singleTapAction.prefValue
                    )
                    .put(
                        "doubleTapAction",
                        profile.floatingTranslateSettings.doubleTapAction.prefValue
                    )
                    .put(
                        "longPressAction",
                        profile.floatingTranslateSettings.longPressAction.prefValue
                    )
                    .put(
                        "tripleTapAction",
                        profile.floatingTranslateSettings.tripleTapAction.prefValue
                    )
            )
            .put(
                "llmParameters",
                JSONObject()
                    .put("temperature", profile.llmParameters.temperature)
                    .put("topP", profile.llmParameters.topP)
                    .put("topK", profile.llmParameters.topK)
                    .put("maxOutputTokens", profile.llmParameters.maxOutputTokens)
                    .put("enableThinking", profile.llmParameters.enableThinking)
                    .put("thinkingLength", profile.llmParameters.thinkingLength.prefValue)
                    .put("frequencyPenalty", profile.llmParameters.frequencyPenalty)
                    .put("presencePenalty", profile.llmParameters.presencePenalty)
            )
            .put(
                "customRequestParameters",
                JSONArray().apply {
                    profile.customRequestParameters.forEach { parameter ->
                        put(
                            JSONObject()
                                .put("key", parameter.key)
                                .put("value", parameter.value)
                                .put("enabled", parameter.enabled)
                        )
                    }
                }
            )
    }

    private fun parseAiProviderProfile(item: JSONObject): AiProviderProfile? {
        val name = item.optString("name").trim()
        if (name.isBlank()) return null
        val mainJson = item.optJSONObject("mainSettings") ?: JSONObject()
        val ocrJson = item.optJSONObject("ocrSettings") ?: JSONObject()
        val floatingJson = item.optJSONObject("floatingTranslateSettings") ?: JSONObject()
        val llmJson = item.optJSONObject("llmParameters") ?: JSONObject()
        val customParams = item.optJSONArray("customRequestParameters") ?: JSONArray()
        val ocrApiFormatPref = ocrJson.optStringOrNull("ocrApiFormat")
        val hasUnsupportedOcrFormat = OcrApiFormat.isUnsupportedPref(ocrApiFormatPref)
        return AiProviderProfile(
            name = name,
            mainSettings = ApiSettings(
                apiUrl = mainJson.optString("apiUrl", SettingsStore.DEFAULT_API_URL),
                apiKey = mainJson.optString("apiKey"),
                modelName = mainJson.optString("modelName", SettingsStore.DEFAULT_MODEL),
                apiFormat = ApiFormat.fromPref(mainJson.optStringOrNull("apiFormat")),
                providerId = PRIMARY_PROVIDER_ID
            ),
            apiTimeoutSeconds = mainJson.optInt(
                "apiTimeoutSeconds",
                SettingsStore.DEFAULT_API_TIMEOUT_SECONDS
            ).coerceIn(
                SettingsStore.MIN_API_TIMEOUT_SECONDS,
                SettingsStore.MAX_API_TIMEOUT_SECONDS
            ),
            apiRetryCount = mainJson.optInt(
                "apiRetryCount",
                SettingsStore.DEFAULT_API_RETRY_COUNT
            ).coerceIn(
                SettingsStore.MIN_API_RETRY_COUNT,
                SettingsStore.MAX_API_RETRY_COUNT
            ),
            maxConcurrency = mainJson.optInt(
                "maxConcurrency",
                SettingsStore.DEFAULT_MAX_CONCURRENCY
            ).coerceIn(
                SettingsStore.MIN_MAX_CONCURRENCY,
                SettingsStore.MAX_MAX_CONCURRENCY
            ),
            ocrSettings = OcrApiSettings(
                useLocalOcr = ocrJson.optBoolean("useLocalOcr", true) || hasUnsupportedOcrFormat,
                apiUrl = if (hasUnsupportedOcrFormat) {
                    ""
                } else {
                    ocrJson.optString("apiUrl", SettingsStore.DEFAULT_OCR_API_URL)
                },
                apiKey = if (hasUnsupportedOcrFormat) "" else ocrJson.optString("apiKey"),
                modelName = if (hasUnsupportedOcrFormat) {
                    ""
                } else {
                    ocrJson.optString("modelName", SettingsStore.DEFAULT_OCR_MODEL_NAME)
                },
                timeoutSeconds = ocrJson.optInt(
                    "timeoutSeconds",
                    SettingsStore.DEFAULT_OCR_API_TIMEOUT_SECONDS
                ).coerceIn(
                    SettingsStore.MIN_OCR_API_TIMEOUT_SECONDS,
                    SettingsStore.MAX_OCR_API_TIMEOUT_SECONDS
                ),
                apiOcrConcurrencyLimit = ocrJson.optInt(
                    "apiOcrConcurrencyLimit",
                    SettingsStore.DEFAULT_OCR_API_CONCURRENCY
                ).coerceIn(
                    SettingsStore.MIN_OCR_API_CONCURRENCY,
                    SettingsStore.MAX_OCR_API_CONCURRENCY
                ),
                localOcrConcurrencyLimit = ocrJson.optInt(
                    "localOcrConcurrencyLimit",
                    SettingsStore.DEFAULT_LOCAL_OCR_CONCURRENCY
                ).coerceIn(
                    SettingsStore.MIN_LOCAL_OCR_CONCURRENCY,
                    SettingsStore.MAX_LOCAL_OCR_CONCURRENCY
                ),
                ocrApiFormat = OcrApiFormat.fromPref(ocrApiFormatPref)
            ),
            floatingTranslateSettings = FloatingTranslateApiSettings(
                apiUrl = floatingJson.optString("apiUrl"),
                apiKey = floatingJson.optString("apiKey"),
                modelName = floatingJson.optString("modelName"),
                timeoutSeconds = floatingJson.optInt(
                    "timeoutSeconds",
                    SettingsStore.DEFAULT_FLOATING_API_TIMEOUT_SECONDS
                ).coerceIn(
                    SettingsStore.MIN_FLOATING_API_TIMEOUT_SECONDS,
                    SettingsStore.MAX_FLOATING_API_TIMEOUT_SECONDS
                ),
                useVlDirectTranslate = floatingJson.optBoolean("useVlDirectTranslate", false),
                ocrConcurrencyLimit = floatingJson.optInt(
                    "ocrConcurrencyLimit",
                    if (floatingJson.has("vlTranslateConcurrency")) {
                        floatingJson.optInt(
                            "vlTranslateConcurrency",
                            SettingsStore.DEFAULT_FLOATING_OCR_CONCURRENCY
                        )
                    } else {
                        SettingsStore.DEFAULT_FLOATING_OCR_CONCURRENCY
                    }
                ).coerceIn(
                    SettingsStore.MIN_FLOATING_OCR_CONCURRENCY,
                    SettingsStore.MAX_FLOATING_OCR_CONCURRENCY
                ),
                aiApiConcurrencyLimit = floatingJson.optInt(
                    "aiApiConcurrencyLimit",
                    floatingJson.optInt(
                        "vlTranslateConcurrency",
                        SettingsStore.DEFAULT_FLOATING_AI_API_CONCURRENCY
                    )
                ).coerceIn(
                    SettingsStore.MIN_FLOATING_AI_API_CONCURRENCY,
                    SettingsStore.MAX_FLOATING_AI_API_CONCURRENCY
                ),
                proofreadingModeEnabled = floatingJson.optBoolean(
                    "proofreadingModeEnabled",
                    false
                ),
                autoCloseOnScreenChangeEnabled = floatingJson.optBoolean(
                    "autoCloseOnScreenChangeEnabled",
                    false
                ),
                detectionTopInsetPercent = floatingJson.optInt(
                    "detectionTopInsetPercent",
                    0
                ).coerceIn(0, 90),
                detectionBottomInsetPercent = floatingJson.optInt(
                    "detectionBottomInsetPercent",
                    0
                ).coerceIn(
                    0,
                    90 - floatingJson.optInt("detectionTopInsetPercent", 0).coerceIn(0, 90)
                ),
                singleTapAction = FloatingBallGestureAction.fromPref(
                    floatingJson.optStringOrNull("singleTapAction"),
                    SettingsStore.DEFAULT_FLOATING_SINGLE_TAP_ACTION
                ),
                doubleTapAction = FloatingBallGestureAction.fromPref(
                    floatingJson.optStringOrNull("doubleTapAction"),
                    SettingsStore.DEFAULT_FLOATING_DOUBLE_TAP_ACTION
                ),
                longPressAction = FloatingBallGestureAction.fromPref(
                    floatingJson.optStringOrNull("longPressAction"),
                    SettingsStore.DEFAULT_FLOATING_LONG_PRESS_ACTION
                ),
                tripleTapAction = FloatingBallGestureAction.fromPref(
                    floatingJson.optStringOrNull("tripleTapAction"),
                    SettingsStore.DEFAULT_FLOATING_TRIPLE_TAP_ACTION
                )
            ),
            llmParameters = LlmParameterSettings(
                temperature = llmJson.optOptionalDouble("temperature"),
                topP = llmJson.optOptionalDouble("topP"),
                topK = llmJson.optOptionalInt("topK"),
                maxOutputTokens = llmJson.optOptionalInt("maxOutputTokens"),
                enableThinking = llmJson.optBoolean(
                    "enableThinking",
                    SettingsStore.DEFAULT_LLM_ENABLE_THINKING
                ),
                thinkingLength = parseThinkingLength(llmJson),
                frequencyPenalty = llmJson.optOptionalDouble("frequencyPenalty"),
                presencePenalty = llmJson.optOptionalDouble("presencePenalty")
            ),
            customRequestParameters = buildList {
                for (index in 0 until customParams.length()) {
                    val param = customParams.optJSONObject(index) ?: continue
                    val key = param.optString("key").trim()
                    val value = param.optString("value")
                    if (key.isBlank() && value.isBlank()) continue
                    add(
                        CustomRequestParameter(
                            key = key,
                            value = value,
                            enabled = param.optBoolean("enabled", true)
                        )
                    )
                }
            }
        )
    }

    private fun parseThinkingLength(llmJson: JSONObject): ThinkingLength {
        val stored = llmJson.optStringOrNull("thinkingLength")
        if (!stored.isNullOrBlank()) {
            return ThinkingLength.fromPref(stored)
        }
        return ThinkingLength.fromLegacyBudget(llmJson.optOptionalInt("thinkingBudget"))
    }

}
