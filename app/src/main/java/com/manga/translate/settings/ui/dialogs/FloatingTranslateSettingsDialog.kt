package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.manga.translate.R
import com.manga.translate.databinding.DialogFloatingTranslateSettingsBinding
import com.manga.translate.model.FloatingBallGestureAction
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.FloatingTranslateApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Floating translate API settings editing dialog.
 */
internal class FloatingTranslateSettingsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val currentSettings = settingsStore.loadFloatingTranslateApiSettings()
        val dialogBinding = DialogFloatingTranslateSettingsBinding.inflate(fragment.layoutInflater)
        dialogBinding.floatingApiUrlInput.setText(currentSettings.apiUrl)
        dialogBinding.floatingApiKeyInput.setText(currentSettings.apiKey)
        dialogBinding.floatingModelNameInput.setText(currentSettings.modelName)
        dialogBinding.floatingApiTimeoutInput.setText(
            fragment.formatNumber(currentSettings.timeoutSeconds)
        )
        dialogBinding.floatingUseVlDirectTranslateSwitch.isChecked =
            currentSettings.useVlDirectTranslate
        dialogBinding.floatingProofreadingModeSwitch.isChecked =
            currentSettings.proofreadingModeEnabled
        dialogBinding.floatingAutoCloseOnScreenChangeSwitch.isChecked =
            currentSettings.autoCloseOnScreenChangeEnabled
        dialogBinding.floatingDetectionTopInsetInput.setText(
            fragment.formatNumber(currentSettings.detectionTopInsetPercent)
        )
        dialogBinding.floatingDetectionBottomInsetInput.setText(
            fragment.formatNumber(currentSettings.detectionBottomInsetPercent)
        )
        setupFloatingGestureActionDropdown(
            dialogBinding.floatingSingleTapActionInput,
            currentSettings.singleTapAction
        )
        setupFloatingGestureActionDropdown(
            dialogBinding.floatingDoubleTapActionInput,
            currentSettings.doubleTapAction
        )
        setupFloatingGestureActionDropdown(
            dialogBinding.floatingLongPressActionInput,
            currentSettings.longPressAction
        )
        setupFloatingGestureActionDropdown(
            dialogBinding.floatingTripleTapActionInput,
            currentSettings.tripleTapAction
        )
        dialogBinding.floatingVlTranslateConcurrencyInput.setText(
            fragment.formatNumber(currentSettings.ocrConcurrencyLimit)
        )
        dialogBinding.floatingAiApiConcurrencyInput.setText(
            fragment.formatNumber(currentSettings.aiApiConcurrencyLimit)
        )
        dialogBinding.floatingUseVlDirectTranslateSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.floating_use_vl_direct_translate_warning,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.floating_translate_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val timeoutInput =
                    dialogBinding.floatingApiTimeoutInput.text?.toString()?.trim()
                val timeoutSeconds = fragment.parseIntInput(timeoutInput)
                    ?.coerceIn(SettingsStore.MIN_FLOATING_API_TIMEOUT_SECONDS, SettingsStore.MAX_FLOATING_API_TIMEOUT_SECONDS)
                    ?: currentSettings.timeoutSeconds
                val concurrencyInput =
                    dialogBinding.floatingVlTranslateConcurrencyInput.text?.toString()?.trim()
                val ocrConcurrencyLimit = fragment.parseIntInput(concurrencyInput)
                    ?.coerceIn(1, 50)
                    ?: currentSettings.ocrConcurrencyLimit
                val aiApiConcurrencyInput =
                    dialogBinding.floatingAiApiConcurrencyInput.text?.toString()?.trim()
                val aiApiConcurrencyLimit = fragment.parseIntInput(aiApiConcurrencyInput)
                    ?.coerceIn(1, 50)
                    ?: currentSettings.aiApiConcurrencyLimit
                val detectionTopInsetPercent = fragment.parseIntInput(
                    dialogBinding.floatingDetectionTopInsetInput.text?.toString()?.trim()
                )?.coerceIn(0, 90) ?: currentSettings.detectionTopInsetPercent
                val detectionBottomInsetPercent = fragment.parseIntInput(
                    dialogBinding.floatingDetectionBottomInsetInput.text?.toString()?.trim()
                )?.coerceIn(0, 90) ?: currentSettings.detectionBottomInsetPercent
                settingsStore.saveFloatingTranslateApiSettings(
                    FloatingTranslateApiSettings(
                        apiUrl = dialogBinding.floatingApiUrlInput.text?.toString()?.trim().orEmpty(),
                        apiKey = dialogBinding.floatingApiKeyInput.text?.toString()?.trim().orEmpty(),
                        modelName = dialogBinding.floatingModelNameInput.text?.toString()?.trim().orEmpty(),
                        timeoutSeconds = timeoutSeconds,
                        useVlDirectTranslate =
                            dialogBinding.floatingUseVlDirectTranslateSwitch.isChecked,
                        ocrConcurrencyLimit = ocrConcurrencyLimit,
                        aiApiConcurrencyLimit = aiApiConcurrencyLimit,
                        proofreadingModeEnabled =
                            dialogBinding.floatingProofreadingModeSwitch.isChecked,
                        autoCloseOnScreenChangeEnabled =
                            dialogBinding.floatingAutoCloseOnScreenChangeSwitch.isChecked,
                        singleTapAction = parseFloatingGestureAction(
                            dialogBinding.floatingSingleTapActionInput,
                            currentSettings.singleTapAction
                        ),
                        doubleTapAction = parseFloatingGestureAction(
                            dialogBinding.floatingDoubleTapActionInput,
                            currentSettings.doubleTapAction
                        ),
                        longPressAction = parseFloatingGestureAction(
                            dialogBinding.floatingLongPressActionInput,
                            currentSettings.longPressAction
                        ),
                        tripleTapAction = parseFloatingGestureAction(
                            dialogBinding.floatingTripleTapActionInput,
                            currentSettings.tripleTapAction
                        ),
                        detectionTopInsetPercent = detectionTopInsetPercent,
                        detectionBottomInsetPercent = detectionBottomInsetPercent
                    )
                )
                AppLogger.log("Settings", "Floating translate API settings updated")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupFloatingGestureActionDropdown(
        inputView: MaterialAutoCompleteTextView,
        currentAction: FloatingBallGestureAction
    ) {
        val actions = FloatingBallGestureAction.entries
        val labels = actions.map { fragment.getString(it.labelRes) }
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
        inputView.setText(fragment.getString(currentAction.labelRes), false)
    }

    private fun parseFloatingGestureAction(
        inputView: MaterialAutoCompleteTextView,
        defaultAction: FloatingBallGestureAction
    ): FloatingBallGestureAction {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        if (selectedLabel.isBlank()) return defaultAction
        return FloatingBallGestureAction.entries.firstOrNull {
            fragment.getString(it.labelRes) == selectedLabel
        } ?: defaultAction
    }
}
