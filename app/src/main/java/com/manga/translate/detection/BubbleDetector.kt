package com.manga.translate.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.manga.translate.model.TranslationCoreDefaults
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.SettingsStore
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class BubbleDetection(
    val rect: RectF,
    val confidence: Float,
    val classId: Int,
    val maskContour: FloatArray? = null
)

data class UnifiedRegionDetection(
    val balloons: List<BubbleDetection>,
    val freeTextRects: List<RectF>,
    val detectedTextLines: List<RectF>? = null,
    val detectionComplete: Boolean = true,
    val textDetections: List<BubbleDetection> = emptyList()
)

private const val MASK_COEFFICIENT_COUNT = 32

/** Shared TFLite bubble/text detector. Inference buffers are reused under the instance lock. */
class BubbleDetector(
    context: Context,
    modelAssetName: String = DEFAULT_MODEL_ASSET,
    threadProfile: OnnxThreadProfile = OnnxThreadProfile.LIGHT,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) : AutoCloseable {
    private val model = TfliteDualModel(
        context.applicationContext, modelAssetName,
        threadProfile.intraOpThreads
    )

    @Synchronized
    fun detectRegions(bitmap: Bitmap): UnifiedRegionDetection {
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            return UnifiedRegionDetection(emptyList(), emptyList())
        }
        val preprocessed = OnnxImagePreprocessor.letterbox(bitmap, model.inputWidth, model.inputHeight, stretchShortImages = false)
        try {
            model.run(preprocessed.bitmap)
        } finally {
            preprocessed.bitmap.recycle()
        }
        val detections = decodeDualDetections(
            model.detections, model.anchorCount, model.inputWidth, model.inputHeight,
            settingsStore.loadBubbleConfThresholdPercent() / 100f
        )
        val balloons = ArrayList<BubbleDetection>()
        val textRects = ArrayList<RectF>()
        val textDetections = ArrayList<BubbleDetection>()
        for (raw in detections) {
            val rect = raw.toRect(preprocessed, bitmap.width, bitmap.height)
            if (rect.width() <= 1f || rect.height() <= 1f) continue
            val contour = computeDualMaskContour(
                raw, model.prototypes, model.protoHeight, model.protoWidth,
                preprocessed, bitmap.width, bitmap.height, model.inputWidth, model.inputHeight
            )
            if (raw.classId == CLASS_TEXT) {
                textRects.add(rect)
                textDetections.add(BubbleDetection(rect, raw.confidence, CLASS_TEXT, contour))
            } else {
                balloons.add(BubbleDetection(rect, raw.confidence, CLASS_BALLOON, contour))
            }
        }
        val keptBalloons = deduplicateBubbleDetections(balloons)
        if (settingsStore.loadModelIoLogging()) {
            AppLogger.log("BubbleDetector",
                "TFLite dual: bubbles=${keptBalloons.size}, text=${textRects.size}, " +
                    "input=${model.inputWidth}x${model.inputHeight}")
        }
        return UnifiedRegionDetection(keptBalloons, textRects, textDetections = textDetections)
    }

    @Synchronized
    fun detect(bitmap: Bitmap): List<BubbleDetection> = detectRegions(bitmap).balloons

    @Synchronized
    override fun close() = model.close()

    companion object {
        const val DEFAULT_MODEL_ASSET = "models/detection/mixed-dual-s-e5_float16.tflite"
        const val CLASS_BALLOON = 0
        const val CLASS_TEXT = 1
    }
}

internal data class RawDetection(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val classId: Int,
    val maskCoefficients: FloatArray
) {
    fun toRect(
        preprocessed: LetterboxResult,
        originalWidth: Int,
        originalHeight: Int
    ): RectF {
        val left = OnnxImagePreprocessor.toOriginalX(cx - width / 2f, preprocessed)
        val top = OnnxImagePreprocessor.toOriginalY(cy - height / 2f, preprocessed)
        val right = OnnxImagePreprocessor.toOriginalX(cx + width / 2f, preprocessed)
        val bottom = OnnxImagePreprocessor.toOriginalY(cy + height / 2f, preprocessed)
        val maxX = max(0f, originalWidth - 1f)
        val maxY = max(0f, originalHeight - 1f)
        return RectF(
            left.coerceIn(0f, maxX),
            top.coerceIn(0f, maxY),
            right.coerceIn(0f, maxX),
            bottom.coerceIn(0f, maxY)
        )
    }
}

