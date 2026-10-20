package com.manga.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.manga.translate.app.ReleaseChannel
import com.manga.translate.app.UpdateChecker
import com.manga.translate.app.UpdateInfo
import com.manga.translate.settings.AutoUpdateCheckState
import com.manga.translate.settings.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoUpdateCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val day = 86_400_000L
    private val info = UpdateInfo(1, "1", "https://example.com/app.apk", "", emptyList(), ReleaseChannel.STABLE, null)

    @Before fun clear() {
        context.getSharedPreferences("manga_translate_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun intervalsGrowToThirtyDaysAndResetOnUpdate() {
        var state = AutoUpdateCheckState()
        assertTrue(state.isDue(1))
        for (days in listOf(1, 1, 2, 3, 7, 14, 30, 30)) {
            state = state.afterSuccess(100, false)
            assertEquals(days * day, state.intervalMillis)
            assertFalse(state.isDue(100 + days * day - 1))
            assertTrue(state.isDue(100 + days * day))
        }
        state = state.afterSuccess(200, true)
        assertEquals(day, state.intervalMillis)
        assertEquals(0, state.noUpdateCount)
        assertTrue(state.isDue(199)) // Clock rollback must not block checks indefinitely.
    }

    @Test fun successPersistsAcrossStoreInstancesAndFailureDoesNotAdvance() = runBlocking {
        var calls = 0
        suspend fun check(now: Long, success: Boolean) = UpdateChecker.fetchAutomaticUpdateInfo(
            SettingsStore(context), false, "en", { false }, { now },
            { calls++; if (success) info else null }
        )
        assertNull(check(100, false))
        assertEquals(AutoUpdateCheckState(), SettingsStore(context).loadAutoUpdateCheckState())
        assertEquals(info, check(101, true))
        assertNull(check(102, true))
        assertEquals(2, calls)
        assertNull(check(101 + day, false))
        assertEquals(AutoUpdateCheckState(101, 1), SettingsStore(context).loadAutoUpdateCheckState())
        assertEquals(info, check(102 + day, true))
    }

    @Test fun simultaneousAutomaticChecksOnlyFetchOnce() = runBlocking {
        var calls = 0
        val checks = List(2) {
            async {
                UpdateChecker.fetchAutomaticUpdateInfo(
                    SettingsStore(context), false, "en", { true }, { 100L },
                    { calls++; delay(20); info }
                )
            }
        }
        assertEquals(1, checks.map { it.await() }.count { it != null })
        assertEquals(1, calls)
    }
}
