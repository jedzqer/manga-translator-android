package com.manga.translate.settings.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.manga.translate.settings.CustomThemeColors
import com.manga.translate.theming.ThemePalette

class CustomThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val radius = 0f
    private val lineRadius = 3f * density
    private val buttonTextRadius = 2f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundBounds = RectF()
    private val cardBounds = RectF()
    private val cardShadowBounds = RectF()
    private val heroBounds = RectF()
    private val titleBounds = RectF()
    private val subtitleBounds = RectF()
    private val buttonBounds = RectF()
    private val buttonTextBounds = RectF()
    private val buttonShadowBounds = RectF()
    private var palette = ThemePalette.from(CustomThemeColors.DEFAULT)
    var colors: CustomThemeColors = CustomThemeColors.DEFAULT
        set(value) {
            field = value
            palette = ThemePalette.from(value)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = palette.background
        canvas.drawRoundRect(backgroundBounds, radius, radius, paint)
        paint.color = ColorUtils.blendARGB(palette.background, palette.outline, 0.24f)
        paint.strokeWidth = density
        var lineY = 12f * density
        while (lineY < height) {
            canvas.drawLine(0f, lineY, width.toFloat(), lineY, paint)
            lineY += 16f * density
        }
        paint.color = 0x38000000
        canvas.drawRect(cardShadowBounds, paint)
        paint.color = palette.surface
        canvas.drawRoundRect(cardBounds, radius, radius, paint)
        paint.color = palette.heroStart
        canvas.drawRoundRect(heroBounds, radius, radius, paint)
        paint.color = palette.foreground
        canvas.drawRoundRect(titleBounds, lineRadius, lineRadius, paint)
        paint.color = palette.mutedForeground
        canvas.drawRoundRect(subtitleBounds, lineRadius, lineRadius, paint)
        paint.color = 0x38000000
        canvas.drawRect(buttonShadowBounds, paint)
        paint.color = palette.buttonFill
        canvas.drawRoundRect(buttonBounds, radius, radius, paint)
        paint.color = palette.buttonText
        canvas.drawRoundRect(buttonTextBounds, buttonTextRadius, buttonTextRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = palette.outline
        canvas.drawRoundRect(cardBounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 14f * density
        backgroundBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        cardBounds.set(margin, margin, w - margin, h - margin)
        cardShadowBounds.set(cardBounds)
        cardShadowBounds.offset(3f * density, 4f * density)
        heroBounds.set(
            cardBounds.left + 14f * density, cardBounds.top + 14f * density,
            cardBounds.left + 112f * density, cardBounds.top + 34f * density
        )
        titleBounds.set(
            cardBounds.left + 14f * density,
            cardBounds.top + 50f * density,
            cardBounds.right - 45f * density,
            cardBounds.top + 56f * density
        )
        subtitleBounds.set(
            cardBounds.left + 14f * density,
            cardBounds.top + 65f * density,
            cardBounds.right - 70f * density,
            cardBounds.top + 70f * density
        )
        buttonBounds.set(
            cardBounds.right - 92f * density,
            cardBounds.bottom - 36f * density,
            cardBounds.right - 14f * density,
            cardBounds.bottom - 12f * density
        )
        buttonShadowBounds.set(
            buttonBounds.left + 2f * density,
            buttonBounds.top + 2f * density,
            buttonBounds.right + 2f * density,
            buttonBounds.bottom + 2f * density
        )
        buttonTextBounds.set(
            buttonBounds.left + 18f * density,
            buttonBounds.centerY() - 2f * density,
            buttonBounds.right - 18f * density,
            buttonBounds.centerY() + 2f * density
        )
    }
}
