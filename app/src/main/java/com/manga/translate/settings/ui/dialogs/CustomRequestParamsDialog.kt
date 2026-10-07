package com.manga.translate.settings.ui.dialogs

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogCustomRequestParamsBinding
import com.manga.translate.databinding.ItemCustomRequestParamBinding
import com.manga.translate.network.LlmClient
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.CustomRequestParameter
import com.manga.translate.settings.PRIMARY_PROVIDER_ID
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Custom request parameters editing dialog.
 */
internal class CustomRequestParamsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val dialogBinding = DialogCustomRequestParamsBinding.inflate(fragment.layoutInflater)
        val existing = settingsStore.loadCustomRequestParameters()

        fun updateRowVisualState(rowBinding: ItemCustomRequestParamBinding) {
            val enabled = rowBinding.customRequestParamEnabledSwitch.isChecked
            rowBinding.customRequestParamFieldsContainer.alpha = if (enabled) 1f else 0.58f
            rowBinding.customRequestParamTitle.alpha = if (enabled) 1f else 0.72f
        }

        fun refreshRowTitles() {
            for (index in 0 until dialogBinding.customRequestParamsContainer.childCount) {
                val child = dialogBinding.customRequestParamsContainer.getChildAt(index)
                val rowBinding = ItemCustomRequestParamBinding.bind(child)
                rowBinding.customRequestParamTitle.text = fragment.getString(
                    R.string.custom_request_params_row_title,
                    index + 1
                )
            }
        }

        fun addRow(
            parameter: CustomRequestParameter = CustomRequestParameter("", "")
        ) {
            val rowBinding = ItemCustomRequestParamBinding.inflate(
                fragment.layoutInflater,
                dialogBinding.customRequestParamsContainer,
                false
            )
            rowBinding.customRequestParamEnabledSwitch.isChecked = parameter.enabled
            rowBinding.customRequestParamKeyInput.setText(parameter.key)
            rowBinding.customRequestParamValueInput.setText(parameter.value)
            rowBinding.customRequestParamEnabledSwitch.setOnCheckedChangeListener { _, _ ->
                updateRowVisualState(rowBinding)
            }
            rowBinding.customRequestParamDeleteButton.setOnClickListener {
                dialogBinding.customRequestParamsContainer.removeView(rowBinding.root)
                if (dialogBinding.customRequestParamsContainer.childCount == 0) {
                    addRow()
                } else {
                    refreshRowTitles()
                }
            }
            dialogBinding.customRequestParamsContainer.addView(rowBinding.root)
            updateRowVisualState(rowBinding)
            refreshRowTitles()
        }

        if (existing.isEmpty()) {
            addRow()
        } else {
            existing.forEach(::addRow)
        }
        dialogBinding.customRequestParamsAddButton.setOnClickListener {
            addRow()
        }

        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.custom_request_params_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parameters = collectCustomRequestParameters(dialogBinding)
                val validationError = validateCustomRequestParameters(parameters)
                if (validationError != null) {
                    Toast.makeText(fragment.requireContext(), validationError, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                settingsStore.saveCustomRequestParameters(parameters)
                fragment.updateCustomRequestParamsButton(parameters)
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.custom_request_params_saved,
                    Toast.LENGTH_SHORT
                ).show()
                AppLogger.log("Settings", "Custom request params updated")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun collectCustomRequestParameters(
        dialogBinding: DialogCustomRequestParamsBinding
    ): List<CustomRequestParameter> {
        val collected = mutableListOf<CustomRequestParameter>()
        for (index in 0 until dialogBinding.customRequestParamsContainer.childCount) {
            val child = dialogBinding.customRequestParamsContainer.getChildAt(index)
            val rowBinding = ItemCustomRequestParamBinding.bind(child)
            collected += CustomRequestParameter(
                key = rowBinding.customRequestParamKeyInput.text?.toString()?.trim().orEmpty(),
                value = rowBinding.customRequestParamValueInput.text?.toString().orEmpty(),
                enabled = rowBinding.customRequestParamEnabledSwitch.isChecked,
            )
        }
        return collected
    }

    private fun validateCustomRequestParameters(parameters: List<CustomRequestParameter>): String? {
        val activeKeys = LinkedHashSet<String>()
        parameters.forEach { parameter ->
            val key = parameter.key.trim()
            val value = parameter.value.trim()
            if (key.isBlank() && value.isBlank()) return@forEach
            if (key.isBlank()) {
                return fragment.getString(R.string.custom_request_params_empty_row_error)
            }
            if (!parameter.enabled) return@forEach
            if (!activeKeys.add(key)) {
                return fragment.getString(
                    R.string.custom_request_params_duplicate_error_scoped,
                    fragment.getString(R.string.custom_request_params_provider_primary),
                    key
                )
            }
        }
        val activeParamKeys = parameters
            .filter { it.enabled }
            .mapNotNull {
                val key = it.key.trim()
                if (key.isBlank() && it.value.trim().isBlank()) {
                    null
                } else {
                    key
                }
            }
        val conflict = activeParamKeys.firstOrNull { key ->
            key in LlmClient.reservedRequestKeys(fragment.currentApiFormat())
        }
        return if (conflict != null) {
            fragment.getString(R.string.custom_request_params_conflict_error, conflict)
        } else {
            null
        }
    }

}
