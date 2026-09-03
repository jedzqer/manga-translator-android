package com.manga.translate.settings.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.manga.translate.R

/** Notified when the user presses the cancel button on the backup lock dialog. */
internal interface BackupOperationCancelHost {
    fun onBackupOperationCancelRequested()
}

/**
 * Modal progress dialog shown while an app backup export/import is running.
 *
 * The dialog is not dismissable — back key and outside touches are ignored, so
 * the whole app stays locked until the operation finishes. The cancel button
 * is the only way to abort; it routes the request to the owner fragment via
 * [BackupOperationCancelHost], which cancels the backing coroutine.
 *
 * It must be shown on the owner Fragment's childFragmentManager so that
 * [parentFragment] resolves the host again after the activity is recreated.
 */
internal class BackupProgressDialog : DialogFragment() {
    private var cancelHost: BackupOperationCancelHost? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        cancelHost = parentFragment as? BackupOperationCancelHost
    }

    override fun onDetach() {
        super.onDetach()
        cancelHost = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_backup_progress, null)
        view.findViewById<TextView>(R.id.backup_progress_message).text =
            requireArguments().getCharSequence(ARG_MESSAGE) ?: ""
        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancelHost?.onBackupOperationCancelRequested()
            }
            .create()
        // Only the cancel button may abort the backup; back key and outside
        // touches must keep the app locked.
        dialog.setCancelable(false)
        isCancelable = false
        return dialog
    }

    internal fun showAllowingStateLoss(manager: FragmentManager, tag: String) {
        manager.beginTransaction().add(this, tag).commitAllowingStateLoss()
    }

    companion object {
        private const val ARG_MESSAGE = "arg_message"

        fun newInstance(message: CharSequence): BackupProgressDialog =
            BackupProgressDialog().apply {
                arguments = Bundle().apply { putCharSequence(ARG_MESSAGE, message) }
            }
    }
}
