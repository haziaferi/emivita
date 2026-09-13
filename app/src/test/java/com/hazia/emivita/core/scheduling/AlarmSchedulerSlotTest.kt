package com.hazia.emivita.core.scheduling

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The alarm-slot scheme (spec §7 R60, Appendix E B-03 / B F-2). Robolectric's
 * ShadowAlarmManager records every armed PendingIntent, so this can see what
 * the request-code arithmetic actually does to AlarmManager, which no
 * mock-based test could: two PendingIntents with the same request code and
 * a `filterEquals` intent are one alarm to the platform.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerSlotTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scheduler = AlarmScheduler(context)

    @Test
    fun `two snoozes on the same reminder are two alarms, not one overwriting the other`() {
        // An ON_REMINDED interval reminder can have two unanswered doses at
        // once. Keyed per reminder, the second snooze replaced the first's
        // (same request code, FLAG_UPDATE_CURRENT) and the first dose was
        // never re-shown. Keyed per dose event, both survive.
        scheduler.scheduleSnooze(reminderId = 7L, doseEventId = 101L, originalScheduledEpochMillis = 1_000L, fireAtEpochMillis = 5_000L)
        scheduler.scheduleSnooze(reminderId = 7L, doseEventId = 102L, originalScheduledEpochMillis = 2_000L, fireAtEpochMillis = 6_000L)

        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `two nags on the same reminder are two alarms`() {
        scheduler.scheduleNag(reminderId = 7L, doseEventId = 101L, originalScheduledEpochMillis = 1_000L, fireAtEpochMillis = 5_000L)
        scheduler.scheduleNag(reminderId = 7L, doseEventId = 102L, originalScheduledEpochMillis = 2_000L, fireAtEpochMillis = 6_000L)

        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `cancelling one dose's nag leaves the other dose's nag armed`() {
        scheduler.scheduleNag(reminderId = 7L, doseEventId = 101L, originalScheduledEpochMillis = 1_000L, fireAtEpochMillis = 5_000L)
        scheduler.scheduleNag(reminderId = 7L, doseEventId = 102L, originalScheduledEpochMillis = 2_000L, fireAtEpochMillis = 6_000L)

        scheduler.cancelNag(reminderId = 7L, doseEventId = 101L)

        val remaining = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, remaining.size)
        assertEquals(6_000L, remaining.single().triggerAtMs)
    }

    @Test
    fun `the regular slot, the snooze slot and the nag slot of one dose never collide`() {
        scheduler.schedule(reminderId = 7L, triggerAtEpochMillis = 4_000L)
        scheduler.scheduleSnooze(reminderId = 7L, doseEventId = 7L, originalScheduledEpochMillis = 1_000L, fireAtEpochMillis = 5_000L)
        scheduler.scheduleNag(reminderId = 7L, doseEventId = 7L, originalScheduledEpochMillis = 1_000L, fireAtEpochMillis = 6_000L)

        // reminderId == doseEventId == 7 is the adversarial case: the three
        // request codes are 7, -7 and 1_500_000_007.
        assertEquals(3, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `re-arming the regular slot replaces rather than stacks`() {
        scheduler.schedule(reminderId = 7L, triggerAtEpochMillis = 4_000L)
        scheduler.schedule(reminderId = 7L, triggerAtEpochMillis = 9_000L)

        val alarms = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(9_000L, alarms.single().triggerAtMs)
    }

    @Test
    fun `with the alarm-clock setting on, the regular slot is an AlarmClock and re-arming still replaces`() {
        scheduler.useAlarmClock = true
        scheduler.schedule(reminderId = 7L, triggerAtEpochMillis = 4_000L)
        scheduler.schedule(reminderId = 7L, triggerAtEpochMillis = 9_000L)
        val alarms = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(9_000L, alarms.single().triggerAtMs)
        // snooze and nag stay ordinary exact alarms beside it
        scheduler.scheduleSnooze(reminderId = 7L, doseEventId = 1L, originalScheduledEpochMillis = 1L, fireAtEpochMillis = 5_000L)
        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }
}
