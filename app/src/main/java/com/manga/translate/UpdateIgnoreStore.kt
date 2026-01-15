package com.manga.translate

import android.content.Context

class UpdateIgnoreStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadIgnoredVersionCode(): Int {
        return prefs.getInt(KEY_IGNORED_VERSION_CODE, NO_VERSION)
    }

    fun saveIgnoredVersionCode(versionCode: Int) {
        if (versionCode <= 0) return
        prefs.edit()
            .putInt(KEY_IGNORED_VERSION_CODE, versionCode)
            .apply()
    }

    fun isIgnored(versionCode: Int): Boolean {
        if (versionCode <= 0) return false
        return loadIgnoredVersionCode() == versionCode
    }

    companion object {
        private const val PREFS_NAME = "manga_translate_update"
        private const val KEY_IGNORED_VERSION_CODE = "ignored_version_code"
        private const val NO_VERSION = -1
    }
}
