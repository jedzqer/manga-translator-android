package com.manga.translate.settings.ui.dialogs

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogModelContextBinding
import com.manga.translate.settings.ModelContextDefaults
import com.manga.translate.settings.ModelContextLimits
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

internal class ModelContextDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val current = settingsStore.loadModelContextSettings()
        if (current.identity.endpoint.isBlank() || current.identity.modelName.isBlank()) {
            Toast.makeText(fragment.requireContext(), R.string.model_context_missing_identity, Toast.LENGTH_SHORT).show()
            return
        }
        val binding = DialogModelContextBinding.inflate(fragment.layoutInflater)
        binding.modelContextScope.text = fragment.getString(
            R.string.model_context_scope,
            current.profileName ?: fragment.getString(R.string.model_context_unsaved),
            current.identity.modelName, current.identity.endpoint
        )
        val fields = listOf(
            binding.contextInputLayout to binding.contextInputInput,
            binding.contextOutputLayout to binding.contextOutputInput,
            binding.contextTotalLayout to binding.contextTotalInput
        )
        fields.zip(listOf(ModelContextDefaults.MAX_INPUT_TOKENS, ModelContextDefaults.MAX_OUTPUT_TOKENS,
            ModelContextDefaults.CONTEXT_WINDOW_TOKENS)).forEach { (field, fallback) ->
            field.first.hint = fragment.getString(R.string.model_context_unknown, fallback)
        }
        fields.zip(listOf(current.limits.maxInputTokens, current.limits.maxOutputTokens, current.limits.contextWindowTokens))
            .forEach { (field, value) -> field.second.setText(value?.toString().orEmpty()) }
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.model_context_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.model_context_clear, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                fields.forEach { (layout, input) -> input.text?.clear(); layout.error = null }
                binding.modelContextError.text = ""
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var valid = true
                val values = fields.map { (layout, input) ->
                    val raw = input.text?.toString()?.trim().orEmpty()
                    val value = raw.toIntOrNull()?.takeIf { it > 0 }
                    val invalid = raw.isNotEmpty() && (value == null || raw.any { !it.isDigit() })
                    layout.error = if (invalid) fragment.getString(R.string.model_context_invalid) else null
                    if (invalid) valid = false
                    value
                }
                if (valid) {
                    val updated = current.copy(limits = ModelContextLimits(values[0], values[1], values[2]))
                    if (settingsStore.saveModelContextSettings(updated)) dialog.dismiss()
                    else binding.modelContextError.setText(R.string.model_context_save_failed)
                }
            }
        }
        dialog.show()
    }
}
