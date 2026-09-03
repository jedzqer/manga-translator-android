package com.manga.translate.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale

/**
 * 气泡模型输入的预处理结果。
 *
 * 正向变换：输入画布坐标 = 原图坐标 × gain + pad（X/Y 分轴记录，支持非等比拉伸）。
 * 逆向还原统一使用 [OnnxImagePreprocessor.toOriginalX] / [toOriginalY]。
 */
internal data class LetterboxResult(
    val bitmap: Bitmap,
    val gainX: Float,
    val gainY: Float,
    val padX: Float,
    val padY: Float,
    val inputWidth: Int,
    val inputHeight: Int
)

internal object OnnxImagePreprocessor {
    /**
     * 将原图变换为模型的固定输入尺寸。
     *
     * - 高度充足（gainX <= gainY，等比缩放后宽度方向留白）：维持原有 letterbox，
     *   等比缩放后水平居中，左右留灰色 pad。
     * - 高度不足的短图（gainY > gainX，等比缩放后上下会出现灰边）：改为把宽度、
     *   高度分别拉伸到输入尺寸，整张画布被图像填满（pad 为 0）。短图中的气泡在
     *   模型输入里因此被放大，更易识别。
     *
     * 两条路径都通过 [LetterboxResult] 的分轴 gain/pad 记录变换，检测结果的坐标
     * 还原不依赖具体路径。
     */
    fun letterbox(
        bitmap: Bitmap,
        inputWidth: Int,
        inputHeight: Int,
        padColor: Int = Color.rgb(114, 114, 114),
        afterDraw: ((Canvas, Float, Float, Float) -> Unit)? = null
    ): LetterboxResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val fitX = (inputWidth.toFloat() / srcW).coerceAtLeast(1e-6f)
        val fitY = (inputHeight.toFloat() / srcH).coerceAtLeast(1e-6f)

        if (fitY > fitX) {
            // 高度不足的短图：垂直拉伸填满输入，不再上下留灰边。
            val stretched = bitmap.scale(inputWidth, inputHeight)
            val filled = createBitmap(inputWidth, inputHeight)
            val canvas = Canvas(filled)
            canvas.drawColor(padColor)
            canvas.drawBitmap(stretched, 0f, 0f, null)
            // afterDraw 的 gain 参数沿用 X 轴缩放；拉伸路径 pad 恒为 0。
            afterDraw?.invoke(canvas, fitX, 0f, 0f)
            if (stretched !== bitmap) {
                stretched.recycle()
            }
            return LetterboxResult(
                bitmap = filled,
                gainX = fitX,
                gainY = fitY,
                padX = 0f,
                padY = 0f,
                inputWidth = inputWidth,
                inputHeight = inputHeight
            )
        }

        // 高度充足：等比 letterbox，宽度方向留灰边（保持既有行为）。
        val gain = fitY
        val newW = (srcW * gain).toInt().coerceAtLeast(1)
        val newH = (srcH * gain).toInt().coerceAtLeast(1)

        val resized = bitmap.scale(newW, newH)
        val padded = createBitmap(inputWidth, inputHeight)
        val canvas = Canvas(padded)
        canvas.drawColor(padColor)
        val padX = ((inputWidth - newW) / 2f).coerceAtLeast(0f)
        val padY = ((inputHeight - newH) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(resized, padX, padY, null)
        afterDraw?.invoke(canvas, gain, padX, padY)
        if (resized !== bitmap) {
            resized.recycle()
        }

        return LetterboxResult(
            bitmap = padded,
            gainX = gain,
            gainY = gain,
            padX = padX,
            padY = padY,
            inputWidth = inputWidth,
            inputHeight = inputHeight
        )
    }

    /** 输入画布 X 坐标 → 原图 X 坐标（[letterbox] 正向变换的逆变换）。 */
    fun toOriginalX(inputX: Float, preprocessed: LetterboxResult): Float =
        (inputX - preprocessed.padX) / preprocessed.gainX

    /** 输入画布 Y 坐标 → 原图 Y 坐标（[letterbox] 正向变换的逆变换）。 */
    fun toOriginalY(inputY: Float, preprocessed: LetterboxResult): Float =
        (inputY - preprocessed.padY) / preprocessed.gainY

    fun bitmapToRgbChwFloat(bitmap: Bitmap): FloatArray {
        return bitmapToRgbChwFloat(bitmap, normalize = true)
    }

    fun bitmapToRgbChwFloat255(bitmap: Bitmap): FloatArray {
        return bitmapToRgbChwFloat(bitmap, normalize = false)
    }

    private fun bitmapToRgbChwFloat(bitmap: Bitmap, normalize: Boolean): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val planeSize = width * height
        val input = FloatArray(3 * planeSize)
        val scale = if (normalize) 1f / 255f else 1f
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap[x, y]
                input[offset] = ((pixel shr 16) and 0xFF) * scale
                input[offset + planeSize] = ((pixel shr 8) and 0xFF) * scale
                input[offset + 2 * planeSize] = (pixel and 0xFF) * scale
                offset++
            }
        }
        return input
    }
}
