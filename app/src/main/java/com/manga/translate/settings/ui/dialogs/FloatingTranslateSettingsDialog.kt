package com.manga.translate.settings.ui.dialogs

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.manga.translate.R
import com.manga.translate.databinding.DialogFloatingTranslateSettingsBinding
import com.manga.translate.model.FloatingBallGestureAction
import com.manga.translate.model.FloatingOverlayMode
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
        setupOverlayModeSection(dialogBinding, currentSettings.overlayMode)
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
        val dialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.floating_translate_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
            val newSettings = FloatingTranslateApiSettings(
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
                detectionBottomInsetPercent = detectionBottomInsetPercent,
                overlayMode = parseFloatingOverlayMode(
                    dialogBinding.floatingOverlayModeInput,
                    currentSettings.overlayMode
                )
            )
            if (!FloatingOverlayMode.isAllowed(newSettings.overlayMode, newSettings.gestureActions)) {
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.floating_overlay_mode_requires_clear_screen,
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            settingsStore.saveFloatingTranslateApiSettings(newSettings)
            AppLogger.log("Settings", "Floating translate API settings updated")
            dialog.dismiss()
        }
    }

    private fun setupFloatingGestureActionDropdown(
        inputView: MaterialAutoCompleteTextView,
        currentAction: FloatingBallGestureAction
    ) {
        setupLabelDropdown(
            inputView,
            FloatingBallGestureAction.entries.map { fragment.getString(it.labelRes) },
            fragment.getString(currentAction.labelRes)
        )
    }

    private fun setupOverlayModeSection(
        dialogBinding: DialogFloatingTranslateSettingsBinding,
        currentMode: FloatingOverlayMode
    ) {
        // Below Android 12 the overlay never blocks touches, so the choice would have no effect.
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        dialogBinding.floatingOverlayModeLayout.isVisible = supported
        dialogBinding.floatingOverlayModeNote.isVisible = supported
        if (!supported) return
        setupLabelDropdown(
            dialogBinding.floatingOverlayModeInput,
            FloatingOverlayMode.entries.map { fragment.getString(it.labelRes) },
            fragment.getString(currentMode.labelRes)
        )
    }

    private fun parseFloatingOverlayMode(
        inputView: MaterialAutoCompleteTextView,
        defaultMode: FloatingOverlayMode
    ): FloatingOverlayMode {
        val selectedLabel = inputView.text?.toString()?.trim().orEmpty()
        if (selectedLabel.isBlank()) return defaultMode
        return FloatingOverlayMode.entries.firstOrNull {
            fragment.getString(it.labelRes) == selectedLabel
        } ?: defaultMode
    }

    private fun setupLabelDropdown(
        inputView: MaterialAutoCompleteTextView,
        labels: List<String>,
        currentLabel: String
    ) {
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
        inputView.setText(currentLabel, false)
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
