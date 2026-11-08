package com.manga.translate.reader

/** Source-space strips with a bounded fit-width View height, independent of page length. */
internal object WebtoonSegments {
    const val MAX_VIEW_HEIGHT = 2048

    fun ranges(width: Int, height: Int, viewportWidth: Int): List<IntRange> {
        if (width <= 0 || height <= 0 || viewportWidth <= 0) return emptyList()
        val step = (MAX_VIEW_HEIGHT.toLong() * width / viewportWidth)
            .coerceIn(1L, height.toLong()).toInt()
        val result = ArrayList<IntRange>()
        var top = 0
        while (top < height) {
            val bottom = (top.toLong() + step).coerceAtMost(height.toLong()).toInt()
            result += top until bottom
            top = bottom
        }
        return result
    }
}
