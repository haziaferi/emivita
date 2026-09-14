package com.hazia.emivita.core.scheduling.receivers

import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.settings.SwipeAction
import com.hazia.emivita.core.notification.ACTION_SKIP
import com.hazia.emivita.core.notification.ACTION_SNOOZE
import com.hazia.emivita.core.notification.ACTION_SWIPED
import com.hazia.emivita.core.notification.ACTION_TAKE
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The notification's buttons and its swipe, on the JVM (spec §9 #30;
 * Appendix E B-47). medTimer's `singleTaken` and `removeSingle` in Emivita's
 * terms, then the paths the inventory listed as untested: snooze -- its
 * arming and its re-fire -- the lock's rejection of Skip, and the swipe
 * setting's three meanings. Same harness as [ReminderAlarmReceiverTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderActionReceiverTest {

    private val h = ReceiverHarness()

    /** DataStore outlives the Robolectric application between tests; the
     *  two settings written here go back to their defaults. */
    @After
    fun tearDown() {
        runBlocking { h.container.settingsRepository.setSwipeAction(SwipeAction.SNOOZE) }
        h.close()
    }

    private fun daily() = ReminderConfig.FixedTime(hour = 9, minute = 0, dose = 1.0)

    /** A medicine, a daily reminder, its first fire: one pending dose showing. */
    private fun pendingDose(stock: Double = 10.0, locked: Boolean? = null): Pair<Long, Long> {
        val medicineId = h.medicine(stock = stock, locked = locked)
        val reminderId = h.reminder(medicineId, daily())
        h.fire(h.regularAlarms(reminderId).single())
        return reminderId to h.doseEvents(reminderId).single().id
    }

    @Test
    fun `singleTaken -- Take resolves the dose, stamps when, decrements stock, clears the notification`() {
        val medicineId = h.medicine(stock = 10.0)
        val reminderId = h.reminder(medicineId, daily())
        h.fire(h.regularAlarms(reminderId).single())
        val doseId = h.doseEvents(reminderId).single().id

        h.action(ACTION_TAKE, doseId)

        val event = h.doseEvent(doseId)
        assertEquals(DoseStatus.TAKEN, event.status)
        assertNotNull(event.resolvedAtEpochMillis)
        assertEquals(9.0, h.medicineRow(medicineId).stockInfo.quantity, 0.0)
        assertEquals(emptyList<Int>(), h.notificationIds)
    }

    @Test
    fun `removeSingle -- skipping one of two showing doses leaves the other showing and pending`() {
        val (_, first) = pendingDose()
        val (_, second) = pendingDose()
        assertTrue("both doses and the group summary are showing", h.notificationIds.containsAll(listOf(first.toInt(), second.toInt())))

        h.action(ACTION_SKIP, second)

        assertEquals(DoseStatus.SKIPPED, h.doseEvent(second).status)
        assertEquals(DoseStatus.PENDING, h.doseEvent(first).status)
        assertEquals(listOf(first.toInt()), h.notificationIds.filter { it > 0 })
        assertEquals("one dose showing needs no summary", emptyList<Int>(), h.notificationIds.filter { it < 0 })
    }

    @Test
    fun `Snooze arms the snooze slot carrying the original dose, clears the notification, and its fire re-shows the same dose`() {
        val (reminderId, doseId) = pendingDose()
        val minutes = runBlocking { h.container.settingsRepository.snoozeDurationMinutes.first() }
        val scheduledAt = h.doseEvent(doseId).scheduledEpochMillis

        h.action(ACTION_SNOOZE, doseId)

        assertEquals(emptyList<Int>(), h.notificationIds)
        val snooze = h.snoozeAlarms(reminderId).single()
        assertEquals(scheduledAt, h.scheduledAtOf(snooze))
        assertTrue("re-fires in about $minutes minutes", snooze.triggerAtMs - System.currentTimeMillis() in (minutes - 1) * 60_000L..minutes * 60_000L)

        h.fire(snooze)

        assertEquals(1, h.doseEvents(reminderId).size)
        assertEquals(DoseStatus.PENDING, h.doseEvent(doseId).status)
        assertEquals(listOf(doseId.toInt()), h.notificationIds)
    }

    @Test
    fun `Snooze on a dose already answered only clears the notification`() {
        val (reminderId, doseId) = pendingDose()
        h.action(ACTION_TAKE, doseId)

        h.action(ACTION_SNOOZE, doseId)

        assertEquals(emptyList<Any>(), h.snoozeAlarms(reminderId))
        assertEquals(DoseStatus.TAKEN, h.doseEvent(doseId).status)
        assertEquals(emptyList<Int>(), h.notificationIds)
    }

    @Test
    fun `Skip is rejected while the medicine is locked until taken`() {
        val (_, doseId) = pendingDose(locked = true)

        h.action(ACTION_SKIP, doseId)

        assertEquals(DoseStatus.PENDING, h.doseEvent(doseId).status)
        assertEquals(listOf(doseId.toInt()), h.notificationIds)
    }

    @Test
    fun `a swipe means Snooze by default`() {
        val (reminderId, doseId) = pendingDose()

        h.action(ACTION_SWIPED, doseId)

        assertEquals(1, h.snoozeAlarms(reminderId).size)
        assertEquals(DoseStatus.PENDING, h.doseEvent(doseId).status)
    }

    @Test
    fun `a swipe set to Skip resolves the dose, but not on a locked medicine`() {
        runBlocking { h.container.settingsRepository.setSwipeAction(SwipeAction.SKIP) }
        val (_, open) = pendingDose()
        val (_, locked) = pendingDose(locked = true)

        h.action(ACTION_SWIPED, open)
        h.action(ACTION_SWIPED, locked)

        assertEquals(DoseStatus.SKIPPED, h.doseEvent(open).status)
        assertEquals(DoseStatus.PENDING, h.doseEvent(locked).status)
    }

    @Test
    fun `a swipe set to Nothing leaves the dose due and arms nothing`() {
        runBlocking { h.container.settingsRepository.setSwipeAction(SwipeAction.NOTHING) }
        val (reminderId, doseId) = pendingDose()

        h.action(ACTION_SWIPED, doseId)

        assertEquals(DoseStatus.PENDING, h.doseEvent(doseId).status)
        assertEquals(emptyList<Any>(), h.snoozeAlarms(reminderId))
    }
}
