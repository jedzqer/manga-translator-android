package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogOcrSettingsBinding
import com.manga.translate.model.OcrApiFormat
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.OcrApiSettings
import com.manga.translate.settings.SettingsStore
import com.manga.translate.settings.ui.SettingsFragment
import java.util.Locale

/**
 * OCR API settings editing dialog.
 */
internal class OcrSettingsDialog(
    private val fragment: SettingsFragment,
    private val settingsStore: SettingsStore
) {
    fun show() {
        val currentSettings = settingsStore.loadOcrApiSettings()
        val dialogBinding = DialogOcrSettingsBinding.inflate(fragment.layoutInflater)
        dialogBinding.useLocalOcrSwitch.isChecked = currentSettings.useLocalOcr
        val supplementLabels = listOf(R.string.ocr_supplement_off, R.string.ocr_supplement_low,
            R.string.ocr_supplement_medium, R.string.ocr_supplement_high)
        val supplementDescriptions = listOf(R.string.ocr_supplement_off_description,
            R.string.ocr_supplement_low_description, R.string.ocr_supplement_medium_description,
            R.string.ocr_supplement_high_description)
        dialogBinding.apiOcrSupplementSlider.progress =
            if (currentSettings.apiSupplementEnabled) currentSettings.apiSupplementLevel.coerceIn(1, 3) else 0
        dialogBinding.manualBubblesApiOcrSwitch.isChecked = currentSettings.manualBubblesUseApi
        dialogBinding.ocrApiUrlInput.setText(currentSettings.apiUrl)
        dialogBinding.ocrApiKeyInput.setText(currentSettings.apiKey)
        dialogBinding.ocrModelNameInput.setText(currentSettings.modelName)
        dialogBinding.ocrApiTimeoutInput.setText(
            String.format(Locale.getDefault(), "%d", currentSettings.timeoutSeconds)
        )
        dialogBinding.ocrApiConcurrencyInput.setText(
            String.format(Locale.getDefault(), "%d", currentSettings.apiOcrConcurrencyLimit)
        )
        // Setup format dropdown
        val formatEntries = OcrApiFormat.entries.map { fragment.getString(it.labelRes) }
        val formatValues = OcrApiFormat.entries.toTypedArray()
        val formatAdapter = ArrayAdapter(
            fragment.requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            formatEntries
        )
        dialogBinding.ocrApiFormatInput.setAdapter(formatAdapter)
        dialogBinding.ocrApiFormatInput.threshold = 0
        dialogBinding.ocrApiFormatInput.setOnClickListener {
            dialogBinding.ocrApiFormatInput.showDropDown()
        }
        val currentFormatIndex = formatValues.indexOf(currentSettings.ocrApiFormat)
            .coerceAtLeast(0)
        dialogBinding.ocrApiFormatInput.setText(formatEntries[currentFormatIndex], false)

        fun resolveSelectedFormat(): OcrApiFormat {
            val selectedText = dialogBinding.ocrApiFormatInput.text?.toString().orEmpty()
            val idx = formatEntries.indexOf(selectedText)
            return if (idx >= 0) formatValues[idx] else OcrApiFormat.OPENAI_COMPATIBLE
        }

        fun updateInputsEnabled(useLocalOcr: Boolean) {
            val level = dialogBinding.apiOcrSupplementSlider.progress
            dialogBinding.apiOcrSupplementLabel.text = fragment.getString(R.string.ocr_supplement_level_label,
                fragment.getString(supplementLabels[level]))
            dialogBinding.apiOcrSupplementDescription.setText(supplementDescriptions[level])
            androidx.core.view.ViewCompat.setStateDescription(dialogBinding.apiOcrSupplementSlider,
                fragment.getString(supplementLabels[level]))
            val enabled = !useLocalOcr || level > 0 ||
                dialogBinding.manualBubblesApiOcrSwitch.isChecked
            dialogBinding.apiOcrSupplementSlider.isEnabled = useLocalOcr
            dialogBinding.manualBubblesApiOcrSwitch.isEnabled = useLocalOcr
            dialogBinding.ocrApiFormatLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrApiUrlLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrApiKeyLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrModelNameLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrApiTimeoutLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrApiUrlInput.isEnabled = enabled
            dialogBinding.ocrApiKeyInput.isEnabled = enabled
            dialogBinding.ocrModelNameInput.isEnabled = enabled
            dialogBinding.ocrApiTimeoutInput.isEnabled = enabled
            dialogBinding.ocrApiConcurrencyLayout.visibility =
                if (enabled) View.VISIBLE else View.GONE
            dialogBinding.ocrSettingsNote.setText(
                if (useLocalOcr) R.string.ocr_settings_note_local else R.string.ocr_settings_note_api
            )
        }

        updateInputsEnabled(currentSettings.useLocalOcr)
        dialogBinding.apiOcrSupplementSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateInputsEnabled(dialogBinding.useLocalOcrSwitch.isChecked)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        dialogBinding.manualBubblesApiOcrSwitch.setOnCheckedChangeListener { _, _ ->
            updateInputsEnabled(dialogBinding.useLocalOcrSwitch.isChecked)
        }
        dialogBinding.useLocalOcrSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateInputsEnabled(isChecked)
        }
        dialogBinding.ocrApiFormatInput.setOnItemClickListener { _, _, _, _ ->
            updateInputsEnabled(dialogBinding.useLocalOcrSwitch.isChecked)
        }

        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.ocr_settings_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val timeoutInput = dialogBinding.ocrApiTimeoutInput.text?.toString()?.trim()
                val timeoutSeconds = fragment.parseIntInput(timeoutInput)
                    ?.coerceIn(SettingsStore.MIN_OCR_API_TIMEOUT_SECONDS, SettingsStore.MAX_OCR_API_TIMEOUT_SECONDS)
                    ?: currentSettings.timeoutSeconds
                val concurrencyInput = dialogBinding.ocrApiConcurrencyInput.text?.toString()?.trim()
                val apiOcrConcurrencyLimit = fragment.parseIntInput(concurrencyInput)
                    ?.coerceIn(SettingsStore.MIN_OCR_API_CONCURRENCY, SettingsStore.MAX_OCR_API_CONCURRENCY)
                    ?: currentSettings.apiOcrConcurrencyLimit
                val format = resolveSelectedFormat()
                val settings = OcrApiSettings(
                    useLocalOcr = dialogBinding.useLocalOcrSwitch.isChecked,
                    apiSupplementEnabled = dialogBinding.apiOcrSupplementSlider.progress > 0,
                    apiSupplementLevel = dialogBinding.apiOcrSupplementSlider.progress.takeIf { it > 0 }
                        ?: currentSettings.apiSupplementLevel,
                    manualBubblesUseApi = dialogBinding.manualBubblesApiOcrSwitch.isChecked,
                    apiUrl = dialogBinding.ocrApiUrlInput.text?.toString()?.trim().orEmpty(),
                    apiKey = dialogBinding.ocrApiKeyInput.text?.toString()?.trim().orEmpty(),
                    modelName = dialogBinding.ocrModelNameInput.text?.toString()?.trim().orEmpty(),
                    timeoutSeconds = timeoutSeconds,
                    apiOcrConcurrencyLimit = apiOcrConcurrencyLimit,
                    localOcrConcurrencyLimit = SettingsStore.DEFAULT_LOCAL_OCR_CONCURRENCY,
                    ocrApiFormat = format
                )
                saveOcrSettingsWithResourceCheck(settings)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveOcrSettingsWithResourceCheck(settings: OcrApiSettings) {
        fun save() {
            settingsStore.saveOcrApiSettings(settings)
            AppLogger.log(
                "Settings",
                "OCR mode set to ${
                    if (settings.useLocalOcr) {
                        "local:ppocrv6_small_rec"
                    } else {
                        "${settings.ocrApiFormat.prefValue} api"
                    }
                }"
            )
        }

        save()
    }
}
