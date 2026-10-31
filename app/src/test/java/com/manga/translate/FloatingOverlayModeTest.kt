package com.manga.translate

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.floating.DetectionOverlayWindowPolicy
import com.manga.translate.floating.FloatingScreenTapDetector
import com.manga.translate.floating.FloatingTapActions
import com.manga.translate.model.FloatingBallGestureAction
import com.manga.translate.model.FloatingOverlayMode
import com.manga.translate.settings.SettingsStore
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class FloatingOverlayModeTest {
    private val sdkS = Build.VERSION_CODES.S

    @Test fun passthroughModeNeverCapturesTouchesAndStaysWithinUntrustedAlpha() {
        for (hasBubbles in listOf(false, true)) {
            val policy = DetectionOverlayWindowPolicy.resolve(
                FloatingOverlayMode.TRANSLUCENT_PASSTHROUGH, isEditing = false, hasBubbles = hasBubbles, sdkInt = sdkS
            )
            assertFalse(policy.touchable)
            assertEquals(DetectionOverlayWindowPolicy.PASSTHROUGH_ALPHA, policy.alpha, 0f)
        }
    }

    @Test fun opaqueModeCapturesTouchesOnlyWhileBubblesAreShown() {
        val shown = DetectionOverlayWindowPolicy.resolve(
            FloatingOverlayMode.OPAQUE_BLOCKING, isEditing = false, hasBubbles = true, sdkInt = sdkS
        )
        assertTrue(shown.touchable)
        assertEquals(1f, shown.alpha, 0f)

        val idle = DetectionOverlayWindowPolicy.resolve(
            FloatingOverlayMode.OPAQUE_BLOCKING, isEditing = false, hasBubbles = false, sdkInt = sdkS
        )
        assertFalse(idle.touchable)
        assertEquals(DetectionOverlayWindowPolicy.PASSTHROUGH_ALPHA, idle.alpha, 0f)
    }

    @Test fun editingAlwaysCapturesTouchesOpaque() {
        for (mode in FloatingOverlayMode.entries) {
            val policy = DetectionOverlayWindowPolicy.resolve(mode, isEditing = true, hasBubbles = false, sdkInt = sdkS)
            assertTrue(policy.touchable)
            assertEquals(1f, policy.alpha, 0f)
        }
    }

    @Test fun belowAndroid12KeepsFullAlphaWhenNotCapturingTouches() {
        for (mode in FloatingOverlayMode.entries) {
            val policy = DetectionOverlayWindowPolicy.resolve(
                mode,
                isEditing = false,
                hasBubbles = true,
                sdkInt = Build.VERSION_CODES.R
            )
            assertFalse(policy.touchable)
            assertEquals(1f, policy.alpha, 0f)
        }
    }

    @Test fun overlayModeDefaultsToPassthroughAndRoundTrips() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context)
        assertEquals(FloatingOverlayMode.TRANSLUCENT_PASSTHROUGH, store.loadFloatingTranslateApiSettings().overlayMode)
        store.saveFloatingTranslateApiSettings(
            store.loadFloatingTranslateApiSettings().copy(overlayMode = FloatingOverlayMode.OPAQUE_BLOCKING)
        )
        assertEquals(
            FloatingOverlayMode.OPAQUE_BLOCKING,
            SettingsStore(context).loadFloatingTranslateApiSettings().overlayMode
        )
        assertEquals(
            FloatingOverlayMode.TRANSLUCENT_PASSTHROUGH,
            FloatingOverlayMode.fromPref("unknown", FloatingOverlayMode.TRANSLUCENT_PASSTHROUGH)
        )
    }

    @Test fun opaqueModeRequiresClearScreenGesture() {
        val none = listOf(FloatingBallGestureAction.START_TRANSLATE, FloatingBallGestureAction.NONE)
        assertFalse(FloatingOverlayMode.isAllowed(FloatingOverlayMode.OPAQUE_BLOCKING, none))
        assertTrue(FloatingOverlayMode.isAllowed(FloatingOverlayMode.TRANSLUCENT_PASSTHROUGH, none))
        assertTrue(
            FloatingOverlayMode.isAllowed(
                FloatingOverlayMode.OPAQUE_BLOCKING,
                none + FloatingBallGestureAction.CLEAR_SCREEN
            )
        )

        val store = SettingsStore(ApplicationProvider.getApplicationContext<Context>())
        val imported = store.loadFloatingTranslateApiSettings().copy(
            singleTapAction = FloatingBallGestureAction.START_TRANSLATE,
            doubleTapAction = FloatingBallGestureAction.NONE,
            longPressAction = FloatingBallGestureAction.OPEN_MENU,
            tripleTapAction = FloatingBallGestureAction.NONE,
            overlayMode = FloatingOverlayMode.OPAQUE_BLOCKING
        )
        assertEquals(FloatingOverlayMode.TRANSLUCENT_PASSTHROUGH, imported.effectiveOverlayMode())
        assertEquals(
            FloatingOverlayMode.OPAQUE_BLOCKING,
            imported.copy(doubleTapAction = FloatingBallGestureAction.CLEAR_SCREEN).effectiveOverlayMode()
        )
    }

    private class DetectorHarness {
        val fired = mutableListOf<FloatingBallGestureAction>()
        val detector = FloatingScreenTapDetector(
            handler = Handler(Looper.getMainLooper()),
            touchSlopPx = 10f,
            doubleTapTimeoutMs = 300L,
            longPressTimeoutMs = 500L,
            actions = {
                FloatingTapActions(
                    single = FloatingBallGestureAction.START_TRANSLATE,
                    double = FloatingBallGestureAction.CLEAR_SCREEN,
                    triple = FloatingBallGestureAction.OPEN_MENU,
                    longPress = FloatingBallGestureAction.SWIPE_TRANSLATE
                )
            },
            onAction = { fired += it }
        )

        fun send(action: Int, x: Float = 100f, y: Float = 100f) {
            val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
            detector.onTouchEvent(event)
            event.recycle()
        }

        fun tap() {
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_UP)
        }

        fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
    }

    @Test fun singleTapFiresAfterDoubleTapWindow() {
        val h = DetectorHarness()
        h.tap()
        h.idle(100)
        assertTrue(h.fired.isEmpty())
        h.idle(400)
        assertEquals(listOf(FloatingBallGestureAction.START_TRANSLATE), h.fired)
    }

    @Test fun doubleTapFiresDoubleActionOnly() {
        val h = DetectorHarness()
        h.tap()
        h.idle(100)
        h.tap()
        h.idle(600)
        assertEquals(listOf(FloatingBallGestureAction.CLEAR_SCREEN), h.fired)
    }

    @Test fun tripleTapFiresImmediately() {
        val h = DetectorHarness()
        h.tap()
        h.idle(50)
        h.tap()
        h.idle(50)
        h.tap()
        assertEquals(listOf(FloatingBallGestureAction.OPEN_MENU), h.fired)
        h.idle(600)
        assertEquals(1, h.fired.size)
    }

    @Test fun longPressFiresOnceAndSuppressesTap() {
        val h = DetectorHarness()
        h.send(MotionEvent.ACTION_DOWN)
        h.idle(700)
        assertEquals(listOf(FloatingBallGestureAction.SWIPE_TRANSLATE), h.fired)
        h.send(MotionEvent.ACTION_UP)
        h.idle(600)
        assertEquals(1, h.fired.size)
    }

    @Test fun dragBeyondSlopFiresNothing() {
        val h = DetectorHarness()
        h.send(MotionEvent.ACTION_DOWN)
        h.send(MotionEvent.ACTION_MOVE, x = 160f)
        h.idle(700)
        h.send(MotionEvent.ACTION_UP, x = 160f)
        h.idle(600)
        assertTrue(h.fired.isEmpty())
    }

    @Test fun cancelDropsPendingTap() {
        val h = DetectorHarness()
        h.tap()
        h.detector.cancel()
        h.idle(600)
        assertTrue(h.fired.isEmpty())
    }
}
