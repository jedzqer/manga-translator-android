package com.manga.translate.library

import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.manga.translate.R
import com.manga.translate.platform.ErrorDialogFormatter
import com.manga.translate.platform.setScrollableMessage
import com.manga.translate.platform.showWithScrollableMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns dialogs for one view lifetime; acknowledgements and decisions stay in the bridge. */
internal class LibraryErrorController(private val fragment: Fragment, private val dialogs: LibraryDialogs) {
    private var activeId: Long? = null
    private var dialog: AlertDialog? = null

    fun render(errors: List<LibraryPendingError>) {
        val next = errors.firstOrNull()
        if (next?.id == activeId) return
        release()
        if (next == null || !fragment.isAdded || fragment.view == null) return
        val overlay = next.useSystemOverlay && Settings.canDrawOverlays(fragment.requireContext())
        if (!overlay && !fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        activeId = next.id
        dialog = if (next.apiCode != null) {
            AlertDialog.Builder(fragment.requireContext())
                .setTitle(R.string.api_request_failed_title)
                .setScrollableMessage(fragment.getString(R.string.api_request_failed_message,
                    ErrorDialogFormatter.formatApiErrorMessage(fragment.requireContext(), next.apiCode, next.content)))
                .setPositiveButton(android.R.string.ok) { _, _ -> LibraryUiBridge.acknowledge(next.id) }
                .setOnCancelListener { LibraryUiBridge.acknowledge(next.id) }
                .showWithScrollableMessage()
        } else {
            dialogs.showModelErrorDialog(fragment.requireContext(), next.content,
                onRetry = { LibraryUiBridge.acknowledge(next.id, retry = true) },
                onSkip = { LibraryUiBridge.acknowledge(next.id) },
                onUnresolvedDismiss = {
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        delay(15_000)
                        if (fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            render(LibraryUiBridge.pendingErrors.value)
                        }
                    }
                },
                onDialogDismissed = { activeId = null; dialog = null },
                windowType = if (overlay) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                } else null)
        }
    }

    fun release() {
        activeId = null
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }
}
