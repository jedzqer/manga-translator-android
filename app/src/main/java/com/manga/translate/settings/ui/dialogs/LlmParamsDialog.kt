package com.manga.translate.settings.ui.dialogs

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogLlmParamsBinding
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.LlmParameterSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * LLM parameter editing dialog.
 */
internal class LlmParamsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val currentParams = settingsStore.loadLlmParameters()
        val dialogBinding = DialogLlmParamsBinding.inflate(fragment.layoutInflater)
        dialogBinding.temperatureInput.setText(fragment.formatNumberOrEmpty(currentParams.temperature))
        dialogBinding.topPInput.setText(fragment.formatNumberOrEmpty(currentParams.topP))
        dialogBinding.topKInput.setText(fragment.formatNumberOrEmpty(currentParams.topK))
        dialogBinding.maxOutputTokensInput.setText(fragment.formatNumberOrEmpty(currentParams.maxOutputTokens))
        dialogBinding.frequencyPenaltyInput.setText(fragment.formatNumberOrEmpty(currentParams.frequencyPenalty))
        dialogBinding.presencePenaltyInput.setText(fragment.formatNumberOrEmpty(currentParams.presencePenalty))
        dialogBinding.llmParamsNote.setText(R.string.llm_params_note)
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.llm_params_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val parsed = parseLlmParams(dialogBinding)
                settingsStore.saveLlmParameters(parsed.params)
                fragment.fragmentBinding.enableThinkingSwitch.isChecked = parsed.params.enableThinking
                fragment.updateThinkingLengthButton()
                if (parsed.hasInvalid) {
                    Toast.makeText(
                        fragment.requireContext(),
                        R.string.llm_params_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                AppLogger.log("Settings", "LLM params updated")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseLlmParams(
        dialogBinding: DialogLlmParamsBinding
    ): ParsedLlmParams {
        var hasInvalid = false
        fun parseDouble(text: String?): Double? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            return fragment.parseDoubleInput(trimmed).also { if (it == null) hasInvalid = true }
        }
        fun parseInt(text: String?): Int? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            return fragment.parseIntInput(trimmed).also { if (it == null) hasInvalid = true }
        }
        val existing = settingsStore.loadLlmParameters()
        val params = LlmParameterSettings(
            temperature = parseDouble(dialogBinding.temperatureInput.text?.toString()),
            topP = parseDouble(dialogBinding.topPInput.text?.toString()),
            topK = parseInt(dialogBinding.topKInput.text?.toString()),
            maxOutputTokens = parseInt(dialogBinding.maxOutputTokensInput.text?.toString()),
            enableThinking = fragment.fragmentBinding.enableThinkingSwitch.isChecked,
            thinkingLength = existing.thinkingLength,
            frequencyPenalty = parseDouble(dialogBinding.frequencyPenaltyInput.text?.toString()),
            presencePenalty = parseDouble(dialogBinding.presencePenaltyInput.text?.toString())
        )
        return ParsedLlmParams(params, hasInvalid)
    }

    private data class ParsedLlmParams(
        val params: LlmParameterSettings,
        val hasInvalid: Boolean
    )
}
