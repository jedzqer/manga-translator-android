package com.manga.translate.theming

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import com.manga.translate.R

/** A quiet ruled-paper backing shared by the library, reader and settings pages. */
class NotebookPaperLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = ThemePaletteRuntime.customPalette
    private val colors = context.obtainStyledAttributes(
        R.styleable.NotebookPaperLayout
    ).let { values ->
        val resolved = intArrayOf(
            palette?.background ?: values.getColor(
                R.styleable.NotebookPaperLayout_android_colorBackground, 0xFFF4EEDC.toInt()
            ),
            palette?.outline ?: values.getColor(
                R.styleable.NotebookPaperLayout_outlineColor, 0xFFB6AA90.toInt()
            )
        )
        values.recycle()
        resolved
    }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = colors[0]
        canvas.drawColor(colors[0])

        paint.strokeWidth = density.coerceAtLeast(1f)
        paint.color = ColorUtils.blendARGB(colors[0], colors[1], 0.24f)
        val spacing = 28f * density
        var y = 24f * density
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += spacing
        }
    }
}
