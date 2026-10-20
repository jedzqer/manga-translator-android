package com.manga.translate

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.manga.translate.floating.FloatingBallOverlayService
import com.manga.translate.floating.FloatingWindowController
import com.manga.translate.floating.createFloatingControllerContext
import com.manga.translate.floating.floatingControllerButtonTextColor
import com.manga.translate.model.ThemeMode
import com.manga.translate.settings.SettingsStore
import com.manga.translate.theming.ThemePaletteRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class FloatingControllerUiTest {
    @Test @Config(qualifiers = "en-w320dp-h480dp-notnight")
    fun narrowLightMenuKeepsBallStill() = checkMenu()

    @Test @Config(qualifiers = "ru-w320dp-h480dp-night")
    fun narrowDarkMenuWrapsAndScrolls() = checkMenu(leftBottom = true)

    @Test @Config(qualifiers = "pt-rBR-w480dp-h320dp-notnight")
    fun landscapeMenuStaysWithinScreen() = checkMenu()

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun opacityChangesReachExistingOverlay() {
        val controller = Robolectric.buildService(FloatingBallOverlayService::class.java).create()
        val service = controller.get()
        val store = SettingsStore(service)
        val original = store.loadFloatingBubbleRenderSettings()
        try {
            store.saveFloatingBubbleRenderSettings(original.copy(opacityPercent = 40))
            invoke(service, "showDetectionOverlay")
            shadowOf(Looper.getMainLooper()).idle()
            val overlay = field<com.manga.translate.floating.FloatingDetectionOverlayView>(service, "detectionOverlayView")
            val paint = com.manga.translate.floating.FloatingDetectionOverlayView::class.java
                .getDeclaredField("boxPaint").apply { isAccessible = true }.get(overlay) as android.graphics.Paint
            assertEquals(102, paint.alpha)
            store.saveFloatingBubbleRenderSettings(original.copy(opacityPercent = 100))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(255, paint.alpha)
        } finally {
            store.saveFloatingBubbleRenderSettings(original)
            controller.destroy()
        }
    }

    private fun checkMenu(leftBottom: Boolean = false) {
        val controller = Robolectric.buildService(FloatingBallOverlayService::class.java).create()
        val service = controller.get()
        try {
            invoke(service, "showControllerOverlay")
            shadowOf(Looper.getMainLooper()).idle()
            val ball = field<View>(service, "controllerBallView")
            val root = field<ViewGroup>(service, "controllerRoot")
            val menu = field<ViewGroup>(service, "controllerMenuPanel")
            val params = field<WindowManager.LayoutParams>(service, "controllerLayoutParams")
            if (leftBottom) {
                val frame = Rect().also(root::getWindowVisibleDisplayFrame)
                params.x = 0
                params.y = (frame.height() - ball.height).coerceAtLeast(0)
                field<FloatingWindowController>(service, "windowManager").updateViewLayout(root, params)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val originalX = params.x
            val originalY = params.y
            val originalLocation = IntArray(2).also(ball::getLocationOnScreen)
            assertEquals(1, root.childCount)
            assertTrue(ball is ImageView)
            assertNotNull((ball as ImageView).drawable)
            assertFalse(menu.isShown)
            val store = SettingsStore(service)
            val previous = store.loadFloatingOnlyRecognizeBubbles()
            val toggle = (0 until menu.childCount).map { menu.getChildAt(it) }
                .filterIsInstance<TextView>().single {
                    it.text == service.getString(if (previous) R.string.floating_only_bubbles_on else R.string.floating_only_bubbles_off)
                }
            toggle.performClick()
            assertEquals(!previous, SettingsStore(service).loadFloatingOnlyRecognizeBubbles())
            assertEquals(service.getString(if (previous) R.string.floating_only_bubbles_off else R.string.floating_only_bubbles_on), toggle.text)
            toggle.performClick()
            assertEquals(previous, store.loadFloatingOnlyRecognizeBubbles())

            // Exercise the real long-press handler, including release and reopening.
            repeat(2) {
                val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 24f, 24f, 0)
                ball.dispatchTouchEvent(down)
                down.recycle()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
                    ViewConfiguration.getLongPressTimeout().toLong() + 1))
                assertTrue(menu.isShown)
                val after = IntArray(2).also(ball::getLocationOnScreen)
                assertArrayEquals(originalLocation, after)
                assertEquals(originalX, params.x)
                assertEquals(originalY, params.y)
                val up = MotionEvent.obtain(0, 600, MotionEvent.ACTION_UP, 24f, 24f, 0)
                ball.dispatchTouchEvent(up)
                up.recycle()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(menu.isShown)

                val panel = (menu.parent as View).parent as View
                val panelLocation = IntArray(2).also(panel::getLocationOnScreen)
                val frame = Rect().also(root::getWindowVisibleDisplayFrame)
                val panelRect = Rect(panelLocation[0], panelLocation[1],
                    panelLocation[0] + panel.width, panelLocation[1] + panel.height)
                assertTrue("panel=$panelRect frame=$frame", frame.contains(panelRect))
                val ballRect = Rect(after[0], after[1], after[0] + ball.width, after[1] + ball.height)
                assertFalse(Rect.intersects(ballRect, panelRect))
                val density = service.resources.displayMetrics.density
                assertTrue(panel.width <= (132 * density).toInt())

                // Long localized labels remain multiline; larger fonts can use the scroll area.
                for (index in 0 until menu.childCount) {
                    val button = menu.getChildAt(index) as TextView
                    if (button.visibility != View.VISIBLE) continue
                    assertTrue(button.width <= panel.width)
                    assertTrue(button.textSize / service.resources.displayMetrics.scaledDensity <= 12.1f)
                    assertNull(button.ellipsize)
                    val layout = button.layout
                    assertNotNull(layout)
                    assertEquals(button.text.length, layout.getLineEnd(layout.lineCount - 1))
                }
                val scroll = menu.parent as ScrollView
                scroll.measure(View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((80 * density).toInt(), View.MeasureSpec.AT_MOST))
                scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
                assertTrue(menu.height > scroll.height)
                scroll.scrollTo(0, menu.height)
                assertTrue(scroll.scrollY > 0)

                FloatingBallOverlayService::class.java.getDeclaredMethod(
                    "setMenuVisibility", View::class.java, Boolean::class.javaPrimitiveType
                ).apply { isAccessible = true }.invoke(service, menu, false)
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(panel.isAttachedToWindow)
                assertArrayEquals(originalLocation, IntArray(2).also(ball::getLocationOnScreen))
            }
            FloatingBallOverlayService::class.java.getDeclaredMethod(
                "showProgressStatus", String::class.java, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(service, "A long progress message that wraps across lines", true)
            shadowOf(Looper.getMainLooper()).idle()
            assertArrayEquals(originalLocation, IntArray(2).also(ball::getLocationOnScreen))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(field<View>(service, "progressStatusView").isShown)
        } finally {
            controller.destroy()
        }
    }

    @Test fun overlayHonorsExplicitThemeAndCustomTextColor() {
        val controller = Robolectric.buildService(FloatingBallOverlayService::class.java).create()
        try {
            val service = controller.get()
            val settings = SettingsStore(service)
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.PASTEL, ThemeMode.DEEP_SEA, ThemeMode.CUSTOM)) {
                settings.saveThemeMode(mode)
                val context = createFloatingControllerContext(service, settings)
                assertEquals(if (mode == ThemeMode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                val color = floatingControllerButtonTextColor(context, settings)
                assertNotEquals(0, color)
                if (mode == ThemeMode.CUSTOM) assertEquals(settings.loadCustomThemeColors().buttonText, color)
            }
        } finally {
            ThemePaletteRuntime.clear()
            controller.destroy()
        }
    }

    private fun invoke(service: FloatingBallOverlayService, name: String) =
        FloatingBallOverlayService::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(service)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(service: FloatingBallOverlayService, name: String): T =
        FloatingBallOverlayService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(service) as T
}