internal fun retainLargestConnectedMaskComponent(
    foreground: BooleanArray,
    width: Int,
    height: Int
): BooleanArray? {
    if (width <= 0 || height <= 0 || foreground.size != width * height) return null
    val labels = IntArray(foreground.size)
    val queue = IntArray(foreground.size)
    var nextLabel = 0
    var largestLabel = 0
    var largestSize = 0

    for (start in foreground.indices) {
        if (!foreground[start] || labels[start] != 0) continue
        nextLabel++
        var head = 0
        var tail = 0
        var componentSize = 0
        queue[tail++] = start
        labels[start] = nextLabel
        while (head < tail) {
            val current = queue[head++]
            componentSize++
            val currentX = current % width
            val currentY = current / width
            val minY = maxOf(0, currentY - 1)
            val maxY = minOf(height - 1, currentY + 1)
            val minX = maxOf(0, currentX - 1)
            val maxX = minOf(width - 1, currentX + 1)
            for (neighborY in minY..maxY) {
                for (neighborX in minX..maxX) {
                    val neighbor = neighborY * width + neighborX
                    if (!foreground[neighbor] || labels[neighbor] != 0) continue
                    labels[neighbor] = nextLabel
                    queue[tail++] = neighbor
                }
            }
        }
        if (componentSize > largestSize) {
            largestLabel = nextLabel
            largestSize = componentSize
        }
    }
    if (largestLabel == 0) return null
    // A substantial second lobe is not prototype noise. Falling back to the
    // detection rectangle is safer than rendering all OCR text in half a balloon.
    if (largestSize < foreground.count { it } * 0.8f) return null
    return BooleanArray(foreground.size) { labels[it] == largestLabel }
}

internal data class YoloClassScore(
    val classId: Int,
    val confidence: Float
)

internal fun bestYoloClassScore(
    featureRow: FloatArray,
    firstClassIndex: Int = 4
): YoloClassScore? {
    if (firstClassIndex !in featureRow.indices) return null
    var bestClassId = -1
    var bestConfidence = Float.NEGATIVE_INFINITY
    for (index in firstClassIndex until featureRow.size) {
        val confidence = featureRow[index]
        if (!confidence.isFinite()) continue
        if (confidence > bestConfidence) {
            bestConfidence = confidence
            bestClassId = index - firstClassIndex
        }
    }
    if (bestClassId < 0) return null
    return YoloClassScore(bestClassId, bestConfidence)
}

internal fun effectiveDetectionConfidenceThreshold(
    classId: Int,
    configuredThreshold: Float
): Float {
    val normalized = configuredThreshold.coerceIn(0f, 1f)
    return if (classId == BubbleDetector.CLASS_BALLOON) {
        max(normalized, TranslationCoreDefaults.MinBalloonConfidence)
    } else {
        max(normalized, TranslationCoreDefaults.MinTextConfidence)
    }
}

/**
 * Final class-aware NMS for residual overlapping boxes left by the exported model.
 * The result keeps the detector's original order while selecting winners by confidence.
 */
internal fun deduplicateBubbleDetections(
    detections: List<BubbleDetection>,
    iouThreshold: Float = TranslationCoreDefaults.BubbleDedupIouThreshold
): List<BubbleDetection> {
    if (detections.size <= 1) return detections

    val ranked = detections.indices.sortedWith(
        compareByDescending<Int> { detections[it].confidence }
            .thenByDescending { detectionArea(detections[it].rect) }
            .thenBy { it }
    )
    val keptIndices = ArrayList<Int>(detections.size)
    for (candidateIndex in ranked) {
        val candidate = detections[candidateIndex]
        val duplicate = keptIndices.any { keptIndex ->
            val kept = detections[keptIndex]
            candidate.classId == kept.classId &&
                areDuplicateBubbleRects(candidate.rect, kept.rect, iouThreshold)
        }
        if (!duplicate) keptIndices.add(candidateIndex)
    }
    keptIndices.sort()
    return keptIndices.map(detections::get)
}

