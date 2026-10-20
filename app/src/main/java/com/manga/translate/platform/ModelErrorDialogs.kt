package com.manga.translate.platform

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R

internal fun showModelErrorDialog(
    context: Context,
    responseContent: String,
    onRetry: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onUnresolvedDismiss: (() -> Unit)? = null,
    onDialogDismissed: (() -> Unit)? = null,
    negativeButtonResId: Int = R.string.translation_skip,
    windowType: Int? = null
): AlertDialog {
    var resolved = false
    var dismissHandled = false
    val dialog = createAlertDialogBuilder(context)
        .setTitle(R.string.model_response_failed_title)
        .setScrollableMessage(ErrorDialogFormatter.formatModelErrorMessage(context, responseContent))
        .setNegativeButton(negativeButtonResId) { _, _ ->
            resolved = true
            onSkip?.invoke()
        }
        .apply {
            if (onRetry != null) {
                setPositiveButton(R.string.translation_continue) { _, _ ->
                    resolved = true
                    onRetry.invoke()
                }
            } else {
                setPositiveButton(android.R.string.ok) { _, _ ->
                    resolved = true
                }
            }
        }
        .createWithScrollableMessage()
    dialog.setCanceledOnTouchOutside(false)
    dialog.setOnDismissListener {
        if (!dismissHandled) {
            dismissHandled = true
            if (!resolved) {
                onUnresolvedDismiss?.invoke()
            }
            onDialogDismissed?.invoke()
        }
    }
    if (windowType != null) {
        dialog.window?.setType(windowType)
    }
    dialog.show()
    return dialog
}
