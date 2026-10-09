package com.manga.translate.library

import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.widget.TooltipCompat

internal fun View.setActionDescription(@StringRes label: Int) {
    setActionDescription(context.getString(label))
}

internal fun View.setActionDescription(label: CharSequence) {
    contentDescription = label
    TooltipCompat.setTooltipText(this, label)
}
