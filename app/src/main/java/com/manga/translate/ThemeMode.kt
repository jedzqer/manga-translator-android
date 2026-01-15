package com.manga.translate

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode(
    val prefValue: String,
    val nightMode: Int,
    @StringRes val labelRes: Int
) {
    FOLLOW_SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_follow_system),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
    PINK("pink", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_pink);

    companion object {
        fun fromPref(value: String?): ThemeMode {
            return entries.firstOrNull { it.prefValue == value } ?: FOLLOW_SYSTEM
        }
    }
}
