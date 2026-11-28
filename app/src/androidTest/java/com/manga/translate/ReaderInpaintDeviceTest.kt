package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.BubbleTranslation
import com.manga.translate.model.TranslationResult
import com.manga.translate.reader.FloatingTranslationView
import com.manga.translate.reader.ManualBubbleShape
import com.manga.translate.rendering.FreeTextInpaint
import com.manga.translate.settings.NormalBubbleRenderSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderInpaintDeviceTest {
    @Test
    fun oversizedSelectionErasesCaptionAndPreservesCrossingLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(500, 400, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val page = RectF(0f, 0f, 500f, 400f)
        Canvas(source).apply {
            drawColor(Color.WHITE)
            for (y in 35..135 step 10) drawLine(0f, y.toFloat(), 500f, y + 40f, paint)
            paint.textSize = 32f
            drawText("MANGA TEXT", 135f, 240f, paint)
        }
        val repaired = source.copy(Bitmap.Config.ARGB_8888, true)
        val patch = requireNotNull(FreeTextInpaint.create(context, source, null, RectF(.05f, .05f, .95f, .95f)))
        try {
            patch.draw(Canvas(repaired), page, 255)
            for (y in 35..175) for (x in 0 until source.width) {
                assertEquals(source.getPixel(x, y), repaired.getPixel(x, y))
            }
            var inkBefore = 0
            var inkAfter = 0
            for (y in 200..245) for (x in 125..360) {
                if (Color.red(source.getPixel(x, y)) < 128) inkBefore++
                if (Color.red(repaired.getPixel(x, y)) < 128) inkAfter++
            }
            assertTrue("Caption ink remaining: $inkAfter / $inkBefore", inkAfter < inkBefore / 10)
        } finally {
            patch.bitmap.recycle()
            repaired.recycle()
            source.recycle()
        }
    }

    @Test
    fun readerRepairsCurrentRegionFromOriginalUnderEmptyAndTranslatedBubbles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 200) {
            val ink = (x % 100 in 29..31 || x % 100 in 12..14) && y in 12..76
            source.setPixel(x, y, if (ink) Color.BLACK else Color.rgb(160 + x % 32, 180 + y % 30, 160))
        }
        val file = File.createTempFile("inpaint", ".png", context.cacheDir)
        val frame = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        lateinit var view: FloatingTranslationView
        var rect = RectF(10f, 10f, 90f, 90f)
        var bubble = BubbleTranslation.pending(1, rect, source = BubbleSource.MANUAL,
            maskContour = ManualBubbleShape.ELLIPSE.contour(rect, 200, 100))

        fun awaitRepair(x: Int) {
            val deadline = SystemClock.uptimeMillis() + 10000
            do {
                instrumentation.runOnMainSync {
                    val canvas = Canvas(frame)
                    canvas.drawBitmap(source, 0f, 0f, null)
                    view.draw(canvas)
                }
                if (Color.green(frame.getPixel(x, 45)) > 140) return
                SystemClock.sleep(25)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("Stroke at ($x,45) was still visible")
        }
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            instrumentation.runOnMainSync {
                view = FloatingTranslationView(context)
                view.layout(0, 0, 200, 100)
                view.setNormalBubbleRenderSettings(NormalBubbleRenderSettings(
                    opacityPercent = 100, freeBubbleSizeAdjustPercent = 0, useHorizontalText = true,
                    freeBubbleOpacityPercent = 100, autoAdaptFreeBubbleColor = true, shrinkPercent = 0))
                view.setDisplayRect(RectF(0f, 0f, 200f, 100f))
                view.setSourceImageFile(file)
                view.setTranslations(TranslationResult(file.name, 200, 100, listOf(bubble)))
                view.setEditMode(true)
            }
            awaitRepair(30)
            assertEquals("outside ellipse", Color.BLACK, frame.getPixel(13, 14))
            assertEquals("untouched texture", source.getPixel(53, 54), frame.getPixel(53, 54))
            instrumentation.runOnMainSync {
                bubble = bubble.withManualText("I")
                view.setTranslations(TranslationResult(file.name, 200, 100, listOf(bubble)))
                view.setOffsets(mapOf(1 to (100f to 0f)))
            }
            awaitRepair(130)
            assertEquals("former position", Color.BLACK, frame.getPixel(30, 45))
            instrumentation.runOnMainSync {
                rect = RectF(105f, 30f, 145f, 60f)
                bubble = BubbleTranslation.translated(1, rect, "I", BubbleSource.TEXT_DETECTOR)
                view.setTranslations(TranslationResult(file.name, 200, 100, listOf(bubble)))
                view.setOffsets(emptyMap())
                view.setEditMode(false)
            }
            awaitRepair(130)
            assertEquals("outside resized region", Color.BLACK, frame.getPixel(130, 70))
            assertTrue("original ink remains", source.getPixel(130, 45) == Color.BLACK)
        } finally {
            file.delete()
            frame.recycle()
            source.recycle()
        }
    }
}
