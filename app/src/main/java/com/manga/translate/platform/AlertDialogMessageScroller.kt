package com.manga.translate.platform

import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.NestedScrollView
import androidx.annotation.StringRes

internal fun AlertDialog.Builder.showWithScrollableMessage(): AlertDialog {
    return createWithScrollableMessage().also { dialog ->
        dialog.show()
        dialog.enableScrollableMessage()
    }
}

internal fun AlertDialog.Builder.createWithScrollableMessage(): AlertDialog {
    return create()
}

internal fun AlertDialog.Builder.setScrollableMessage(@StringRes message: Int): AlertDialog.Builder =
    setScrollableMessage(context.getText(message))

internal fun AlertDialog.Builder.setScrollableMessage(message: CharSequence): AlertDialog.Builder {
    setMessage(null as CharSequence?)
    setView(if (message.isEmpty()) null else createSafeScrollableMessageView(message))
    return this
}

internal fun AlertDialog.enableScrollableMessage() {
    val messageView = findViewById<TextView>(android.R.id.message) ?: return
    messageView.movementMethod = ScrollingMovementMethod.getInstance()
    disableFrameworkScrollBars(messageView)
    (messageView.parent as? View)?.let(::disableFrameworkScrollBars)
    findScrollableAncestors(messageView).forEach(::disableFrameworkScrollBars)
}

private fun findScrollableAncestors(start: View): List<View> {
    val scrollableViews = mutableListOf<View>()
    var current = start.parent
    while (current is View) {
        if (current is NestedScrollView || current is ScrollView) {
            scrollableViews += current
        }
        current = current.parent
    }
    return scrollableViews
}

private fun disableFrameworkScrollBars(view: View) {
    // Some Android builds crash in ScrollBarDrawable methods when dialog scrollbars are enabled.
    view.isVerticalScrollBarEnabled = false
    view.isHorizontalScrollBarEnabled = false
    view.isScrollbarFadingEnabled = false
    if (view is ViewGroup) {
        view.clipToPadding = true
    }
}

private fun AlertDialog.Builder.createSafeScrollableMessageView(message: CharSequence): View {
    val context = context
    val density = context.resources.displayMetrics.density
    val paddingHorizontal = (24 * density).toInt()
    val paddingTop = (8 * density).toInt()
    val paddingBottom = (4 * density).toInt()
    val maxHeight = (context.resources.displayMetrics.heightPixels * 0.4f).toInt()
    val messageView = AppCompatTextView(context).apply {
        text = message
        setTextIsSelectable(true)
        movementMethod = ScrollingMovementMethod.getInstance()
        setLineSpacing(0f, 1.15f)
        ellipsize = null
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    return FrameLayout(context).apply {
        addView(
            SafeNestedScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isScrollbarFadingEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(
                    messageView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    maxHeight
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setPadding(paddingHorizontal, paddingTop, paddingHorizontal, paddingBottom)
    }
}
