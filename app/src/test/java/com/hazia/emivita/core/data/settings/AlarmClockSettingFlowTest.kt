package com.hazia.emivita.core.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `useAlarmClock` emits when the toggle changes and at no other time (spec
 * §7 R70). DataStore re-emits its whole preferences object on every write,
 * and `AppContainer.observeAlarmClockSetting` answers every emission after
 * the first with `rescheduleAll()`: without `distinctUntilChanged`, the
 * receiver's own last-seen stamp on each alarm fire re-armed every reminder
 * on a background thread -- found when the receiver tests raced it.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmClockSettingFlowTest {

    @Test
    fun `unrelated settings writes do not re-emit the alarm-clock toggle`() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        settings.setUseAlarmClock(false)
        val seen = mutableListOf<Boolean>()
        val collector = launch(Dispatchers.IO) { settings.useAlarmClock.take(2).toList(seen) }
        withTimeout(5_000) { while (seen.isEmpty()) kotlinx.coroutines.delay(10) }

        settings.setSwipeAction(SwipeAction.SKIP)
        settings.setAppLastSeenEpochMillis(1L)
        settings.setSnoozeDurationMinutes(7)
        assertEquals(SwipeAction.SKIP, settings.swipeAction.first())
        assertEquals("three unrelated writes, no emission", listOf(false), seen.toList())

        settings.setUseAlarmClock(true)
        withTimeout(5_000) { collector.join() }
        assertEquals(listOf(false, true), seen)

        settings.setSwipeAction(SwipeAction.SNOOZE)
        settings.setSnoozeDurationMinutes(10)
        settings.setUseAlarmClock(false)
    }
}
