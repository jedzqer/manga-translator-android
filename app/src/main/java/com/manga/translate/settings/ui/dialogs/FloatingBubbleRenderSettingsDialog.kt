package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.manga.translate.R
import com.manga.translate.databinding.DialogFloatingBubbleRenderSettingsBinding
import com.manga.translate.settings.FloatingBubbleRenderSettings
import com.manga.translate.settings.FloatingBubbleShape
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Floating bubble render settings editing dialog.
 */
internal class FloatingBubbleRenderSettingsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val currentSettings = settingsStore.loadFloatingBubbleRenderSettings()
        val dialogBinding = DialogFloatingBubbleRenderSettingsBinding.inflate(fragment.layoutInflater)
        dialogBinding.floatingBubbleSizeAdjustPercentInput.setText(
            fragment.formatNumber(currentSettings.sizeAdjustPercent)
        )
        dialogBinding.floatingBubbleOpacityPercentInput.setText(
            fragment.formatNumber(currentSettings.opacityPercent)
        )
        setupFloatingBubbleShapeDropdown(
            dialogBinding.floatingBubbleShapeInput,
            currentSettings.shape
        )
        dialogBinding.floatingBubbleVerticalTextSwitch.isChecked = !currentSettings.useHorizontalText
        dialogBinding.floatingBubbleAutoAdaptColorSwitch.isChecked = currentSettings.autoAdaptBubbleColor
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.floating_bubble_render_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = FloatingBubbleRenderSettings(
                    sizeAdjustPercent = fragment.parseIntInput(
                        dialogBinding.floatingBubbleSizeAdjustPercentInput.text?.toString()
                    ) ?: currentSettings.sizeAdjustPercent,
                    opacityPercent = fragment.parseIntInput(
                        dialogBinding.floatingBubbleOpacityPercentInput.text?.toString()
                    ) ?: currentSettings.opacityPercent,
                    shape = parseFloatingBubbleShape(
                        dialogBinding.floatingBubbleShapeInput,
                        currentSettings.shape
                    ),
                    useHorizontalText = !dialogBinding.floatingBubbleVerticalTextSwitch.isChecked,
                    autoAdaptBubbleColor = dialogBinding.floatingBubbleAutoAdaptColorSwitch.isChecked
                )
                settingsStore.saveFloatingBubbleRenderSettings(updated)
                fragment.updateFloatingBubbleRenderSettingsButton()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupFloatingBubbleShapeDropdown(
        inputView: MaterialAutoCompleteTextView,
        currentShape: FloatingBubbleShape
    ) {
        val shapes = FloatingBubbleShape.entries
        val labels = shapes.map { fragment.getString(it.labelRes) }
        val textColor = fragment.resolveColorAttr(R.attr.dialogTextColor)
        inputView.setAdapter(
            object : ArrayAdapter<String>(
                fragment.requireContext(),
                android.R.layout.simple_list_item_1,
                labels
            ) {
                private fun applyThemeTextColor(view: View): View {
                    (view as? TextView)?.setTextColor(textColor)
                    return view
                }

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return applyThemeTextColor(super.getView(position, convertView, parent))
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    return applyThemeTextColor(super.getDropDownView(position, convertView, parent))
                }
            }
        )
        inputView.threshold = 0
        inputView.setOnClickListener { inputView.showDropDown() }
        inputView.setText(fragment.getString(currentShape.labelRes), false)
    }

    private fun parseFloatingBubbleShape(
        inputView: MaterialAutoCompleteTextView,
        defaultShape: FloatingBubbleShape
    ): FloatingBubbleShape {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        if (selectedLabel.isBlank()) return defaultShape
        return FloatingBubbleShape.entries.firstOrNull {
            fragment.getString(it.labelRes) == selectedLabel
        } ?: defaultShape
    }
}
