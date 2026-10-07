package com.manga.translate

import androidx.core.graphics.ColorUtils
import com.manga.translate.settings.CustomThemeColors
import com.manga.translate.theming.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemePaletteTest {
    @Test
    fun `custom theme defaults match the white theme colors`() {
        val defaults = CustomThemeColors.DEFAULT

        assertEquals(0xFFFFFFFF.toInt(), defaults.background)
        assertEquals(0xFFFFFFFF.toInt(), defaults.surface)
        assertEquals(0xFF245FC2.toInt(), defaults.accent)
        assertEquals(0xFF243247.toInt(), defaults.foreground)
        assertEquals(0xFF56677E.toInt(), defaults.mutedForeground)
        assertEquals(0xFFF0F6FF.toInt(), defaults.surfaceAlt)
        assertTrue(ColorUtils.calculateContrast(defaults.foreground, defaults.surface) >= 4.5)
    }

    @Test
    fun `secondary colors are generated from primary colors`() {
        val generated = CustomThemeColors.fromBaseColors(
            background = 0xFF101820.toInt(),
            surface = 0xFF182432.toInt(),
            accent = 0xFFF0A040.toInt(),
            foreground = 0xFFE8EEF7.toInt(),
            mutedForeground = 0xFF9AA8B8.toInt()
        )

        assertEquals(0xFF101820.toInt(), generated.background)
        assertEquals(0xFF182432.toInt(), generated.surface)
        assertEquals(0xFFF0A040.toInt(), generated.accent)
        assertEquals(0xFFE8EEF7.toInt(), generated.foreground)
        assertTrue(ColorUtils.calculateContrast(generated.foreground, generated.surface) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(generated.accentContent, generated.surface) >= 4.5)
    }

    @Test
    fun `derived content colors stay readable on light and dark surfaces`() {
        val palettes = listOf(
            ThemePalette.from(
                CustomThemeColors.fromBaseColors(
                    background = 0xFFF5F5F5.toInt(),
                    surface = 0xFFFFFFFF.toInt(),
                    accent = 0xFFFFEB3B.toInt()
                )
            ),
            ThemePalette.from(
                CustomThemeColors.fromBaseColors(
                    background = 0xFF050A10.toInt(),
                    surface = 0xFF101820.toInt(),
                    accent = 0xFF18212A.toInt()
                )
            )
        )

        palettes.forEach { palette ->
            assertTrue(ColorUtils.calculateContrast(palette.foreground, palette.surface) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(palette.accentContent, palette.surface) >= 4.5)
        }
    }

    @Test
    fun `stored theme colors are forced opaque`() {
        val transparent = 0x00112233
        val palette = ThemePalette.from(
            CustomThemeColors.DEFAULT.copy(
                background = transparent,
                surface = transparent,
                surfaceAlt = transparent,
                accent = transparent,
                accentContent = transparent,
                foreground = transparent,
                mutedForeground = transparent,
                outline = transparent,
                buttonFill = transparent,
                buttonPressed = transparent,
                buttonText = transparent,
                heroStart = transparent,
                heroEnd = transparent
            )
        )

        palette.toList().forEach { color -> assertEquals(0xFF, color ushr 24) }
    }

    private fun ThemePalette.toList(): List<Int> = listOf(
        background,
        surface,
        surfaceAlt,
        accent,
        accentContent,
        foreground,
        mutedForeground,
        outline,
        buttonFill,
        buttonPressed,
        buttonText,
        heroStart,
        heroEnd
    )
}
