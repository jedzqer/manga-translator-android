package com.manga.translate.settings.ui.dialogs

import android.widget.Toast
import com.manga.translate.R
import com.manga.translate.model.ThinkingLength
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Thinking length selection dialog.
 */
internal class ThinkingLengthDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val current = settingsStore.loadLlmParameters()
        if (current.enableThinking != true) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.thinking_length_requires_enable,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val options = ThinkingLength.entries
        showSingleChoiceSettingDialog(
            context = fragment.requireContext(),
            titleRes = R.string.thinking_length_title,
            options = options,
            current = current.thinkingLength,
            labelRes = { it.labelRes }
        ) { dialog, length ->
            val latest = settingsStore.loadLlmParameters()
            settingsStore.saveLlmParameters(latest.copy(thinkingLength = length))
            fragment.updateThinkingLengthButton()
            AppLogger.log("Settings", "thinking_length set to ${length.prefValue}")
            dialog.dismiss()
        }
    }
}
