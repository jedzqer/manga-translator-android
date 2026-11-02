package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.reader.readingTileTransform
import com.manga.translate.reader.ReadingRegionImageView
import com.manga.translate.reader.ReadingTiledBitmapDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp-notnight")
class ReadingRegionImageViewTest {
    @Test fun `scrolled and zoomed tile viewport stays aligned with source bubble coordinates`() {
        val matrix = Matrix().apply { setScale(0.64f, 0.64f); postTranslate(-90f, 20f) }
        for (viewport in listOf(Rect(0, 0, 320, 480), Rect(0, 3200, 320, 3680),
            Rect(0, 6200, 320, 6400))) {
            for (sample in listOf(1, 2)) {
                val transform = requireNotNull(readingTileTransform(matrix, viewport, sample))
                val sourcePoint = floatArrayOf(350f, 5500f)
                val displayPoint = floatArrayOf(sourcePoint[0] / sample, sourcePoint[1] / sample)
                matrix.mapPoints(displayPoint)
                val tileX = viewport.width() / 2f +
                    (sourcePoint[0] - transform.center.x) * transform.scale
                val tileY = viewport.height() / 2f +
                    (sourcePoint[1] - transform.center.y) * transform.scale
                assertEquals(displayPoint[0], tileX + viewport.left, 0.001f)
                assertEquals(displayPoint[1], tileY + viewport.top, 0.001f)
            }
        }
        assertNull(readingTileTransform(Matrix().apply { setScale(0f, 0f) }, Rect(0, 0, 320, 480), 1))
    }

    @Test fun `wrap content preserves the full long page height`() {
        val view = ReadingRegionImageView(ApplicationProvider.getApplicationContext())
        view.adjustViewBounds = true
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.setImageDrawable(ReadingTiledBitmapDrawable.empty(1000, 20000))
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(320, view.measuredWidth)
        assertEquals(6400, view.measuredHeight)
    }

    @Test fun `regular bitmap uses the same matrix as the translation overlay`() {
        val view = ReadingRegionImageView(ApplicationProvider.getApplicationContext())
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        view.scaleType = ImageView.ScaleType.MATRIX
        view.setImageDrawable(BitmapDrawable(view.resources, bitmap))
        val matrix = Matrix().apply { setScale(2f, 2f); postTranslate(30f, 40f) }
        view.imageMatrix = matrix
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 320, 480)
        val output = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(output))
        assertEquals(matrix, view.imageMatrix)
        assertEquals(Color.RED, output.getPixel(40, 50))
        assertEquals(Color.TRANSPARENT, output.getPixel(10, 10))
        view.setRegionSource(null)
        view.setImageDrawable(null)
        assertNull(view.drawable)
        assertFalse(bitmap.isRecycled) // The reader's bitmap lease owns this bitmap.
        output.recycle()
        bitmap.recycle()
    }
}
