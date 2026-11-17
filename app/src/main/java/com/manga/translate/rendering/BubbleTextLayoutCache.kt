package com.manga.translate.rendering

import android.text.Layout
import android.text.TextPaint
import java.util.LinkedHashMap

/** View-owned, UI-thread-only reuse of text preparation and fitted layouts. */
internal class BubbleTextLayoutCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private data class Key(
        val text: String,
        val width: Int,
        val height: Int,
        val fillPercent: Int,
        val vertical: Boolean,
        val alignment: Layout.Alignment?,
        val lineSpacing: Float,
        val typeface: android.graphics.Typeface?,
        val paintFlags: Int,
        val density: Float,
        val textScaleX: Float,
        val textSkewX: Float,
        val wordSpacing: Float,
        val baselineShift: Int,
        val letterSpacing: Float,
        val fontFeatureSettings: String?,
        val textLocales: android.os.LocaleList,
        val elegantTextHeight: Boolean
    )

    private data class Entry(
        val horizontal: BubbleTextLayoutPreparer.Horizontal? = null,
        val vertical: BubbleTextLayoutPreparer.Vertical? = null
    )

    private val entries = object : LinkedHashMap<Key, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean =
            size > maxEntries
    }

    internal val size: Int get() = entries.size

    fun horizontal(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        fillPercent: Int,
        basePaint: TextPaint,
        alignment: Layout.Alignment,
        lineSpacing: Float = 0f
    ): BubbleTextLayoutPreparer.Horizontal {
        val width = maxWidth.coerceAtLeast(1)
        val height = maxHeight.coerceAtLeast(1)
        val key = key(text, width, height, fillPercent, false, basePaint, alignment, lineSpacing)
        entries[key]?.horizontal?.let { cached ->
            cached.paint.color = basePaint.color
            return cached
        }

        return BubbleTextLayoutPreparer.horizontal(
            text, width, height, fillPercent, basePaint, alignment, lineSpacing
        ).also { entries[key] = Entry(horizontal = it) }
    }

    fun vertical(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        fillPercent: Int,
        basePaint: TextPaint
    ): BubbleTextLayoutPreparer.Vertical {
        val width = maxWidth.coerceAtLeast(1)
        val height = maxHeight.coerceAtLeast(1)
        val key = key(text, width, height, fillPercent, true, basePaint, null, 0f)
        entries[key]?.vertical?.let { cached ->
            cached.paint.color = basePaint.color
            return cached
        }

        return BubbleTextLayoutPreparer.vertical(text, width, height, fillPercent, basePaint)
            .also { entries[key] = Entry(vertical = it) }
    }

    fun clear() = entries.clear()

    @Suppress("DEPRECATION") // Still affects layout on supported Android versions.
    private fun key(
        text: String,
        width: Int,
        height: Int,
        fillPercent: Int,
        vertical: Boolean,
        paint: TextPaint,
        alignment: Layout.Alignment?,
        lineSpacing: Float
    ) = Key(
        text = text,
        width = width,
        height = height,
        fillPercent = fillPercent.coerceIn(20, 100),
        vertical = vertical,
        alignment = alignment,
        lineSpacing = lineSpacing,
        typeface = paint.typeface,
        paintFlags = paint.flags,
        density = paint.density,
        textScaleX = paint.textScaleX,
        textSkewX = paint.textSkewX,
        wordSpacing = paint.wordSpacing,
        baselineShift = paint.baselineShift,
        letterSpacing = paint.letterSpacing,
        fontFeatureSettings = paint.fontFeatureSettings,
        textLocales = paint.textLocales,
        elegantTextHeight = paint.isElegantTextHeight
    )

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 96
    }
}
