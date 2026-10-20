package com.manga.translate

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.floating.FloatingCaptureTranslationSession
import com.manga.translate.floating.FloatingWindowController
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingLifecycleTest {
    @Test fun replacingAndClosingSessionRejectsLateResultsAndCancelsWork() {
        val session = FloatingCaptureTranslationSession()
        val first = session.advance()
        val job = Job()
        session.job = job
        val second = session.advance()
        assertTrue(job.isCancelled)
        assertFalse(session.isCurrent(first))
        assertTrue(session.isCurrent(second))
        session.close()
        session.close()
        assertFalse(session.isCurrent(second))
        assertFalse(session.isCurrent(session.advance()))
    }

    @Test fun captureCleanupCannotRestoreClosedWindows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(WindowManager::class.java)
        val windows = FloatingWindowController(manager)
        val view = View(context)
        val params = WindowManager.LayoutParams()
        windows.addView(view, params)
        val restore = windows.hideForCapture()
        assertEquals(View.INVISIBLE, view.visibility)
        windows.close()
        windows.close()
        restore()
        assertEquals(View.INVISIBLE, view.visibility)
        windows.addView(view, params)
        assertFalse(view.isAttachedToWindow)
    }
}
