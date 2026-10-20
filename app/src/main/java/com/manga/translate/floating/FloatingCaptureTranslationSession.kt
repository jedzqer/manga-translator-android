package com.manga.translate.floating

import com.manga.translate.model.TranslationResult
import kotlinx.coroutines.Job

/** Owns a translation generation; platform capture remains in ProjectionCaptureSession. */
internal class FloatingCaptureTranslationSession {
    var job: Job? = null
    var result: TranslationResult? = null
    val bitmaps = FloatingBitmapController()
    @Volatile private var generation = 0L
    @Volatile private var closed = false

    fun advance(): Long {
        generation++
        job?.cancel()
        job = null
        return generation
    }

    fun isCurrent(token: Long): Boolean = !closed && generation == token

    fun close() {
        closed = true
        advance()
        result = null
    }
}
