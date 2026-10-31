package com.manga.translate

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.manga.translate.detection.isSeamCrossingDetection
import com.manga.translate.detection.planSeamDetectionBand
import com.manga.translate.model.BubbleSource
import com.manga.translate.model.OcrBubble
import com.manga.translate.model.PageOcrResult
import com.manga.translate.platform.PipelineBitmapDecoder
import com.manga.translate.platform.StitchedBitmapCropSource
import com.manga.translate.translation.applySeamBubbles
import com.manga.translate.translation.repairOcrWindowSeams
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageSeamRepairTest {
    @Test
    fun `stitched source scales lower page to upper width below the seam`() = runBlocking {
        val upper = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val lower = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val stitched = StitchedBitmapCropSource(
            PipelineBitmapDecoder.openCropSource(upper),
            PipelineBitmapDecoder.openCropSource(lower)
        )
        assertEquals(100, stitched.width)
        assertEquals(50, stitched.seamY)
        assertEquals(100, stitched.height)

        val crop = stitched.decodeRegion(RectF(0f, 30f, 100f, 70f))
        assertNotNull(crop)
        crop!!
        assertEquals(100, crop.width)
        assertEquals(40, crop.height)
        assertEquals(Color.RED, crop.getPixel(50, 5))
        assertEquals(Color.BLUE, crop.getPixel(50, 35))
    }

    @Test
    fun `seam band needs room on both sides`() {
        val band = planSeamDetectionBand(1000, 3000, 6000)!!
        assertEquals(2750f, band.top)
        assertEquals(3250f, band.bottom)
        assertNull(planSeamDetectionBand(1000, 0, 6000))
        assertNull(planSeamDetectionBand(1000, 5995, 6000))
        assertEquals(0f, planSeamDetectionBand(1000, 100, 6000)!!.top)
    }

    @Test
    fun `only bubbles crossing the seam and closed inside the band are kept`() {
        val band = RectF(0f, 2750f, 1000f, 3250f)
        assertTrue(isSeamCrossingDetection(RectF(100f, 2850f, 400f, 3150f), band, 3000, 6000))
        // Entirely on one side of the seam: ordinary page detection already handles it.
        assertFalse(isSeamCrossingDetection(RectF(100f, 2800f, 400f, 2990f), band, 3000, 6000))
        assertFalse(isSeamCrossingDetection(RectF(100f, 3005f, 400f, 3200f), band, 3000, 6000))
        // Cut by the band edge: re-detection would only reproduce a truncated bubble.
        assertFalse(isSeamCrossingDetection(RectF(100f, 2750f, 400f, 3150f), band, 3000, 6000))
        assertFalse(isSeamCrossingDetection(RectF(100f, 2850f, 400f, 3250f), band, 3000, 6000))
        // A band clamped to the stitched page edge is not a cut.
        val clamped = RectF(0f, 0f, 1000f, 350f)
        assertTrue(isSeamCrossingDetection(RectF(100f, 0f, 400f, 200f), clamped, 100, 6000))
    }

    @Test
    fun `recovered bubble replaces fragments on both pages and keeps neighbours`() {
        val upper = page(0, 1000, 1000, listOf(
            OcrBubble(0, RectF(100f, 100f, 300f, 200f), "keep top"),
            OcrBubble(1, RectF(400f, 920f, 600f, 1000f), "upper half", BubbleSource.TEXT_DETECTOR)
        ))
        // Lower page is twice as wide, so its coordinates are halved in upper space.
        val lower = page(1, 2000, 2000, listOf(
            OcrBubble(0, RectF(800f, 0f, 1200f, 120f), "lower half", BubbleSource.TEXT_DETECTOR),
            OcrBubble(1, RectF(800f, 1000f, 1200f, 1200f), "keep bottom")
        ))
        val recovered = OcrBubble(-1, RectF(390f, 900f, 610f, 1070f), "whole", BubbleSource.BUBBLE_DETECTOR)

        val (repairedUpper, repairedLower) = applySeamBubbles(upper, lower, listOf(recovered))

        assertEquals(listOf("keep top", "whole"), repairedUpper.bubbles.map { it.text })
        assertEquals(listOf(0, 1), repairedUpper.bubbles.map { it.id })
        assertEquals(1070f, repairedUpper.bubbles[1].rect.bottom)
        assertEquals(listOf("keep bottom"), repairedLower.bubbles.map { it.text })
        assertEquals(0, repairedLower.bubbles.single().id)
    }

    @Test
    fun `seam repair only runs for adjacent pages with regions near the seam`() = runBlocking {
        val near = page(0, 1000, 1000, listOf(OcrBubble(0, RectF(100f, 900f, 300f, 1000f), "edge")))
        val next = page(1, 1000, 1000, emptyList())
        val far = page(2, 1000, 1000, listOf(OcrBubble(0, RectF(100f, 400f, 300f, 500f), "middle")))
        val last = page(3, 1000, 1000, listOf(OcrBubble(0, RectF(100f, 400f, 300f, 500f), "middle")))
        val positions = listOf(near, next, far, last).mapIndexed { index, page -> page.imageFile to index }.toMap()
        val calls = mutableListOf<Pair<File, File>>()

        val result = repairOcrWindowSeams(listOf(near, next, null, far, last), positions) { upper, lower ->
            calls += upper.imageFile to lower.imageFile
            null
        }

        assertEquals(listOf(near.imageFile to next.imageFile), calls)
        assertEquals(listOf(near, next, null, far, last), result)
    }

    private fun page(index: Int, width: Int, height: Int, bubbles: List<OcrBubble>) = PageOcrResult(
        imageFile = File("$index.jpg"), width = width, height = height, bubbles = bubbles
    )
}
