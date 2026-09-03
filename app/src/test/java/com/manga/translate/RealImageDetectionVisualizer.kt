package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.manga.translate.detection.PageRegionDetectionResult
import com.manga.translate.model.BubbleSource
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Writes an annotated copy of a detector fixture for manual inspection. */
internal object RealImageDetectionVisualizer {
    fun write(
        source: Bitmap,
        result: PageRegionDetectionResult,
        fixtureName: String,
        outputDirectory: File = defaultOutputDirectory()
    ): File {
        outputDirectory.mkdirs()
        val output = File(outputDirectory, "$fixtureName.png")
        val annotated = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            drawRegions(annotated, result)
            FileOutputStream(output).use { stream ->
                check(annotated.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Unable to encode visualization: $output"
                }
            }
        } finally {
            annotated.recycle()
        }
        writeSummary(result, File(outputDirectory, "$fixtureName.json"))
        println("Real image visualization: ${output.absolutePath}")
        return output
    }

    fun defaultOutputDirectory(): File {
        val configured = System.getProperty(OUTPUT_DIRECTORY_PROPERTY)
        return if (!configured.isNullOrBlank()) {
            File(configured)
        } else {
            File(System.getProperty("user.dir", "."), "build/reports/real-image-detection")
        }
    }

    private fun drawRegions(bitmap: Bitmap, result: PageRegionDetectionResult) {
        val canvas = Canvas(bitmap)
        val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (bitmap.width.coerceAtMost(bitmap.height) / 300f).coerceAtLeast(3f)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (regionPaint.strokeWidth / 2f).coerceAtLeast(2f)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = (bitmap.width.coerceAtMost(bitmap.height) / 48f).coerceAtLeast(18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        result.regions.forEach { region ->
            val color = colorFor(region.source)
            regionPaint.color = color
            canvas.drawRect(region.rect, regionPaint)
            region.textLineRects.orEmpty().forEach { lineRect ->
                linePaint.color = color
                canvas.drawRect(lineRect, linePaint)
            }

            val label = "#${region.id} ${region.source.jsonValue}"
            val baseline = (region.rect.top - 8f).coerceAtLeast(labelPaint.textSize)
            val labelTop = baseline - labelPaint.textSize
            val labelWidth = labelPaint.measureText(label)
            labelBackgroundPaint.color = Color.argb(210, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(
                region.rect.left,
                labelTop,
                (region.rect.left + labelWidth + 12f).coerceAtMost(bitmap.width.toFloat()),
                baseline + 4f,
                labelBackgroundPaint
            )
            labelPaint.color = Color.WHITE
            canvas.drawText(label, region.rect.left + 6f, baseline, labelPaint)
        }
    }

    private fun writeSummary(result: PageRegionDetectionResult, output: File) {
        val regions = result.regions.joinToString(",\n") { region ->
            val rect = region.rect
            "    {\"id\":${region.id},\"source\":\"${region.source.jsonValue}\"," +
                "\"rect\":[${format(rect.left)},${format(rect.top)}," +
                "${format(rect.right)},${format(rect.bottom)}]}"
        }
        output.writeText(
            "{\n" +
                "  \"width\":${result.width},\n" +
                "  \"height\":${result.height},\n" +
                "  \"detectionComplete\":${result.detectionComplete},\n" +
                "  \"regionCount\":${result.regions.size},\n" +
                "  \"regions\":[\n$regions\n  ]\n}\n"
        )
    }

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun colorFor(source: BubbleSource): Int {
        return when (source) {
            BubbleSource.BUBBLE_DETECTOR -> Color.rgb(236, 64, 122)
            BubbleSource.TEXT_DETECTOR -> Color.rgb(0, 188, 212)
            BubbleSource.MANUAL -> Color.rgb(255, 193, 7)
            BubbleSource.UNKNOWN -> Color.rgb(156, 39, 176)
        }
    }

    private const val OUTPUT_DIRECTORY_PROPERTY = "realImageDetectionReportDir"
}
