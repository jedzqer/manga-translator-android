package com.manga.translate

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import com.manga.translate.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RetroThemeTest {
    @Test
    fun `retro theme exposes the Windows 95 palette`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_MangaTranslator_Retro
        )

        assertEquals(0xFF008080.toInt(), resolve(context, android.R.attr.colorBackground))
        assertEquals(0xFFC0C0C0.toInt(), resolve(context, R.attr.surfaceColor))
        assertEquals(0xFFD4D0C8.toInt(), resolve(context, R.attr.surfaceAltColor))
        assertEquals(0xFF000080.toInt(), resolve(context, R.attr.heroStartColor))
        assertEquals(0xFFFFFFFF.toInt(), resolve(context, R.attr.heroTextColor))
        assertEquals(0xFF000000.toInt(), resolve(context, R.attr.outlineColor))
        assertEquals(0xFFC0C0C0.toInt(), resolve(context, R.attr.buttonFillColor))
        assertEquals(0xFF808080.toInt(), resolve(context, R.attr.buttonFillPressedColor))
        assertEquals(0xFF000080.toInt(), resolve(context, android.R.attr.statusBarColor))
    }

    @Test
    fun `retro palette keeps text readable on silver surfaces`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_MangaTranslator_Retro
        )
        val surface = resolve(context, R.attr.surfaceColor)
        val foreground = resolve(context, R.attr.dialogTextColor)
        val muted = resolve(context, R.attr.mutedTextColor)
        val buttonFill = resolve(context, R.attr.buttonFillColor)
        val buttonText = resolve(context, R.attr.buttonTextColor)
        val heroStart = resolve(context, R.attr.heroStartColor)
        val heroText = resolve(context, R.attr.heroTextColor)

        assertTrue(contrast(foreground, surface) >= 4.5)
        assertTrue(contrast(muted, surface) >= 4.5)
        assertTrue(contrast(buttonText, buttonFill) >= 4.5)
        assertTrue(contrast(heroText, heroStart) >= 4.5)
    }

    @Test
    fun `retro preference round trips through the theme enum`() {
        assertEquals(ThemeMode.RETRO, ThemeMode.fromPref("retro"))
        assertSame(ThemeMode.FOLLOW_SYSTEM, ThemeMode.fromPref("unknown"))
    }

    private fun resolve(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    private fun contrast(a: Int, b: Int): Double =
        ColorUtils.calculateContrast(a, b)
}
