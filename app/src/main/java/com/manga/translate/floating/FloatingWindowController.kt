package com.manga.translate.floating

import android.view.View
import android.view.WindowManager

/** Service-owned window attachments. All operations run on the main thread. */
internal class FloatingWindowController(private val manager: WindowManager) {
    private val attached = linkedSetOf<View>()
    private var closed = false

    fun addView(view: View, params: WindowManager.LayoutParams) {
        if (closed || view in attached) return
        manager.addView(view, params)
        attached.add(view)
    }

    fun removeView(view: View) {
        if (!attached.remove(view)) return
        runCatching { manager.removeViewImmediate(view) }
    }

    fun updateViewLayout(view: View?, params: WindowManager.LayoutParams) {
        if (!closed && view != null && view in attached) manager.updateViewLayout(view, params)
    }

    fun hideForCapture(): () -> Unit {
        val snapshot = attached.map { it to it.visibility }
        snapshot.forEach { (view, _) -> view.visibility = View.INVISIBLE }
        return {
            if (!closed) snapshot.forEach { (view, visibility) ->
                if (view in attached) view.visibility = visibility
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        attached.toList().asReversed().forEach(::removeView)
    }
}
