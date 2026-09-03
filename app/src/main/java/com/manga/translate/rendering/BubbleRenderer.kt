package com.manga.translate.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationResult
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.ImageProcessingGuards
import com.manga.translate.settings.SettingsStore
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BubbleRenderer(context: Context) {
    private companion object {
        private const val DEFAULT_TEXT_COLOR = 0xFF1B1B1B.toInt()
    }

    private val appContext = context.applicationContext
    private val bubbleRenderSettings = SettingsStore(appContext).loadNormalBubbleRenderSettings()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_TEXT_COLOR
        applyInitialTypefaceSettings(this)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bubblePath = Path()
    private val bubbleBounds = RectF()
    private val textRect = RectF()

    suspend fun render(
        source: Bitmap,
        translation: TranslationResult,
        verticalLayoutEnabled: Boolean
    ): Bitmap {
        ensureTypefaceAsync()
        return ImageProcessingGuards.withRenderPermit(
            width = source.width,
            height = source.height,
            tag = "BubbleRenderer"
        ) {
            try {
                renderInternal(source, translation, verticalLayoutEnabled)
            } catch (e: OutOfMemoryError) {
                AppLogger.log(
                    "BubbleRenderer",
                    "Render OOM for ${source.width}x${source.height}, bubbles=${translation.bubbles.size}",
                    e
                )
                throw e
            }
        }
    }

    private fun applyInitialTypefaceSettings(paint: TextPaint) {
        val typeface = BubbleFontResolver.resolveTypeface(
            appContext,
            bubbleRenderSettings.font,
            customUrl = bubbleRenderSettings.customFontUrl,
            customFileName = bubbleRenderSettings.customFontFileName,
            tag = "normal"
        )
        val style = if (bubbleRenderSettings.isBold) Typeface.BOLD else Typeface.NORMAL
        paint.typeface = Typeface.create(typeface, style)
    }

    private suspend fun ensureTypefaceAsync() {
        val typeface = withContext(Dispatchers.IO) {
            BubbleFontResolver.ensureTypeface(
                appContext,
                bubbleRenderSettings.font,
                bubbleRenderSettings.customFontUrl,
                bubbleRenderSettings.customFontFileName,
                "normal"
            )
        }
        val style = if (bubbleRenderSettings.isBold) Typeface.BOLD else Typeface.NORMAL
        textPaint.typeface = Typeface.create(typeface, style)
    }

    private fun renderInternal(
        source: Bitmap,
        translation: TranslationResult,
        verticalLayoutEnabled: Boolean
    ): Bitmap {
        val output = ensureMutableArgbBitmap(source)
        // When render mutates the caller's bitmap, preserve an untouched image for
        // background sampling so overlapping free bubbles do not sample prior fills.
        val samplingBitmap = if (output === source) {
            source.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw OutOfMemoryError("Failed to allocate bitmap sampling snapshot")
        } else {
            source
        }
        val canvas = Canvas(output)
        val scaleX = if (translation.width > 0) {
            output.width.toFloat() / translation.width.toFloat()
        } else {
            1f
        }
        val scaleY = if (translation.height > 0) {
            output.height.toFloat() / translation.height.toFloat()
        } else {
            1f
        }
        try {
            for (bubble in translation.bubbles) {
                val text = bubble.text.trim()
                if (text.isBlank()) continue
                val opacityAlpha = resolveBubbleOpacityAlpha(bubble)
                val useAutoAdaptColor = if (bubble.source.isFreeBubble) {
                    bubbleRenderSettings.autoAdaptFreeBubbleColor
                } else {
                    bubbleRenderSettings.autoAdaptBubbleColor
                }
                val bubbleFillColor = if (useAutoAdaptColor) {
                    val sampleLeft = bubble.rect.left * scaleX
                    val sampleTop = bubble.rect.top * scaleY
                    val sampleRight = bubble.rect.right * scaleX
                    val sampleBottom = bubble.rect.bottom * scaleY
                    BubbleColorSampler.sampleBackgroundColor(
                        samplingBitmap, sampleLeft, sampleTop, sampleRight, sampleBottom
                    ) ?: Color.WHITE
                } else {
                    Color.WHITE
                }
                fillPaint.color = bubbleFillColor
                textPaint.color = if (useAutoAdaptColor) {
                    BubbleTextColorResolver.resolveContrastingTextColor(
                        backgroundColor = bubbleFillColor,
                        darkTextColor = DEFAULT_TEXT_COLOR
                    )
                } else {
                    DEFAULT_TEXT_COLOR
                }
                fillPaint.alpha = opacityAlpha
                BubbleShapePaths.buildPath(
                    outPath = bubblePath,
                    bubble = bubble,
                    sourceWidth = translation.width,
                    sourceHeight = translation.height,
                    originX = 0f,
                    originY = 0f,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    shrinkPercent = resolveBubbleShrinkPercent(bubble)
                )
                drawBubble(
                    canvas = canvas,
                    text = text,
                    path = bubblePath,
                    verticalLayoutEnabled = verticalLayoutEnabled,
                    startFromTop = BubbleTextPlacement.spillsAcrossPage(
                        bubble.rect,
                        translation.height
                    )
                )
            }
        } finally {
            if (samplingBitmap !== source && !samplingBitmap.isRecycled) {
                samplingBitmap.recycle()
            }
        }
        return output
    }

    private fun ensureMutableArgbBitmap(source: Bitmap): Bitmap {
        return if (source.config == Bitmap.Config.ARGB_8888 && source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw OutOfMemoryError("Failed to allocate mutable ARGB_8888 bitmap copy")
        }
    }

    private fun resolveBubbleShrinkPercent(bubble: BubbleTranslation): Int {
        return if (bubble.source.usesFreeBubbleShrink) {
            bubbleRenderSettings.freeBubbleShrinkPercent
        } else if (bubble.source == BubbleSource.MANUAL) {
            0
        } else {
            bubbleRenderSettings.shrinkPercent
        }
    }

    private fun resolveBubbleOpacityAlpha(bubble: BubbleTranslation): Int {
        val opacityPercent = if (bubble.source.isFreeBubble) {
            bubbleRenderSettings.freeBubbleOpacityPercent
        } else {
            bubbleRenderSettings.opacityPercent
        }
        return ((opacityPercent.coerceIn(0, 100) / 100f) * 255f).toInt()
    }

    private fun drawBubble(
        canvas: Canvas,
        text: String,
        path: Path,
        verticalLayoutEnabled: Boolean,
        startFromTop: Boolean
    ) {
        path.computeBounds(bubbleBounds, true)
        if (bubbleBounds.width() <= 0f || bubbleBounds.height() <= 0f) return
        val textRect = BubbleTextScaling.resolveTextRect(path)
        if (textRect.width() <= 0f || textRect.height() <= 0f) return
        canvas.drawPath(path, fillPaint)
        drawTextInRect(
            canvas,
            text,
            textRect,
            verticalLayoutEnabled,
            startFromTop
        )
    }

    private fun drawTextInRect(
        canvas: Canvas,
        text: String,
        rect: RectF,
        verticalLayoutEnabled: Boolean,
        startFromTop: Boolean
    ) {
        if (verticalLayoutEnabled) {
            canvas.withClip(rect) {
                drawVerticalTextInRect(
                    this,
                    VerticalTextSymbolConverter.convert(text),
                    rect,
                    startFromTop
                )
            }
        } else {
            val textSize = resolveHorizontalTextSize(rect, text)
            val layout = buildLayout(text, rect.width().toInt().coerceAtLeast(1), textSize)
            val left = BubbleTextPlacement.horizontalTextLeft(rect, layout.width)
            val top = BubbleTextPlacement.horizontalTextTop(rect, layout.height, startFromTop)
            canvas.withClip(rect) {
                withTranslation(left, top) {
                    layout.draw(this)
                }
            }
        }
    }

    private fun resolveHorizontalTextSize(rect: RectF, text: String): Float {
        return BubbleTextScaling.findAutoHorizontalTextSize(
            text = text,
            maxWidth = rect.width().toInt().coerceAtLeast(1),
            maxHeight = rect.height().toInt().coerceAtLeast(1),
            buildLayout = ::buildLayout,
            layoutFits = BubbleTextScaling::layoutFits
        )
    }

    private fun buildLayout(text: String, width: Int, textSize: Float): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun drawVerticalTextInRect(
        canvas: Canvas,
        text: String,
        rect: RectF,
        startFromTop: Boolean
    ) {
        val maxWidth = rect.width().toInt().coerceAtLeast(1)
        val maxHeight = rect.height().toInt().coerceAtLeast(1)
        val textSize = findDefaultVerticalTextSize(text, maxWidth, maxHeight)
        val layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
        VerticalTextRenderer.draw(canvas, text, rect, textPaint, layout, startFromTop)
    }

    private fun findDefaultVerticalTextSize(
        text: String,
        maxWidth: Int,
        maxHeight: Int
    ): Float {
        return BubbleTextScaling.findLargestFittingTextSize(maxWidth, maxHeight) { textSize ->
            val layout = buildVerticalLayout(text, maxWidth, maxHeight, textSize)
            layout.columnWidth > 0f && layout.lineHeight > 0f && layout.fits
        }
    }

    private fun buildVerticalLayout(
        text: String,
        maxWidth: Int,
        maxHeight: Int,
        textSize: Float
    ): VerticalTextLayout {
        return VerticalTextLayoutCalculator.build(textPaint, text, maxWidth, maxHeight, textSize)
    }
}
