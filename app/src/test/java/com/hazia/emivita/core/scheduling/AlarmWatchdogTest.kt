package com.hazia.emivita.core.scheduling

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The missed-alarm rule and its store (spec §9 #29; Appendix F DP-04 / DP-F2). */
@RunWith(RobolectricTestRunner::class)
class AlarmWatchdogTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val min = 60_000L
    private val up = AlarmWatchdog.MIN_UPTIME_MILLIS + 1

    @Test
    fun `verdict -- a planned instant more than a minute past with no fire since is a miss`() {
        assertEquals(100 * min, AlarmWatchdog.verdict(earliestPlanned = 100 * min, lastFired = null, nowEpochMillis = 102 * min, uptimeMillis = up))
        assertEquals(100 * min, AlarmWatchdog.verdict(100 * min, lastFired = 90 * min, nowEpochMillis = 102 * min, uptimeMillis = up))
    }

    @Test
    fun `verdict -- the two guards and a fire on or after the plan clear it`() {
        assertNull("nothing planned", AlarmWatchdog.verdict(null, null, 102 * min, up))
        assertNull("inside AlarmManager's slack", AlarmWatchdog.verdict(100 * min, null, 100 * min + 30_000, up))
        assertNull("the receiver has not had its turn since boot", AlarmWatchdog.verdict(100 * min, null, 102 * min, uptimeMillis = 2 * min))
        assertNull("it fired, late", AlarmWatchdog.verdict(100 * min, lastFired = 101 * min, nowEpochMillis = 102 * min, uptimeMillis = up))
    }

    @Test
    fun `the store keeps one planned instant per reminder, the earliest wins, cancel removes, a fire clears the notice`() {
        AlarmWatchdog.recordPlanned(context, 1, 500 * min)
        AlarmWatchdog.recordPlanned(context, 2, 300 * min)
        AlarmWatchdog.recordPlanned(context, 1, 400 * min)
        assertEquals(300 * min, AlarmWatchdog.earliestPlanned(context))
        AlarmWatchdog.recordCancelled(context, 2)
        assertEquals(400 * min, AlarmWatchdog.earliestPlanned(context))

        assertEquals("missed", 400 * min, AlarmWatchdog.checkOnLaunch(context, nowEpochMillis = 402 * min, uptimeMillis = up))
        assertEquals(400 * min, AlarmWatchdog.notice.value)
        AlarmWatchdog.recordFired(context, 401 * min)
        assertNull("fired since: no new miss", AlarmWatchdog.checkOnLaunch(context, nowEpochMillis = 403 * min, uptimeMillis = up))
        assertEquals("but the earlier notice stays until dismissed", 400 * min, AlarmWatchdog.notice.value)
        AlarmWatchdog.dismissNotice(context)
        assertNull(AlarmWatchdog.notice.value)
        assertNull(AlarmWatchdog.checkOnLaunch(context, nowEpochMillis = 403 * min, uptimeMillis = up))
    }
}