private fun areDuplicateBubbleRects(a: RectF, b: RectF, iouThreshold: Float): Boolean {
    val areaA = detectionArea(a)
    val areaB = detectionArea(b)
    if (areaA <= 0f || areaB <= 0f) return false

    val intersection = detectionIntersectionArea(a, b)
    if (intersection <= 0f) return false
    val union = areaA + areaB - intersection
    if (union > 0f && intersection / union >= iouThreshold.coerceIn(0f, 1f)) return true

    val overlapOverMinArea = intersection / minOf(areaA, areaB)
    if (overlapOverMinArea >= BUBBLE_DUPLICATE_CONTAINMENT_THRESHOLD) return true

    // Slightly shifted predictions for the same bubble can fall below the strict NMS
    // IoU threshold. Only use this relaxed path when their size and center also agree,
    // so two genuinely adjacent bubbles that merely overlap are retained.
    if (overlapOverMinArea < BUBBLE_DUPLICATE_RELAXED_OVERLAP_THRESHOLD) return false
    val widthA = a.width()
    val widthB = b.width()
    val heightA = a.height()
    val heightB = b.height()
    val widthRatio = minOf(widthA, widthB) / maxOf(widthA, widthB)
    val heightRatio = minOf(heightA, heightB) / maxOf(heightA, heightB)
    if (widthRatio < BUBBLE_DUPLICATE_SIZE_RATIO_THRESHOLD ||
        heightRatio < BUBBLE_DUPLICATE_SIZE_RATIO_THRESHOLD
    ) {
        return false
    }

    val centerDx = abs((a.left + a.right) - (b.left + b.right)) * 0.5f
    val centerDy = abs((a.top + a.bottom) - (b.top + b.bottom)) * 0.5f
    return centerDx <= minOf(widthA, widthB) * BUBBLE_DUPLICATE_CENTER_DRIFT_RATIO &&
        centerDy <= minOf(heightA, heightB) * BUBBLE_DUPLICATE_CENTER_DRIFT_RATIO
}

private fun detectionArea(rect: RectF): Float =
    max(0f, rect.width()) * max(0f, rect.height())

private fun detectionIntersectionArea(a: RectF, b: RectF): Float =
    max(0f, minOf(a.right, b.right) - max(a.left, b.left)) *
        max(0f, minOf(a.bottom, b.bottom) - max(a.top, b.top))

private const val BUBBLE_DUPLICATE_CONTAINMENT_THRESHOLD = 0.85f
private const val BUBBLE_DUPLICATE_RELAXED_OVERLAP_THRESHOLD = 0.55f
private const val BUBBLE_DUPLICATE_SIZE_RATIO_THRESHOLD = 0.75f
private const val BUBBLE_DUPLICATE_CENTER_DRIFT_RATIO = 0.25f

/**
 * Reconstruct a compact outer polygon from the prototype mask. Sampling
 * scanlines keeps the Android overlay lightweight while preserving the
 * useful non-rectangular speech-bubble shape.
 */
