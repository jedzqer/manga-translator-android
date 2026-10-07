package com.manga.translate.settings.ui.dialogs

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.GridLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.ViewCompat
import com.manga.translate.R
import com.manga.translate.databinding.DialogCustomThemeBinding
import com.manga.translate.databinding.DialogThemeSettingsBinding
import com.manga.translate.model.ThemeMode
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.CustomThemeColors
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import kotlin.math.roundToInt

/**
 * Theme selection dialog and the custom theme color editor.
 */
internal class ThemeDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val dialogBinding = DialogThemeSettingsBinding.inflate(fragment.layoutInflater)
        val modeButtons = linkedMapOf(
            ThemeMode.FOLLOW_SYSTEM to dialogBinding.themeFollowSystemRadio,
            ThemeMode.DARK to dialogBinding.themeDarkRadio,
            ThemeMode.LIGHT to dialogBinding.themeLightRadio,
            ThemeMode.PASTEL to dialogBinding.themePastelRadio,
            ThemeMode.DEEP_SEA to dialogBinding.themeDeepSeaRadio,
            ThemeMode.CUSTOM to dialogBinding.themeCustomRadio
        )
        modeButtons[settingsStore.loadThemeMode()]?.isChecked = true
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.theme_setting_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        modeButtons.forEach { (mode, button) ->
            button.setOnClickListener {
                modeButtons.values.forEach { it.isChecked = it === button }
                selectThemeMode(mode)
                dialog.dismiss()
            }
        }
        dialogBinding.customThemeColorsButton.setOnClickListener {
            dialog.dismiss()
            showCustomTheme()
        }
        dialog.show()
    }

    private fun selectThemeMode(selected: ThemeMode) {
        settingsStore.saveThemeMode(selected)
        fragment.updateThemeButton(selected)
        applyThemeSelection(selected)
        AppLogger.log("Settings", "Theme set to ${selected.prefValue}")
    }

    private fun applyThemeSelection(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        fragment.activity?.recreate()
    }

    fun showCustomTheme() {
        val dialogBinding = DialogCustomThemeBinding.inflate(fragment.layoutInflater)
        var colors = settingsStore.loadCustomThemeColors()
        var selectedTarget = CustomThemeColorTarget.BACKGROUND
        var updatingSliders = false
        lateinit var refreshEditor: (Boolean) -> Unit

        fun selectedColor(): Int = selectedTarget.read(colors)

        fun refreshTargetGrid() {
            val density = fragment.resources.displayMetrics.density
            val swatchHeight = (44f * density).roundToInt()
            val margin = (4f * density).roundToInt()
            val selectedStroke = (3f * density).roundToInt()
            val normalStroke = density.coerceAtLeast(1f).roundToInt()
            val selectedOutline = fragment.resolveColorAttr(R.attr.dialogTextColor)
            val normalOutline = fragment.resolveColorAttr(R.attr.outlineColor)
            dialogBinding.colorTargetGrid.removeAllViews()
            dialogBinding.colorTargetGrid.columnCount = 3
            CustomThemeColorTarget.entries
                .forEach { target ->
                    val label = fragment.getString(target.labelRes)
                    val swatch = AppCompatImageButton(fragment.requireContext()).apply {
                        contentDescription = label
                        setPadding(0, 0, 0, 0)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 8f * density
                            setColor(target.read(colors))
                            setStroke(
                                if (target == selectedTarget) selectedStroke else normalStroke,
                                if (target == selectedTarget) selectedOutline else normalOutline
                            )
                        }
                        setOnClickListener {
                            selectedTarget = target
                            refreshEditor(false)
                        }
                    }
                    ViewCompat.setTooltipText(swatch, label)
                    dialogBinding.colorTargetGrid.addView(
                        swatch,
                        GridLayout.LayoutParams().apply {
                            width = 0
                            height = swatchHeight
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            setMargins(margin, margin, margin, margin)
                        }
                    )
                }
        }

        refreshEditor = { preserveSliderHsv ->
            val hsv = if (preserveSliderHsv) {
                floatArrayOf(
                    dialogBinding.hueSlider.value * 360f,
                    dialogBinding.saturationSlider.value,
                    dialogBinding.brightnessSlider.value
                )
            } else {
                FloatArray(3).also { Color.colorToHSV(selectedColor(), it) }
            }
            updatingSliders = true
            dialogBinding.hueSlider.value = hsv[0] / 360f
            dialogBinding.saturationSlider.value = hsv[1]
            dialogBinding.brightnessSlider.value = hsv[2]
            dialogBinding.hueSlider.setGradient(
                intArrayOf(0, 60, 120, 180, 240, 300, 360).map { hue ->
                    Color.HSVToColor(floatArrayOf(hue.toFloat(), 1f, 1f))
                }.toIntArray()
            )
            dialogBinding.saturationSlider.setGradient(
                intArrayOf(
                    Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])),
                    Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
                )
            )
            dialogBinding.brightnessSlider.setGradient(
                intArrayOf(
                    Color.BLACK,
                    Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
                )
            )
            updatingSliders = false
            val color = selectedColor()
            dialogBinding.selectedColorSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = fragment.resources.displayMetrics.density * 6f
                setColor(color)
            }
            dialogBinding.selectedColorLabel.setText(selectedTarget.labelRes)
            dialogBinding.selectedColorValue.text = String.format("#%06X", color and 0xFFFFFF)
            dialogBinding.themePreview.colors = colors
            refreshTargetGrid()
        }

        fun applySliderValues() {
            if (updatingSliders) return
            colors = selectedTarget.update(
                colors,
                Color.HSVToColor(
                    floatArrayOf(
                        dialogBinding.hueSlider.value * 360f,
                        dialogBinding.saturationSlider.value,
                        dialogBinding.brightnessSlider.value
                    )
                )
            )
            refreshEditor(true)
        }

        dialogBinding.hueSlider.onValueChanged = { applySliderValues() }
        dialogBinding.saturationSlider.onValueChanged = { applySliderValues() }
        dialogBinding.brightnessSlider.onValueChanged = { applySliderValues() }
        dialogBinding.hueSlider.contentDescription = fragment.getString(R.string.custom_theme_hue)
        dialogBinding.saturationSlider.contentDescription = fragment.getString(R.string.custom_theme_saturation)
        dialogBinding.brightnessSlider.contentDescription = fragment.getString(R.string.custom_theme_brightness)
        refreshEditor(false)

        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.custom_theme_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.custom_theme_reset, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            colors = CustomThemeColors.DEFAULT
            refreshEditor(false)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            settingsStore.saveCustomThemeColors(colors)
            settingsStore.saveThemeMode(ThemeMode.CUSTOM)
            fragment.updateThemeButton(ThemeMode.CUSTOM)
            AppLogger.log(
                "Settings",
                "Custom theme saved: background=${colors.background}, " +
                    "surface=${colors.surface}, accent=${colors.accent}, " +
                    "foreground=${colors.foreground}"
            )
            dialog.dismiss()
            applyThemeSelection(ThemeMode.CUSTOM)
        }
    }

    private enum class CustomThemeColorTarget(
        @param:StringRes val labelRes: Int
    ) {
        BACKGROUND(R.string.custom_theme_background),
        SURFACE(R.string.custom_theme_surface),
        ACCENT(R.string.custom_theme_accent),
        FOREGROUND(R.string.custom_theme_foreground),
        MUTED_FOREGROUND(R.string.custom_theme_muted_foreground);

        fun read(colors: CustomThemeColors): Int = when (this) {
            BACKGROUND -> colors.background
            SURFACE -> colors.surface
            ACCENT -> colors.accent
            FOREGROUND -> colors.foreground
            MUTED_FOREGROUND -> colors.mutedForeground
        }

        fun update(colors: CustomThemeColors, color: Int): CustomThemeColors = when (this) {
            BACKGROUND -> CustomThemeColors.fromBaseColors(color, colors.surface, colors.accent, colors.foreground, colors.mutedForeground)
            SURFACE -> CustomThemeColors.fromBaseColors(colors.background, color, colors.accent, colors.foreground, colors.mutedForeground)
            ACCENT -> CustomThemeColors.fromBaseColors(colors.background, colors.surface, color, colors.foreground, colors.mutedForeground)
            FOREGROUND -> CustomThemeColors.fromBaseColors(colors.background, colors.surface, colors.accent, color, colors.mutedForeground)
            MUTED_FOREGROUND -> CustomThemeColors.fromBaseColors(colors.background, colors.surface, colors.accent, colors.foreground, color)
        }
    }
}
