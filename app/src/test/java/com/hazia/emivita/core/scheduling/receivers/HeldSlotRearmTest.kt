package com.hazia.emivita.core.scheduling.receivers

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A re-arm while the OS holds a dose's alarm (§7 R143, I1 and I2 of the PR #62
 * review). Every re-arm starts from now, so it replaced the held alarm with
 * the next slot: an edit or an answer elsewhere lost the dose, and Take on
 * Today lost the follow-up a "when reminded" link arms only when that alarm
 * arrives. Real container; the slot is three minutes back, its alarm armed
 * and not delivered, no dose row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HeldSlotRearmTest {

    private val h = ReceiverHarness()
    private val zone: ZoneId = ZoneId.systemDefault()

    @After
    fun tearDown() = h.close()

    private val slot: LocalDateTime = LocalDateTime.now(zone).minusMinutes(3).withSecond(0).withNano(0)
    private val at = slot.atZone(zone).toInstant().toEpochMilli()

    private fun heldReminder(medicineId: Long): Long {
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0))
        h.container.alarmScheduler.schedule(reminderId, at)
        return reminderId
    }

    /** The held slot is still the armed one, and delivering it writes its dose. */
    private fun assertHeldSlotStillReachesItsDose(reminderId: Long) {
        val held = h.regularAlarms(reminderId).filter { h.scheduledAtOf(it) == at }
        assertEquals("the held slot is still armed", 1, held.size)
        h.fire(held.single())
        assertEquals(listOf(DoseStatus.PENDING), h.doseEvents(reminderId).filter { it.scheduledEpochMillis == at }.map { it.status })
    }

    @Test
    fun `editing a reminder while the phone holds its dose keeps that dose`() {
        val reminderId = heldReminder(h.medicine())
        runBlocking {
            val reminder = h.container.reminderRepository.getById(reminderId)!!
            h.container.reminderRepository.updateConfig(reminder, ReminderConfig.FixedTime(slot.hour, slot.minute, 2.0))
        }
        assertHeldSlotStillReachesItsDose(reminderId)
    }

    @Test
    fun `answering another dose of the reminder while the phone holds this one keeps it`() {
        // Yesterday's dose of the same reminder, still open, answered from its notification during the hold.
        val medicineId = h.medicine()
        val reminderId = heldReminder(medicineId)
        runBlocking {
            val yesterday = h.container.doseEventRepository.materializeIfAbsent(reminderId, medicineId, at - 24L * 60 * 60 * 1000, 1.0)!!
            h.container.doseEventRepository.resolve(yesterday, DoseStatus.TAKEN)
        }
        assertHeldSlotStillReachesItsDose(reminderId)
    }

    @Test
    fun `Take on a held dose still arms its follow-up`() {
        val medicineId = h.medicine()
        val parent = heldReminder(medicineId)
        val child = runBlocking {
            h.container.reminderRepository.create(
                medicineId = medicineId, groupId = null, type = ReminderType.LINKED_FOLLOWING,
                config = ReminderConfig.LinkedFollowing(30, 1.0, IntervalStartBehavior.ON_REMINDED), parentReminderId = parent,
            )
        }
        runBlocking { h.container.doseEventRepository.takeProjectedNow(parent, medicineId, at, 1.0) }
        assertEquals(listOf(at + 30 * 60_000L), h.regularAlarms(child).map { h.scheduledAtOf(it) })
    }
}
