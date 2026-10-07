package com.manga.translate.theming

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.manga.translate.settings.CustomThemeColors

data class ThemePalette(
    val background: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val accent: Int,
    val accentContent: Int,
    val foreground: Int,
    val mutedForeground: Int,
    val outline: Int,
    val buttonFill: Int,
    val buttonPressed: Int,
    val buttonText: Int,
    val heroStart: Int,
    val heroEnd: Int
) {
    val isDark: Boolean = ColorUtils.calculateLuminance(surface) < 0.42
    val lightStatusBar: Boolean = ColorUtils.calculateLuminance(heroStart) > 0.5

    /** Text drawn on the solid selected main tab background. */
    val heroText: Int = readableOn(heroStart)

    companion object {
        fun from(colors: CustomThemeColors): ThemePalette {
            return ThemePalette(
                background = opaque(colors.background),
                surface = opaque(colors.surface),
                surfaceAlt = opaque(colors.surfaceAlt),
                accent = opaque(colors.accent),
                accentContent = opaque(colors.accentContent),
                foreground = opaque(colors.foreground),
                mutedForeground = opaque(colors.mutedForeground),
                outline = opaque(colors.outline),
                buttonFill = opaque(colors.buttonFill),
                buttonPressed = opaque(colors.buttonPressed),
                buttonText = opaque(colors.buttonText),
                heroStart = opaque(colors.heroStart),
                heroEnd = opaque(colors.heroEnd)
            )
        }

        private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

        private fun readableOn(background: Int): Int {
            val dark = Color.rgb(20, 24, 32)
            val light = Color.rgb(255, 250, 240)
            val darkContrast = ColorUtils.calculateContrast(dark, background)
            val lightContrast = ColorUtils.calculateContrast(light, background)
            return if (darkContrast >= lightContrast) dark else light
        }
    }
}

object ThemePaletteRuntime {
    @Volatile
    var customPalette: ThemePalette? = null
        private set

    fun activate(colors: CustomThemeColors): ThemePalette {
        return ThemePalette.from(colors).also { customPalette = it }
    }

    fun clear() {
        customPalette = null
    }
}
