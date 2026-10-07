package com.manga.translate.translation

import android.graphics.Bitmap
import com.manga.translate.network.LlmErrorCode
import com.manga.translate.network.LlmResponseException
import com.manga.translate.platform.BitmapCropSource
import com.manga.translate.platform.ImageEncodingUtils
import com.manga.translate.platform.recycleSafely
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** The callback borrows the annotation; neither bitmap escapes its owning scope. */
internal suspend fun <T> withPageAnnotation(
    source: BitmapCropSource,
    layout: VlPageLayout,
    chunk: VlPageChunk,
    decodeFailure: () -> Exception,
    consume: suspend (Bitmap) -> T
): T {
    coroutineContext.ensureActive()
    val crop = source.decodeRegion(chunk.rect) ?: throw decodeFailure()
    val annotated = try {
        VlPageAnnotationRenderer.render(crop, layout, chunk)
    } finally {
        crop.recycleSafely()
    }
    return try {
        coroutineContext.ensureActive()
        consume(annotated)
    } finally {
        annotated.recycleSafely()
    }
}

internal suspend fun encodePageAnnotation(
    source: BitmapCropSource,
    layout: VlPageLayout,
    chunk: VlPageChunk
): String = withPageAnnotation(source, layout, chunk,
    decodeFailure = { LlmResponseException(LlmErrorCode.ImageEncodeFailed, "Cannot decode VL page") }
) { annotated ->
    ImageEncodingUtils.encodeBitmapToBase64(annotated)
        ?: throw LlmResponseException(LlmErrorCode.ImageEncodeFailed, "Cannot encode VL page")
}
