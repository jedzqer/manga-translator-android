package com.manga.translate

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.manga.translate.detection.OnnxRuntimeSupport
import com.manga.translate.detection.PageRegionDetector
import com.manga.translate.detection.RegionDetectionSelection
import com.manga.translate.model.BubbleSource
import com.manga.translate.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real-image regression coverage for the bubble/text detection pipeline.
 * Keep fixtures under test/resources/real_images and run this class explicitly when
 * changing detector thresholds, grouping, or region construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
// Legacy graphics silently no-ops every Canvas draw, so the visualization would come out
// as an unannotated copy of the fixture. Native graphics renders the overlay for real.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealImageDetectionRegressionTest {
    @Test
    fun `bubble overlap fixture produces one non-duplicated text region`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = context.javaClass.classLoader
            ?.getResourceAsStream("real_images/bubble_overlap_case.jpg")
            ?.use(BitmapFactory::decodeStream)
        // real_images/ is gitignored, so a fresh clone has no fixture. Skip instead of
        // failing, matching how an unavailable ONNX Runtime backend is handled below.
        assumeTrue(
            "Missing real image fixture: real_images/bubble_overlap_case.jpg; " +
                "drop the fixture into app/src/test/resources/real_images/ to run this test",
            bitmap != null
        )
        requireNotNull(bitmap)

        try {
            assertTrue("Fixture dimensions changed", bitmap.width == 940 && bitmap.height == 1830)
            assumeJvmOnnxRuntimeAvailable()
            val result = PageRegionDetector(
                context = context,
                settingsStore = SettingsStore(context)
            ).detect(
                bitmap = bitmap,
                logTag = "RealImageRegression",
                detectionSelection = RegionDetectionSelection.BUBBLES_AND_TEXT
            )
            val detection = requireNotNull(result) {
                "Detector returned no result after ONNX Runtime initialization"
            }
            RealImageDetectionVisualizer.write(
                source = bitmap,
                result = detection,
                fixtureName = "bubble_overlap_case"
            )
            val regions = detection.regions
            assertTrue("Expected detected regions on the fixture", regions.isNotEmpty())

            // No pair of regions of any source may substantially overlap. The detector used to
            // split the lower paragraph into two balloon halves with 0.64 overlap, and the text
            // line crossing both was promoted to a third, free-floating text region.
            val overlappingPair = regions.indices.firstNotNullOfOrNull { first ->
                (first + 1 until regions.size).firstNotNullOfOrNull { second ->
                    val ratio = intersectionOverSmaller(regions[first].rect, regions[second].rect)
                    if (ratio >= 0.55f) Triple(regions[first], regions[second], ratio) else null
                }
            }
            assertTrue(
                "Fixture contains overlapping duplicate regions: $overlappingPair",
                overlappingPair == null
            )

            // The lower paragraph is one balloon spanning all three of its text lines, which run
            // from x=59 to x=877 between y=899 and y=1030.
            val paragraph = regions.filter { it.rect.top > 700f }
            assertEquals("Lower paragraph must be a single region", 1, paragraph.size)
            val paragraphRect = paragraph.single().rect
            assertEquals(BubbleSource.BUBBLE_DETECTOR, paragraph.single().source)
            assertTrue(
                "Paragraph region must cover every text line, got $paragraphRect",
                paragraphRect.left <= 59f && paragraphRect.right >= 877f &&
                    paragraphRect.top <= 899f && paragraphRect.bottom >= 1030f
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun intersectionOverSmaller(first: RectF, second: RectF): Float {
        val intersection = (minOf(first.right, second.right) - maxOf(first.left, second.left))
            .coerceAtLeast(0f) *
            (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0f)
        val smaller = minOf(first.width() * first.height(), second.width() * second.height())
        return if (smaller <= 0f) 0f else intersection / smaller
    }

    private fun assumeJvmOnnxRuntimeAvailable() {
        val available = runCatching {
            OnnxRuntimeSupport.environment()
        }.isSuccess
        assumeTrue(
            "JVM ONNX Runtime native backend is unavailable on this host; run on Linux x64, " +
                "Windows x64, macOS ARM64, or an Android device",
            available
        )
    }
}
