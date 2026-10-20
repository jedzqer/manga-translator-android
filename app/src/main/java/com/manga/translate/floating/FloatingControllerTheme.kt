package com.manga.translate.floating

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import com.manga.translate.R
import com.manga.translate.model.ThemeMode
import com.manga.translate.settings.SettingsStore
import com.manga.translate.theming.ThemePaletteRuntime

internal fun createFloatingControllerContext(context: Context, settings: SettingsStore): Context {
    val mode = settings.loadThemeMode()
    val configuration = Configuration(context.resources.configuration)
    val night = when (mode) {
        ThemeMode.FOLLOW_SYSTEM -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        else -> Configuration.UI_MODE_NIGHT_NO
    }
    configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    val theme = when (mode) {
        ThemeMode.PASTEL -> R.style.Theme_MangaTranslator_Pastel
        ThemeMode.DEEP_SEA -> R.style.Theme_MangaTranslator_DeepSea
        ThemeMode.CUSTOM -> if (ThemePaletteRuntime.activate(settings.loadCustomThemeColors()).isDark) {
            R.style.Theme_MangaTranslator_Custom_Dark
        } else {
            R.style.Theme_MangaTranslator_Custom_Light
        }
        else -> R.style.Theme_MangaTranslator
    }
    return ContextThemeWrapper(context.createConfigurationContext(configuration), theme)
}

internal fun resolveFloatingControllerColor(context: Context, attr: Int): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(attr, value, true)
    return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
}

internal fun floatingControllerButtonTextColor(context: Context, settings: SettingsStore): Int =
    if (settings.loadThemeMode() == ThemeMode.CUSTOM) {
        settings.loadCustomThemeColors().buttonText
    } else {
        resolveFloatingControllerColor(context, R.attr.buttonTextColor)
    }
