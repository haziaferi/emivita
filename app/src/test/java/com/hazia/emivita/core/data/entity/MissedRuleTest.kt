package com.hazia.emivita.core.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's rule of 2026-10-23 (§7 R119): a pending dose is Due while its
 * notification is up or will repeat, and Missed after the last repeat (fire
 * time + delay x maxRepeats); with no repeat, Missed at once. Fixed clock.
 */
class MissedRuleTest {
    private val firedAt = 1_800_000_000_000L
    private val minute = 60_000L

    private fun pending(missedAfter: Long?, scheduled: Long = firedAt) = DoseEvent(
        reminderId = 1, medicineId = 1, scheduledEpochMillis = scheduled, doseAmount = 1.0,
        createdAtEpochMillis = 0, missedAfterEpochMillis = missedAfter,
    )

    @Test
    fun `with no repeat it is missed at once`() {
        val dose = pending(missedAfterShowing(firedAt, repeat = null, repeatCount = 0))
        assertTrue(dose.isMissedAt(firedAt))
        assertFalse(dose.isDueAt(firedAt))
        assertTrue("before its fire it is still due", dose.isDueAt(firedAt - 1))
    }

    @Test
    fun `with repeats it is due until the last one and missed from it`() {
        val dose = pending(missedAfterShowing(firedAt, RepeatConfig(delayMinutes = 10, maxRepeats = 3), repeatCount = 0))
        assertEquals(firedAt + 30 * minute, dose.missedAfterEpochMillis)
        assertTrue(dose.isDueAt(firedAt + 29 * minute))
        assertFalse(dose.isMissedAt(firedAt + 29 * minute))
        assertTrue(dose.isMissedAt(firedAt + 30 * minute))
    }

    @Test
    fun `each repeat fired leaves the ones still owed, and the last one ends it`() {
        val repeat = RepeatConfig(delayMinutes = 10, maxRepeats = 3)
        assertEquals(firedAt + 10 * minute + 20 * minute, missedAfterShowing(firedAt + 10 * minute, repeat, repeatCount = 1))
        assertEquals(firedAt + 30 * minute, missedAfterShowing(firedAt + 30 * minute, repeat, repeatCount = 3))
    }

    @Test
    fun `a snoozed dose is due until the snooze fires and that fire's repeats are over`() {
        // Snoozed after one repeat, for 15 minutes: the snooze's fire re-shows it
        // and arms the two repeats left (ReminderAlarmReceiver.scheduleFirstNagIfConfigured).
        val snoozeFire = firedAt + 25 * minute
        val dose = pending(missedAfterShowing(snoozeFire, RepeatConfig(delayMinutes = 10, maxRepeats = 3), repeatCount = 1))
        assertTrue(dose.isDueAt(snoozeFire + 19 * minute))
        assertTrue(dose.isMissedAt(snoozeFire + 20 * minute))
        val noRepeat = pending(missedAfterShowing(snoozeFire, repeat = null, repeatCount = 0))
        assertTrue("snoozed with no repeat: due until the snooze fires", noRepeat.isDueAt(snoozeFire - 1))
        assertTrue(noRepeat.isMissedAt(snoozeFire))
    }

    @Test
    fun `a dose whose reminder was deleted has no deadline and is missed once its time has passed`() {
        val dose = pending(missedAfter = null).copy(reminderId = null)
        assertTrue(dose.isDueAt(firedAt - 1))
        assertTrue(dose.isMissedAt(firedAt))
    }

    @Test
    fun `an answered dose is neither due nor missed`() {
        val taken = pending(firedAt + 30 * minute).copy(status = DoseStatus.TAKEN)
        assertFalse(taken.isDueAt(firedAt))
        assertFalse(taken.isMissedAt(firedAt + 60 * minute))
    }
}
