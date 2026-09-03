package com.manga.translate

import android.view.View
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.library.RegionDetectionModeIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RegionDetectionModeIndicatorTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class Harness(context: android.content.Context) {
        val ids = listOf(1, 2, 3)
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val indicator = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val root = FrameLayout(context)

        init {
            ids.forEach { id ->
                group.addView(
                    RadioButton(context).apply {
                        this.id = id
                        layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.MATCH_PARENT, 1f)
                    }
                )
            }
            root.addView(indicator)
            root.addView(group)
        }

        /** Measures and lays out the tree, as a real layout pass would. */
        fun layout(width: Int = 300, height: Int = 44) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, width, height)
        }

        fun buttonLeft(index: Int): Float = group.getChildAt(index).left.toFloat()
    }

    private fun idleMainLooper() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `indicator positions itself once geometry becomes available`() {
        val harness = Harness(context)
        val subject = RegionDetectionModeIndicator(harness.group, harness.indicator)

        // Selection restored while the panel is still unlaid-out, e.g. from onResume
        // before the folder detail container has been measured.
        subject.setChecked(harness.ids[1], animate = false)
        assertNull("indicator cannot be placed without geometry", subject.targetTranslationX)

        harness.layout()

        assertEquals(harness.buttonLeft(1), harness.indicator.translationX, 0.5f)
        assertEquals(harness.group.getChildAt(1).width, harness.indicator.layoutParams.width)
    }

    @Test
    fun `switching mode after a relayout moves the indicator`() {
        val harness = Harness(context)
        val subject = RegionDetectionModeIndicator(harness.group, harness.indicator)
        subject.setChecked(harness.ids[0], animate = false)
        harness.layout()
        assertEquals(harness.buttonLeft(0), harness.indicator.translationX, 0.5f)

        // Reproduces the reading-tab round trip: the view is detached and re-attached,
        // then laid out again before the user taps a different mode.
        harness.root.removeView(harness.group)
        harness.root.addView(harness.group)
        harness.layout()

        subject.setChecked(harness.ids[2], animate = false)

        assertEquals(harness.buttonLeft(2), harness.indicator.translationX, 0.5f)
    }

    @Test
    fun `animated switch settles on the target`() {
        val harness = Harness(context)
        val subject = RegionDetectionModeIndicator(harness.group, harness.indicator, animationDurationMs = 0L)
        subject.setChecked(harness.ids[0], animate = false)
        harness.layout()

        subject.setChecked(harness.ids[1], animate = true)
        idleMainLooper()

        assertEquals(harness.buttonLeft(1), harness.indicator.translationX, 0.5f)
        assertEquals(harness.buttonLeft(1), subject.targetTranslationX!!, 0.5f)
    }

    @Test
    fun `relayout keeps the indicator under the checked option`() {
        val harness = Harness(context)
        val subject = RegionDetectionModeIndicator(harness.group, harness.indicator)
        subject.setChecked(harness.ids[2], animate = false)
        harness.layout(width = 300)
        assertEquals(harness.buttonLeft(2), harness.indicator.translationX, 0.5f)

        // A width change must re-derive both offset and width.
        harness.layout(width = 600)

        assertEquals(harness.buttonLeft(2), harness.indicator.translationX, 0.5f)
        assertEquals(harness.group.getChildAt(2).width, harness.indicator.layoutParams.width)
    }
}
