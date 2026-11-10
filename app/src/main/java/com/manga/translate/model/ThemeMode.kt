package com.manga.translate.model

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.manga.translate.R

enum class ThemeMode(
    val prefValue: String,
    val nightMode: Int,
    @param:StringRes val labelRes: Int
) {
    FOLLOW_SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_follow_system),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
    PASTEL("pastel", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_pastel),
    DEEP_SEA("deep_sea", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_deep_sea),
    RETRO("retro", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_retro),
    CUSTOM("custom", AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_custom);

    companion object {
        fun fromPref(value: String?): ThemeMode {
            return entries.firstOrNull { it.prefValue == value } ?: FOLLOW_SYSTEM
        }
    }
}
