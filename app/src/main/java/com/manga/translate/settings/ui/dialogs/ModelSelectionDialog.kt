package com.manga.translate.settings.ui.dialogs

import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.platform.ErrorDialogFormatter
import com.manga.translate.platform.showWithScrollableMessage
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Model list selection dialog and model-fetch error dialog.
 */
internal class ModelSelectionDialog(
    private val fragment: SettingsFragment
) {
    fun showModelSelection(models: List<String>) {
        val items = models.toTypedArray()
        val currentSelection = fragment.fragmentBinding.modelNameInput.text
            ?.toString()
            ?.trim()
            .orEmpty()
        var selectedIndex = items.indexOf(currentSelection)
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.fetch_models_title)
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex >= 0) {
                    fragment.fragmentBinding.modelNameInput.setText(items[selectedIndex])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showFetchError(code: LlmErrorCode, detail: String? = null) {
        showFetchError(code.value, detail)
    }

    fun showFetchError(code: String, detail: String? = null) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.fetch_models_failed_title)
            .setMessage(
                fragment.getString(
                    R.string.fetch_models_failed_message,
                    ErrorDialogFormatter.formatApiErrorMessage(fragment.requireContext(), code, detail)
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .showWithScrollableMessage()
    }
}