internal fun computeDualMaskContour(
    detection: RawDetection,
    prototypes: FloatBuffer,
    protoHeight: Int,
    protoWidth: Int,
    preprocessed: LetterboxResult,
    originalWidth: Int,
    originalHeight: Int,
    inputWidth: Int,
    inputHeight: Int
): FloatArray? {
    val inputLeft = (detection.cx - detection.width / 2f).coerceIn(0f, inputWidth.toFloat())
    val inputTop = (detection.cy - detection.height / 2f).coerceIn(0f, inputHeight.toFloat())
    val inputRight = (detection.cx + detection.width / 2f).coerceIn(0f, inputWidth.toFloat())
    val inputBottom = (detection.cy + detection.height / 2f).coerceIn(0f, inputHeight.toFloat())
    val x1 = floor(inputLeft / inputWidth * protoWidth).toInt().coerceIn(0, protoWidth - 1)
    val y1 = floor(inputTop / inputHeight * protoHeight).toInt().coerceIn(0, protoHeight - 1)
    val x2 = ceil(inputRight / inputWidth * protoWidth).toInt().coerceIn(x1 + 1, protoWidth)
    val y2 = ceil(inputBottom / inputHeight * protoHeight).toInt().coerceIn(y1 + 1, protoHeight)
    if (x2 <= x1 || y2 <= y1) return null

    val maskWidth = x2 - x1
    val maskHeight = y2 - y1
    val foreground = BooleanArray(maskWidth * maskHeight)
    for (localY in 0 until maskHeight) {
        val protoOffset = (y1 + localY) * protoWidth + x1
        for (localX in 0 until maskWidth) {
            var score = 0f
            for (coefficient in detection.maskCoefficients.indices) {
                score += detection.maskCoefficients[coefficient] *
                    prototypes.get((protoOffset + localX) * MASK_COEFFICIENT_COUNT + coefficient)
            }
            foreground[localY * maskWidth + localX] = score > 0f
        }
    }
    val mainComponent = retainLargestConnectedMaskComponent(
        foreground,
        maskWidth,
        maskHeight
    ) ?: return null

    val sampleCount = (y2 - y1).coerceIn(4, 48)
    val leftEdge = ArrayList<Float>(sampleCount * 2)
    val rightEdge = ArrayList<Float>(sampleCount * 2)
    for (sample in 0 until sampleCount) {
        val fraction = if (sampleCount == 1) 0f else sample / (sampleCount - 1f)
        val y = (y1 + ((y2 - 1 - y1) * fraction).toInt()).coerceIn(y1, y2 - 1)
        var leftX = -1
        var rightX = -1
        for (x in x1 until x2) {
            if (mainComponent[(y - y1) * maskWidth + (x - x1)]) {
                if (leftX < 0) leftX = x
                rightX = x
            }
        }
        if (leftX >= 0) {
            val leftPoint = mapMaskPointToNormalized(
                leftX.toFloat(), y.toFloat(), protoWidth, protoHeight,
                preprocessed, originalWidth, originalHeight
            )
            val rightPoint = mapMaskPointToNormalized(
                (rightX + 1).toFloat(), y.toFloat(), protoWidth, protoHeight,
                preprocessed, originalWidth, originalHeight
            )
            leftEdge.add(leftPoint.first)
            leftEdge.add(leftPoint.second)
            rightEdge.add(rightPoint.first)
            rightEdge.add(rightPoint.second)
        }
    }
    if (leftEdge.size < 6) return null

    val polygon = FloatArray(leftEdge.size + rightEdge.size)
    leftEdge.toFloatArray().copyInto(polygon, 0)
    var outputIndex = leftEdge.size
    for (index in rightEdge.size - 2 downTo 0 step 2) {
        polygon[outputIndex] = rightEdge[index]
        polygon[outputIndex + 1] = rightEdge[index + 1]
        outputIndex += 2
    }
    return polygon
}

private fun mapMaskPointToNormalized(
    x: Float,
    y: Float,
    maskWidth: Int,
    maskHeight: Int,
    preprocessed: LetterboxResult,
    originalWidth: Int,
    originalHeight: Int
): Pair<Float, Float> {
    val inputX = x / maskWidth * preprocessed.inputWidth
    val inputY = y / maskHeight * preprocessed.inputHeight
    val originalX = OnnxImagePreprocessor.toOriginalX(inputX, preprocessed)
        .coerceIn(0f, max(0f, originalWidth - 1f))
    val originalY = OnnxImagePreprocessor.toOriginalY(inputY, preprocessed)
        .coerceIn(0f, max(0f, originalHeight - 1f))
    return (
        if (originalWidth > 0) originalX / originalWidth else 0f
    ) to (
        if (originalHeight > 0) originalY / originalHeight else 0f
    )
}
