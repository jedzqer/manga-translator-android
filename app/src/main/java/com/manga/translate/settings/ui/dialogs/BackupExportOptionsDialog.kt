package com.manga.translate.settings.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.fragment.app.DialogFragment
import com.manga.translate.R
import com.manga.translate.storage.AppBackupManager

internal interface BackupExportOptionsHost {
    fun onBackupExportOptionsSelected(options: AppBackupManager.ExportOptions)
}

/** Keeps the selection across recreation, including while the document picker is open. */
internal class BackupExportOptionsDialog : DialogFragment() {
    private var includeManga = true
    private var includeSettings = true

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        includeManga = savedInstanceState?.getBoolean(KEY_MANGA, true) ?: true
        includeSettings = savedInstanceState?.getBoolean(KEY_SETTINGS, true) ?: true
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_backup_export_options, null)
        val manga = view.findViewById<AppCompatCheckBox>(R.id.backup_include_manga)
        val settings = view.findViewById<AppCompatCheckBox>(R.id.backup_include_settings)
        manga.isChecked = includeManga
        settings.isChecked = includeSettings
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.backup_export_options_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backup_export_continue) { _, _ ->
                (parentFragment as? BackupExportOptionsHost)?.onBackupExportOptionsSelected(
                    AppBackupManager.ExportOptions(includeManga, includeSettings)
                )
            }
            .create()
        fun updateSelection() {
            includeManga = manga.isChecked
            includeSettings = settings.isChecked
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = includeManga || includeSettings
        }
        manga.setOnCheckedChangeListener { _, _ -> updateSelection() }
        settings.setOnCheckedChangeListener { _, _ -> updateSelection() }
        dialog.setOnShowListener { updateSelection() }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MANGA, includeManga)
        outState.putBoolean(KEY_SETTINGS, includeSettings)
    }

    companion object {
        const val TAG = "backup_export_options"
        private const val KEY_MANGA = "include_manga"
        private const val KEY_SETTINGS = "include_settings"
    }
}
