package com.manga.translate.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.manga.translate.R
import com.manga.translate.platform.AppLogger
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal class SettingsStoreStorage(context: Context) {
    val appContext = context.applicationContext
    val prefs = appContext.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
    val aiProviderProfilesFile = File(appContext.filesDir, SettingsStore.AI_PROVIDER_PROFILES_FILE_NAME)
    val settingsObserver = getObservationHub(prefs)

    fun editSettings(
        changedKeys: Set<String>,
        operation: SharedPreferences.Editor.() -> Unit
    ) {
        prefs.edit(action = operation)
        settingsObserver.publish(changedKeys)
    }

    fun notifyImportedSettings() {
        settingsObserver.publish(prefs.all.keys + SettingsStore.KEY_AI_PROVIDER_PROFILES_STATE)
    }

    fun parseVersionedArrayPayload(
        raw: String,
        arrayKey: String,
        label: String
    ): JSONArray {
        val normalized = raw.trimStart()
        if (normalized.startsWith("[")) {
            return JSONArray(raw)
        }
        val root = JSONObject(raw)
        val version = when {
            !root.has("version") -> SettingsStore.LEGACY_SETTINGS_JSON_VERSION
            else -> root.optInt("version", SettingsStore.LEGACY_SETTINGS_JSON_VERSION)
        }
        if (version !in SettingsStore.LEGACY_SETTINGS_JSON_VERSION..SettingsStore.SETTINGS_JSON_SCHEMA_VERSION) {
            AppLogger.log("SettingsStore", "Skip $label for unsupported version=$version")
            return JSONArray()
        }
        return root.optJSONArray(arrayKey) ?: JSONArray()
    }

    fun readDoubleWithDefault(key: String, defaultValue: Double): Double? {
        if (!prefs.contains(key)) return defaultValue
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toDoubleOrNull()
    }

    fun readDoubleOptional(key: String): Double? {
        if (!prefs.contains(key)) return null
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toDoubleOrNull()
    }

    fun readIntOptional(key: String): Int? {
        if (!prefs.contains(key)) return null
        val value = prefs.getString(key, null)
        if (value.isNullOrBlank()) return null
        return value.toIntOrNull()
    }
}

internal class PreferenceObservationHub {
    private val _version = MutableStateFlow(0L)
    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 32)

    val version: StateFlow<Long> = _version.asStateFlow()
    val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    fun publish(keys: Set<String>) {
        if (keys.isEmpty()) return
        _version.value = _version.value + 1L
        _changes.tryEmit(keys)
    }
}

@Volatile
private var sharedObservationHub: PreferenceObservationHub? = null

private fun getObservationHub(@Suppress("UNUSED_PARAMETER") prefs: SharedPreferences): PreferenceObservationHub {
    return sharedObservationHub ?: synchronized(SettingsStoreStorage::class.java) {
        sharedObservationHub ?: PreferenceObservationHub().also {
            sharedObservationHub = it
        }
    }
}

private fun SharedPreferences.Editor.putOptionalString(
    key: String,
    value: Number?
): SharedPreferences.Editor {
    putString(key, value?.toString().orEmpty())
    return this
}

internal fun SharedPreferences.Editor.putOptionalNumber(
    key: String,
    value: Number?
): SharedPreferences.Editor {
    return putOptionalString(key, value)
}

internal fun JSONObject.optOptionalInt(key: String): Int? {
    if (isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }
}

internal fun JSONObject.optOptionalDouble(key: String): Double? {
    if (isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}
