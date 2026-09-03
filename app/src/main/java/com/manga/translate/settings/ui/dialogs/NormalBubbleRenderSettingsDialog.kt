package com.manga.translate.settings.ui.dialogs

import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogNormalBubbleRenderSettingsBinding
import com.manga.translate.settings.NormalBubbleRenderSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Normal bubble render settings editing dialog.
 */
internal class NormalBubbleRenderSettingsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val currentSettings = settingsStore.loadNormalBubbleRenderSettings()
        val dialogBinding = DialogNormalBubbleRenderSettingsBinding.inflate(fragment.layoutInflater)
        dialogBinding.normalBubbleShrinkPercentInput.setText(
            fragment.formatNumber(currentSettings.shrinkPercent)
        )
        dialogBinding.normalBubbleOpacityPercentInput.setText(
            fragment.formatNumber(currentSettings.opacityPercent)
        )
        dialogBinding.normalBubbleFreeShrinkPercentInput.setText(
            fragment.formatNumber(currentSettings.freeBubbleShrinkPercent)
        )
        dialogBinding.normalBubbleFreeOpacityPercentInput.setText(
            fragment.formatNumber(currentSettings.freeBubbleOpacityPercent)
        )
        dialogBinding.normalBubbleVerticalTextSwitch.isChecked = !currentSettings.useHorizontalText
        dialogBinding.normalBubbleAutoAdaptColorSwitch.isChecked = currentSettings.autoAdaptBubbleColor
        dialogBinding.normalBubbleFreeAutoAdaptColorSwitch.isChecked = currentSettings.autoAdaptFreeBubbleColor
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.normal_bubble_render_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = NormalBubbleRenderSettings(
                    shrinkPercent = fragment.parseIntInput(
                        dialogBinding.normalBubbleShrinkPercentInput.text?.toString()
                    ) ?: currentSettings.shrinkPercent,
                    opacityPercent = fragment.parseIntInput(
                        dialogBinding.normalBubbleOpacityPercentInput.text?.toString()
                    ) ?: currentSettings.opacityPercent,
                    freeBubbleShrinkPercent = fragment.parseIntInput(
                        dialogBinding.normalBubbleFreeShrinkPercentInput.text?.toString()
                    ) ?: currentSettings.freeBubbleShrinkPercent,
                    freeBubbleOpacityPercent = fragment.parseIntInput(
                        dialogBinding.normalBubbleFreeOpacityPercentInput.text?.toString()
                    ) ?: currentSettings.freeBubbleOpacityPercent,
                    useHorizontalText = !dialogBinding.normalBubbleVerticalTextSwitch.isChecked,
                    autoAdaptBubbleColor = dialogBinding.normalBubbleAutoAdaptColorSwitch.isChecked,
                    autoAdaptFreeBubbleColor = dialogBinding.normalBubbleFreeAutoAdaptColorSwitch.isChecked
                )
                settingsStore.saveNormalBubbleRenderSettings(updated)
                fragment.updateNormalBubbleRenderSettingsButton()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
