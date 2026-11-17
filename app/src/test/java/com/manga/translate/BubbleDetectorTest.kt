package com.manga.translate

import android.app.Application
import android.graphics.RectF
import com.manga.translate.detection.BubbleDetection
import com.manga.translate.detection.BubbleDetector
import com.manga.translate.detection.YoloClassScore
import com.manga.translate.detection.bestYoloClassScore
import com.manga.translate.detection.deduplicateBubbleDetections
import com.manga.translate.detection.effectiveDetectionConfidenceThreshold
import com.manga.translate.detection.retainLargestConnectedMaskComponent
import com.manga.translate.model.TranslationCoreDefaults
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BubbleDetectorTest {
    @Test
    fun `balloon confidence uses conservative hard floor`() {
        assertEquals(
            TranslationCoreDefaults.MinBalloonConfidence,
            effectiveDetectionConfidenceThreshold(BubbleDetector.CLASS_BALLOON, 0.10f),
            1e-6f
        )
        assertEquals(
            TranslationCoreDefaults.MinBalloonConfidence,
            effectiveDetectionConfidenceThreshold(BubbleDetector.CLASS_BALLOON, 0.25f),
            1e-6f
        )
        assertEquals(
            0.45f,
            effectiveDetectionConfidenceThreshold(BubbleDetector.CLASS_BALLOON, 0.45f),
            1e-6f
        )
    }

    @Test
    fun `text detections use a separate false-positive floor`() {
        assertEquals(
            TranslationCoreDefaults.MinTextConfidence,
            effectiveDetectionConfidenceThreshold(BubbleDetector.CLASS_TEXT, 0.10f),
            1e-6f
        )
        assertEquals(
            0.40f,
            effectiveDetectionConfidenceThreshold(BubbleDetector.CLASS_TEXT, 0.40f),
            1e-6f
        )
    }

    @Test
    fun `comic output chooses the strongest class score`() {
        assertEquals(
            YoloClassScore(classId = BubbleDetector.CLASS_TEXT, confidence = 0.82f),
            bestYoloClassScore(floatArrayOf(320f, 320f, 100f, 80f, 0.12f, 0.82f))
        )
    }

    @Test
    fun `single class text output uses its only class score`() {
        assertEquals(
            YoloClassScore(classId = 0, confidence = 0.73f),
            bestYoloClassScore(floatArrayOf(320f, 320f, 100f, 80f, 0.73f))
        )
    }

    @Test
    fun `mask contour ignores a disconnected prototype island`() {
        val width = 8
        val foreground = BooleanArray(width * 6)
        for (y in 1..4) {
            for (x in 1..4) foreground[y * width + x] = true
        }
        foreground[2 * width + 7] = true

        val result = requireNotNull(
            retainLargestConnectedMaskComponent(foreground, width, height = 6)
        )

        assertEquals(16, result.count { it })
        assertEquals(false, result[2 * width + 7])
    }

    @Test
    fun `substantial disconnected lobes fall back to full detection geometry`() {
        val width = 12
        val foreground = BooleanArray(width * 6)
        for (y in 1..4) {
            for (x in 1..4) foreground[y * width + x] = true
            for (x in 7..10) foreground[y * width + x] = true
        }
        assertEquals(null, retainLargestConnectedMaskComponent(foreground, width, 6))
        // Also reject a sizeable smaller lobe, rather than only equal halves.
        for (y in 1..2) for (x in 7..10) foreground[y * width + x] = false
        assertEquals(null, retainLargestConnectedMaskComponent(foreground, width, 6))
    }

    @Test
    fun `overlapping bubble detections keep the highest confidence candidate`() {
        val lowerConfidence = detection(RectF(100f, 100f, 400f, 400f), confidence = 0.72f)
        val higherConfidence = detection(RectF(112f, 108f, 408f, 404f), confidence = 0.91f)
        val separate = detection(RectF(500f, 100f, 720f, 360f), confidence = 0.65f)

        val result = deduplicateBubbleDetections(
            listOf(lowerConfidence, separate, higherConfidence)
        )

        assertEquals(listOf(separate, higherConfidence), result)
    }

    @Test
    fun `nearly contained bubble detection is removed even below iou threshold`() {
        val outer = detection(RectF(0f, 0f, 200f, 200f), confidence = 0.88f)
        val inner = detection(RectF(20f, 20f, 180f, 180f), confidence = 0.75f)

        assertEquals(listOf(outer), deduplicateBubbleDetections(listOf(outer, inner)))
    }

    @Test
    fun `shifted duplicate below strict iou threshold is removed`() {
        val original = detection(RectF(0f, 0f, 200f, 160f), confidence = 0.88f)
        val shifted = detection(RectF(30f, 20f, 230f, 180f), confidence = 0.75f)

        assertEquals(listOf(original), deduplicateBubbleDetections(listOf(original, shifted)))
    }

    @Test
    fun `partially overlapping distinct bubbles remain separate`() {
        val first = detection(RectF(0f, 0f, 100f, 100f), confidence = 0.85f)
        val second = detection(RectF(40f, 0f, 140f, 100f), confidence = 0.80f)

        assertEquals(listOf(first, second), deduplicateBubbleDetections(listOf(first, second)))
    }

    @Test
    fun `overlapping detections with different shapes remain separate`() {
        val wide = detection(RectF(0f, 40f, 200f, 140f), confidence = 0.85f)
        val tall = detection(RectF(50f, 0f, 150f, 200f), confidence = 0.80f)

        assertEquals(listOf(wide, tall), deduplicateBubbleDetections(listOf(wide, tall)))
    }

    private fun detection(rect: RectF, confidence: Float): BubbleDetection {
        return BubbleDetection(
            rect = rect,
            confidence = confidence,
            classId = BubbleDetector.CLASS_BALLOON
        )
    }
}
