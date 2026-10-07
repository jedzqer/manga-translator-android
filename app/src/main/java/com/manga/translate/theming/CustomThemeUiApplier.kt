package com.manga.translate.theming

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.manga.translate.R

object CustomThemeUiApplier {
    fun applyToActivity(activity: ComponentActivity) {
        val palette = ThemePaletteRuntime.customPalette ?: return
        activity.enableEdgeToEdge(
            statusBarStyle = if (palette.lightStatusBar) {
                SystemBarStyle.light(palette.heroStart, palette.heroStart)
            } else {
                SystemBarStyle.dark(palette.heroStart)
            },
            navigationBarStyle = if (palette.isDark) {
                SystemBarStyle.dark(palette.background)
            } else {
                SystemBarStyle.light(palette.background, palette.background)
            }
        )
        activity.window.decorView.setBackgroundColor(palette.background)
        apply(activity.window.decorView, palette, SemanticColors(activity))
    }

    fun apply(root: View) {
        val palette = ThemePaletteRuntime.customPalette ?: return
        apply(root, palette, SemanticColors(root.context))
    }

    private fun apply(view: View, palette: ThemePalette, semantic: SemanticColors) {
        when (view) {
            is Button -> view.setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(palette.mutedForeground, palette.buttonText)
            ))
            is TextView -> {
                val current = view.textColors.defaultColor
                val target = when (current) {
                    semantic.accent -> palette.accentContent
                    semantic.muted, semantic.dialogHint -> palette.mutedForeground
                    semantic.buttonText -> palette.buttonText
                    semantic.dialogText -> palette.foreground
                    else -> null
                }
                if (target != null) view.setTextColor(target)
                if (view.hintTextColors.defaultColor == semantic.dialogHint) {
                    view.setHintTextColor(palette.mutedForeground)
                }
            }
        }
        when (view) {
            is TabLayout -> view.setTabTextColors(palette.mutedForeground, palette.heroText)
            is FloatingActionButton -> {
                view.backgroundTintList = ColorStateList.valueOf(palette.buttonFill)
                view.imageTintList = ColorStateList.valueOf(palette.buttonText)
            }
            is SwitchCompat -> {
                view.thumbTintList = switchThumbColors(palette)
                view.trackTintList = switchTrackColors(palette)
            }
            is CompoundButton -> view.buttonTintList = controlColors(palette)
            is SeekBar -> {
                view.thumbTintList = ColorStateList.valueOf(palette.accent)
                view.progressTintList = ColorStateList.valueOf(palette.accent)
            }
            is ProgressBar -> view.indeterminateTintList = ColorStateList.valueOf(palette.accent)
            is TextInputLayout -> {
                view.boxStrokeColor = palette.accentContent
                view.defaultHintTextColor = ColorStateList.valueOf(palette.mutedForeground)
                view.hintTextColor = ColorStateList.valueOf(palette.accentContent)
            }
            is ImageView -> recolorImageTint(view, semantic, palette)
        }
        recolorBackground(view, palette, semantic)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) apply(view.getChildAt(index), palette, semantic)
        }
    }

    private fun recolorBackground(view: View, palette: ThemePalette, semantic: SemanticColors) {
        val background = view.background?.mutate() ?: return
        if (background is ColorDrawable) {
            replacementFor(background.color, palette, semantic)?.let(view::setBackgroundColor)
            return
        }
        recolorDrawable(background, palette, semantic, view.resources.displayMetrics.density)
    }

    private fun recolorDrawable(
        drawable: Drawable,
        palette: ThemePalette,
        semantic: SemanticColors,
        density: Float
    ) {
        when (drawable) {
            is GradientDrawable -> {
                val gradientColors = drawable.colors
                if (gradientColors != null) {
                    drawable.colors = gradientColors.map { color ->
                        replacementFor(color, palette, semantic) ?: color
                    }.toIntArray()
                } else {
                    val original = drawable.color?.defaultColor
                    val replacement = original?.let { replacementFor(it, palette, semantic) }
                    if (replacement != null) {
                        drawable.setColor(replacement)
                        if (original == semantic.surface ||
                            original == semantic.surfaceAlt ||
                            original == semantic.buttonFill ||
                            original == semantic.buttonPressed
                        ) {
                            drawable.setStroke(density.coerceAtLeast(1f).toInt(), palette.outline)
                        }
                    }
                }
            }
            is LayerDrawable -> {
                repeat(drawable.numberOfLayers) { index ->
                    val child = drawable.getDrawable(index)
                    if (drawable.getId(index) == R.id.card_accent_strip && child is GradientDrawable) {
                        // Keep decorative strips softer than text, even when the chosen accent
                        // matches the card. Identify the layer instead of matching shared colors.
                        val accent = if (ColorUtils.calculateContrast(palette.accent, palette.surfaceAlt) >= 1.5) {
                            palette.accent
                        } else {
                            palette.foreground
                        }
                        child.setColor(ColorUtils.blendARGB(palette.surfaceAlt, accent, 0.35f))
                    } else {
                        recolorDrawable(child, palette, semantic, density)
                    }
                }
            }
            is DrawableWrapper -> drawable.drawable?.let { child ->
                recolorDrawable(child, palette, semantic, density)
            }
            is DrawableContainer -> {
                val state = drawable.constantState as? DrawableContainer.DrawableContainerState
                state?.children?.filterNotNull()?.forEach { child ->
                    recolorDrawable(child, palette, semantic, density)
                }
            }
        }
    }

    private fun replacementFor(
        color: Int,
        palette: ThemePalette,
        semantic: SemanticColors
    ): Int? = when (color) {
        semantic.background -> palette.background
        semantic.surface -> palette.surface
        semantic.surfaceAlt -> palette.surfaceAlt
        semantic.accent -> palette.accentContent
        semantic.outline -> palette.outline
        semantic.buttonFill -> palette.buttonFill
        semantic.buttonPressed -> palette.buttonPressed
        semantic.heroStart -> palette.heroStart
        semantic.heroEnd -> palette.heroEnd
        else -> null
    }

    private fun recolorImageTint(view: ImageView, semantic: SemanticColors, palette: ThemePalette) {
        val tint = view.imageTintList ?: return
        val color = tint.defaultColor
        val replacement = when (color) {
            semantic.accent, semantic.dialogText -> palette.accentContent
            semantic.muted, semantic.dialogHint -> palette.mutedForeground
            else -> null
        }
        if (replacement != null) view.imageTintList = ColorStateList.valueOf(replacement)
    }

    private fun controlColors(palette: ThemePalette) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(palette.accent, palette.mutedForeground)
    )

    private fun switchThumbColors(palette: ThemePalette) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(palette.accent, palette.mutedForeground)
    )

    private fun switchTrackColors(palette: ThemePalette) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(withAlpha(palette.accent, 0x66), withAlpha(palette.mutedForeground, 0x4D))
    )

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private class SemanticColors(context: Context) {
        val accent = resolve(context, androidx.appcompat.R.attr.colorAccent)
        val background = resolve(context, android.R.attr.colorBackground)
        val muted = resolve(context, R.attr.mutedTextColor)
        val buttonText = resolve(context, R.attr.buttonTextColor)
        val dialogText = resolve(context, R.attr.dialogTextColor)
        val dialogHint = resolve(context, R.attr.dialogHintTextColor)
        val outline = resolve(context, R.attr.outlineColor)
        val surface = resolve(context, R.attr.surfaceColor)
        val surfaceAlt = resolve(context, R.attr.surfaceAltColor)
        val buttonFill = resolve(context, R.attr.buttonFillColor)
        val buttonPressed = resolve(context, R.attr.buttonFillPressedColor)
        val heroStart = resolve(context, R.attr.heroStartColor)
        val heroEnd = resolve(context, R.attr.heroEndColor)

        private fun resolve(context: Context, attr: Int): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(attr, value, true)
            return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        }
    }
}
