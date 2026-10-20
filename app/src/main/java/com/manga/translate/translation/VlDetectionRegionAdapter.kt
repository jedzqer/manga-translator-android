package com.manga.translate.translation

import android.graphics.Bitmap
import com.manga.translate.detection.BubbleDetection
import com.manga.translate.detection.BubbleDetector
import com.manga.translate.detection.DetectionTile
import com.manga.translate.detection.PageRegion
import com.manga.translate.detection.PageRegionDetector
import com.manga.translate.detection.detectWithinInsets
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation

/** Adapts detector geometry into the VL request layout at the translation boundary. */
internal suspend fun detectVlWithinInsets(
    bitmap: Bitmap,
    top: Int,
    bottom: Int,
    left: Int,
    right: Int,
    detector: PageRegionDetector
): VlPageLayout? {
    var targetCount = 0
    var backgroundCount = 0
    var targetParents: Map<Int, Int> = emptyMap()
    val mapped = detectWithinInsets(bitmap, top, bottom, left, right) { input ->
        val detection = detector.detect(input, logTag = "FloatingVL") ?: return@detectWithinInsets null
        if (!detection.detectionComplete) return@detectWithinInsets null
        val layout = detection.vlLayout()
        targetParents = layout.targetParents
        targetCount = layout.targets.size
        backgroundCount = layout.backgrounds.size
        layout.targets.map { PageRegion(it.id, it.rect, it.source, it.maskContour) } +
            layout.backgrounds.map { PageRegion(-1, it.rect, BubbleSource.BUBBLE_DETECTOR, it.maskContour) } +
            layout.tiles.map { PageRegion(-1, it.toRectF(), BubbleSource.BUBBLE_DETECTOR) }
    } ?: return null
    return VlPageLayout(
        bitmap.width,
        bitmap.height,
        mapped.take(targetCount).map { BubbleTranslation.pending(it.id, it.rect, "", it.source, it.maskContour) },
        mapped.drop(targetCount).take(backgroundCount).map {
            BubbleDetection(it.rect, 1f, BubbleDetector.CLASS_BALLOON, it.maskContour)
        },
        mapped.drop(targetCount + backgroundCount).map {
            DetectionTile(it.rect.left.toInt(), it.rect.top.toInt(), it.rect.right.toInt(), it.rect.bottom.toInt())
        },
        targetParents
    )
}
