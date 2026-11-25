package com.manga.translate.settings.ui.dialogs

import com.manga.translate.R
import com.manga.translate.model.ApiFormat
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.ui.SettingsFragment

/**
 * API format selection dialog.
 */
internal class ApiFormatDialog(private val fragment: SettingsFragment) {
    fun show() {
        showSingleChoiceSettingDialog(
            context = fragment.requireContext(),
            titleRes = R.string.api_format_title,
            options = ApiFormat.entries,
            current = fragment.currentApiFormat(),
            labelRes = { it.labelRes }
        ) { dialog, selected ->
            fragment.updateApiFormatButton(selected)
            fragment.updateApiSettingsNote(selected)
            AppLogger.log("Settings", "API format set to ${selected.prefValue}")
            dialog.dismiss()
        }
    }
}
