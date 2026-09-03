package com.manga.translate.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
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
import kotlin.math.roundToInt

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
    val detectionComplete: Boolean = true
)

private const val MASK_COEFFICIENT_COUNT = 32
private const val END_TO_END_FEATURE_COUNT = 6 + MASK_COEFFICIENT_COUNT
private const val END_TO_END_DETECTION_COUNT = 300

/**
 * YOLO26n-seg speech-bubble detector.
 *
 * The exported end-to-end model has one class (`bubble`) and emits 300 rows as
 * [x1, y1, x2, y2, confidence, classId, 32 mask coefficients], plus
 * [1, 32, 368, 368] mask prototypes. Candidate selection is embedded in the
 * graph, so only confidence filtering and mask reconstruction remain here.
 */
class BubbleDetector(
    private val context: Context,
    private val modelAssetName: String = DEFAULT_MODEL_ASSET,
    private val threadProfile: OnnxThreadProfile = OnnxThreadProfile.LIGHT,
    private val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
) {
    private val env = OnnxRuntimeSupport.environment()
    private val session: OrtSession = createSession()
    private val inputName: String
    private val inputShape: LongArray

    init {
        val input = session.inputInfo.entries.first()
        inputName = input.key
        inputShape = (input.value.info as TensorInfo).shape
    }

    @Synchronized
    fun detectRegions(bitmap: Bitmap): UnifiedRegionDetection {
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            return UnifiedRegionDetection(emptyList(), emptyList())
        }

        val inputHeight = inputShape.getOrNull(2)?.takeIf { it > 0 }?.toInt()
            ?: DEFAULT_INPUT_SIZE
        val inputWidth = inputShape.getOrNull(3)?.takeIf { it > 0 }?.toInt()
            ?: DEFAULT_INPUT_SIZE
        val preprocessed = OnnxImagePreprocessor.letterbox(bitmap, inputWidth, inputHeight)
        // Ultralytics ONNX exports expect RGB values normalized to 0..1;
        // normalization is not embedded in this model graph.
        val inputBuffer = OnnxImagePreprocessor.bitmapToRgbChwFloat(preprocessed.bitmap)
        preprocessed.bitmap.recycle()

        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuffer),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        )
        inputTensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output0 = outputs[0] as? OnnxTensor
                    ?: return UnifiedRegionDetection(emptyList(), emptyList())
                val output0Shape = (output0.info as TensorInfo).shape
                val configuredThreshold = settingsStore.loadBubbleConfThresholdPercent() / 100f
                val detections = parseDetections(
                    buffer = output0.floatBuffer,
                    shape = output0Shape,
                    configuredThreshold = configuredThreshold
                )

                val prototypes = if (outputs.size() >= 2) {
                    val output1 = outputs[1] as? OnnxTensor
                    output1?.let { tensor ->
                        val shape = (tensor.info as TensorInfo).shape
                        parsePrototypes(tensor.floatBuffer, shape)
                    }
                } else {
                    null
                }

                if (settingsStore.loadModelIoLogging()) {
                    val maxConfidence = detections.maxOfOrNull { it.confidence } ?: 0f
                    AppLogger.log(
                        "BubbleDetector",
                        "Detections=${detections.size}, configured=$configuredThreshold, " +
                            "max balloon=${formatConfidence(maxConfidence)}, " +
                            "input=${inputWidth}x$inputHeight"
                    )
                }

                val balloons = ArrayList<BubbleDetection>(detections.size)
                for (raw in detections) {
                    val rect = raw.toRect(preprocessed, bitmap.width, bitmap.height)
                    if (rect.width() <= 1f || rect.height() <= 1f) continue
                    val contour = prototypes?.let { proto ->
                        computeMaskContour(
                            detection = raw,
                            prototypes = proto.data,
                            protoHeight = proto.height,
                            protoWidth = proto.width,
                            preprocessed = preprocessed,
                            originalWidth = bitmap.width,
                            originalHeight = bitmap.height,
                            inputWidth = inputWidth,
                            inputHeight = inputHeight
                        )
                    }
                    balloons.add(
                        BubbleDetection(
                            rect = rect,
                            confidence = raw.confidence,
                            classId = CLASS_BALLOON,
                            maskContour = contour
                        )
                    )
                }
                val deduplicatedBalloons = deduplicateBubbleDetections(balloons)
                val duplicateCount = balloons.size - deduplicatedBalloons.size
                if (duplicateCount > 0) {
                    AppLogger.log(
                        "BubbleDetector",
                        "Removed $duplicateCount overlapping bubble detection(s), " +
                            "kept ${deduplicatedBalloons.size}"
                    )
                }
                if (settingsStore.loadModelIoLogging()) {
                    AppLogger.log(
                        "BubbleDetector",
                        "Balloons kept=${deduplicatedBalloons.size}; " +
                            "maskContours=${deduplicatedBalloons.count { it.maskContour != null }}"
                    )
                }
                return UnifiedRegionDetection(
                    balloons = deduplicatedBalloons,
                    freeTextRects = emptyList()
                )
            }
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): List<BubbleDetection> = detectRegions(bitmap).balloons

    private fun parseDetections(
        buffer: FloatBuffer,
        shape: LongArray,
        configuredThreshold: Float
    ): List<RawDetection> {
        if (
            shape.size != 3 ||
            shape[0] != 1L ||
            shape[1] != END_TO_END_DETECTION_COUNT.toLong() ||
            shape[2] != END_TO_END_FEATURE_COUNT.toLong()
        ) {
            return emptyList()
        }

        val threshold = effectiveDetectionConfidenceThreshold(
            classId = CLASS_BALLOON,
            configuredThreshold = configuredThreshold
        )
        val values = buffer.duplicate()
        values.rewind()
        val result = ArrayList<RawDetection>()
        for (index in 0 until END_TO_END_DETECTION_COUNT) {
            val base = index * END_TO_END_FEATURE_COUNT
            val row = FloatArray(END_TO_END_FEATURE_COUNT) { featureIndex ->
                values.get(base + featureIndex)
            }
            val decoded = decodeEndToEndBubbleRow(row) ?: continue
            if (decoded.classId != CLASS_BALLOON || decoded.confidence < threshold) continue
            result.add(
                RawDetection(
                    cx = (decoded.left + decoded.right) / 2f,
                    cy = (decoded.top + decoded.bottom) / 2f,
                    width = decoded.right - decoded.left,
                    height = decoded.bottom - decoded.top,
                    confidence = decoded.confidence.coerceIn(0f, 1f),
                    maskCoefficients = decoded.maskCoefficients
                )
            )
        }
        return result
    }

    private fun parsePrototypes(buffer: FloatBuffer, shape: LongArray): PrototypeData? {
        if (shape.size != 4 || shape[0] != 1L) return null
        val channels = shape[1].toInt()
        val height = shape[2].toInt()
        val width = shape[3].toInt()
        if (channels != MASK_COEFFICIENT_COUNT || height <= 0 || width <= 0) return null
        val expected = channels * height * width
        if (buffer.remaining() < expected && buffer.capacity() < expected) return null
        val values = buffer.duplicate()
        values.rewind()
        val data = FloatArray(expected)
        values.get(data)
        return PrototypeData(data = data, height = height, width = width)
    }

    /**
     * Reconstruct a compact outer polygon from the prototype mask. Sampling
     * scanlines keeps the Android overlay lightweight while preserving the
     * useful non-rectangular speech-bubble shape.
     */
    private fun computeMaskContour(
        detection: RawDetection,
        prototypes: FloatArray,
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
                        prototypes[coefficient * protoHeight * protoWidth + protoOffset + localX]
                }
                foreground[localY * maskWidth + localX] = score >= 0f
            }
        }
        val mainComponent = retainLargestConnectedMaskComponent(
            foreground,
            maskWidth,
            maskHeight
        ) ?: return null

        val sampleCount = (y2 - y1).coerceIn(4, MAX_CONTOUR_SAMPLES)
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

    private fun createSession(): OrtSession {
        return OnnxRuntimeSupport.getOrCreateSession(
            cacheDir = context.cacheDir,
            assetProvider = context.assets::open,
            assetName = modelAssetName,
            threadProfile = threadProfile,
            useXnnpack = settingsStore.loadUseXnnpack()
        )
    }

    private fun formatConfidence(value: Float): String = "%.3f".format(value)

    companion object {
        const val DEFAULT_MODEL_ASSET = "models/detection/manga-bubble-seg-yolo26n-1472.onnx"
        const val CLASS_BALLOON = 0
        // Kept for the shared confidence helper and existing unit tests. The
        // segmentation model itself only emits CLASS_BALLOON.
        const val CLASS_TEXT = 1
        private const val DEFAULT_INPUT_SIZE = 1472
        private const val MAX_CONTOUR_SAMPLES = 48
    }
}

private data class PrototypeData(
    val data: FloatArray,
    val height: Int,
    val width: Int
)

private data class RawDetection(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
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

internal data class EndToEndBubbleRow(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val classId: Int,
    val maskCoefficients: FloatArray
)

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
    return BooleanArray(foreground.size) { labels[it] == largestLabel }
}

internal fun decodeEndToEndBubbleRow(featureRow: FloatArray): EndToEndBubbleRow? {
    if (featureRow.size != END_TO_END_FEATURE_COUNT) return null
    val left = featureRow[0]
    val top = featureRow[1]
    val right = featureRow[2]
    val bottom = featureRow[3]
    val confidence = featureRow[4]
    val classValue = featureRow[5]
    if (
        !left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
        !confidence.isFinite() || !classValue.isFinite() || right <= left || bottom <= top
    ) {
        return null
    }
    val classId = classValue.roundToInt()
    if (abs(classValue - classId) > 1e-3f) return null
    val coefficients = featureRow.copyOfRange(6, featureRow.size)
    if (coefficients.any { !it.isFinite() }) return null
    return EndToEndBubbleRow(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        confidence = confidence,
        classId = classId,
        maskCoefficients = coefficients
    )
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
        normalized
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
