package com.manga.translate.rendering

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint

/** Draw the shaped text twice so ligatures, spaces and vertical punctuation follow the final layout. */
internal object FreeTextBackground {
    fun draw(samples: Bitmap, bounds: RectF, paint: TextPaint, opacity: Int, drawText: () -> Unit) {
        val original = TextPaint(paint)
        try {
            val shader = BitmapShader(samples, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply {
                setRectToRect(RectF(0f, 0f, samples.width.toFloat(), samples.height.toFloat()),
                    bounds, Matrix.ScaleToFit.FILL)
            })
            paint.shader = shader
            // Retain the sampled hue while separating the backing from the selected ink.
            paint.colorFilter = LightingColorFilter(0xff999999.toInt(),
                if (original.color == Color.WHITE) 0 else 0x00666666)
            paint.alpha = opacity
            paint.isFilterBitmap = true
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = (paint.textSize * 0.22f).coerceAtLeast(1f)
            drawText()
        } finally {
            paint.set(original)
        }
        drawText()
    }
}
