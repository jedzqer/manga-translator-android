package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import com.manga.translate.app.MainActivity
import com.manga.translate.model.FolderReadingMode
import com.manga.translate.reader.ReadingSessionViewModel
import com.manga.translate.reader.WebtoonRecyclerView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebtoonScrollDeviceTest {
    @Test
    fun stoppingFlingDoesNotTurnPageButIdleTapAndDoubleTapStillWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "scroll_qa_${System.nanoTime()}").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(480, 16000, Bitmap.Config.ARGB_8888)
        val pages = try {
            (0..2).map { index ->
                bitmap.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                File(folder, "$index.png").also { file ->
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally { bitmap.recycle() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[ReadingSessionViewModel::class.java]
                        .setFolder(folder, pages, 0, FolderReadingMode.WEBTOON_SCROLL)
                    activity.findViewById<ViewPager2>(R.id.main_pager).setCurrentItem(0, false)
                }
                // Wait for bounds/segment layout, independently of image decode completion.
                val deadline = SystemClock.uptimeMillis() + 10000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<WebtoonRecyclerView>(R.id.reading_webtoon_list)
                        ready = list != null && list.width > 0 && (list.adapter?.itemCount ?: 0) > 3 && !list.isLayoutRequested
                    }
                    if (!ready) SystemClock.sleep(30)
                }
                assertTrue("reader must have measured segments", ready)
                for (fraction in listOf(0.8f, 0.2f, 0.5f)) {
                    var stoppedOffset: Int? = null
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<WebtoonRecyclerView>(R.id.reading_webtoon_list)
                        val session = ViewModelProvider(activity)[ReadingSessionViewModel::class.java]
                        assertTrue(list.fling(0, 6000))
                        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, list.scrollState)
                        tap(list, fraction)
                        assertTrue(list.isStoppingScrollGesture)
                        stoppedOffset = session.state.value.viewport?.topOffset
                    }
                    SystemClock.sleep(450) // Includes GestureDetector's delayed single-tap callback.
                    scenario.onActivity { activity ->
                        val state = ViewModelProvider(activity)[ReadingSessionViewModel::class.java].state.value
                        assertEquals("braking must not navigate", 0, state.index)
                        assertEquals("braking must preserve offset", stoppedOffset, state.viewport?.topOffset)
                    }
                }
                scenario.onActivity { activity ->
                    tap(activity.findViewById(R.id.reading_webtoon_list), 0.8f)
                }
                SystemClock.sleep(450)
                scenario.onActivity { activity ->
                    assertEquals("idle right tap still navigates", 1,
                        ViewModelProvider(activity)[ReadingSessionViewModel::class.java].state.value.index)
                }
                scenario.onActivity { activity -> tap(activity.findViewById(R.id.reading_webtoon_list), 0.5f) }
                SystemClock.sleep(70)
                scenario.onActivity { activity -> tap(activity.findViewById(R.id.reading_webtoon_list), 0.5f) }
                SystemClock.sleep(450)
                scenario.onActivity { activity ->
                    val list = activity.findViewById<WebtoonRecyclerView>(R.id.reading_webtoon_list)
                    assertEquals("double tap still zooms", 2f, list.contentScale, 0.01f)
                    assertEquals(1, ViewModelProvider(activity)[ReadingSessionViewModel::class.java].state.value.index)
                }
            }
        } finally { folder.deleteRecursively() }
    }

    private fun tap(list: WebtoonRecyclerView, fraction: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, down, action, list.width * fraction, list.height * 0.5f, 0)
            try { list.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
