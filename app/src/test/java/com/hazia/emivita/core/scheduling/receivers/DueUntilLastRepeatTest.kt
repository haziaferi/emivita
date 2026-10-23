package com.hazia.emivita.core.scheduling.receivers

import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.core.notification.ACTION_SNOOZE
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * What writes a dose's Due-until instant (§7 R119): the fire, each repeat and
 * a snooze, from the reminder's `repeatOnUnanswered` as it stood when they
 * happened. The receivers run on the real clock, so each instant is checked
 * against the window the call ran in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DueUntilLastRepeatTest {
    private val h = ReceiverHarness()
    private val minute = 60_000L

    @After
    fun tearDown() = h.close()

    private fun assertWithin(what: String, offset: Long, before: Long, after: Long, actual: Long?) {
        assertTrue("$what: $actual not in ${before + offset}..${after + offset}", actual != null && actual in (before + offset)..(after + offset))
    }

    private fun <T> timed(block: () -> T): Triple<Long, T, Long> {
        val before = System.currentTimeMillis()
        val value = block()
        return Triple(before, value, System.currentTimeMillis())
    }

    @Test
    fun `the fire sets due until its last repeat, and repeats delivered early do not shorten it`() {
        // The receivers read the real clock, so the repeats here arrive at once
        // rather than five minutes apart: each owes fewer repeats from an earlier
        // instant, and the later deadline the fire set stands (on the phone,
        // five minutes on, each lands where the fire's estimate put it).
        val reminderId = h.reminder(h.medicine(), ReminderConfig.FixedTime(9, 0, 1.0), repeat = RepeatConfig(delayMinutes = 5, maxRepeats = 2))
        val (b0, _, a0) = timed { h.fire(h.regularAlarms(reminderId).single()) }
        val doseId = h.doseEvents(reminderId).single().id
        assertWithin("after the fire", 10 * minute, b0, a0, h.doseEvent(doseId).missedAfterEpochMillis)

        h.fire(h.nagAlarms(reminderId).single())
        assertWithin("after the first repeat", 10 * minute, b0, a0, h.doseEvent(doseId).missedAfterEpochMillis)
        h.fire(h.nagAlarms(reminderId).single())
        assertWithin("after the last repeat", 10 * minute, b0, a0, h.doseEvent(doseId).missedAfterEpochMillis)
    }

    @Test
    fun `a dose moved to later is due until the moved slot's repeats are over`() {
        val reminderId = h.reminder(h.medicine(), ReminderConfig.FixedTime(9, 0, 1.0), repeat = RepeatConfig(delayMinutes = 5, maxRepeats = 2))
        h.fire(h.regularAlarms(reminderId).single())
        val doseId = h.doseEvents(reminderId).single().id
        val movedTo = System.currentTimeMillis() + 60 * minute
        runBlocking { h.container.doseEventRepository.reschedule(doseId, movedTo) }
        assertWithin("moved", 10 * minute, movedTo, movedTo, h.doseEvent(doseId).missedAfterEpochMillis)
    }

    @Test
    fun `a repeat that fires while a snooze runs does not end the snooze's due time`() {
        val reminderId = h.reminder(h.medicine(), ReminderConfig.FixedTime(9, 0, 1.0), repeat = RepeatConfig(delayMinutes = 5, maxRepeats = 1))
        h.fire(h.regularAlarms(reminderId).single())
        val doseId = h.doseEvents(reminderId).single().id
        val snooze = runBlocking { h.container.settingsRepository.snoozeDurationMinutes.first() } * minute
        val (b, _, a) = timed { h.action(ACTION_SNOOZE, doseId) }
        // Snoozed with its one repeat still owed: due until the snooze fire and that repeat.
        assertWithin("snoozed", snooze + 5 * minute, b, a, h.doseEvent(doseId).missedAfterEpochMillis)
        // The snooze leaves the armed repeat in place; it fires now and is the last one.
        h.fire(h.nagAlarms(reminderId).single())
        assertWithin("snoozed, then repeated", snooze + 5 * minute, b, a, h.doseEvent(doseId).missedAfterEpochMillis)
    }

    @Test
    fun `with no repeat the fire makes it missed at once`() {
        val reminderId = h.reminder(h.medicine(), ReminderConfig.FixedTime(9, 0, 1.0))
        val (b, _, a) = timed { h.fire(h.regularAlarms(reminderId).single()) }
        assertWithin("no repeat", 0L, b, a, h.doseEvents(reminderId).single().missedAfterEpochMillis)
    }

    @Test
    fun `a snooze keeps it due until the snooze fires and that fire's repeats are over`() {
        val reminderId = h.reminder(h.medicine(), ReminderConfig.FixedTime(9, 0, 1.0), repeat = RepeatConfig(delayMinutes = 5, maxRepeats = 2))
        h.fire(h.regularAlarms(reminderId).single())
        val doseId = h.doseEvents(reminderId).single().id
        val snooze = runBlocking { h.container.settingsRepository.snoozeDurationMinutes.first() } * minute
        val (b, _, a) = timed { h.action(ACTION_SNOOZE, doseId) }
        assertWithin("snoozed", snooze + 10 * minute, b, a, h.doseEvent(doseId).missedAfterEpochMillis)
    }
}
