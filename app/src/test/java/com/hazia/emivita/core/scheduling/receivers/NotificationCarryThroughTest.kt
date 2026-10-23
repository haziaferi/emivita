package com.hazia.emivita.core.scheduling.receivers

import android.app.Application
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.notification.ACTION_SKIP
import com.hazia.emivita.core.notification.ACTION_SNOOZE
import com.hazia.emivita.core.notification.ACTION_TAKE
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import com.hazia.emivita.feature.alarm.TakeAmountActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * What R107–R112 left uncarried between the record and the shade (§7 R113):
 * an answer given anywhere takes the dose's notification away; a sticky
 * notification whose dose is gone can still be answered away; deleting a
 * reminder or a medicine takes its notifications with it; and a Take from a
 * notification posted before "asks the amount" was turned on asks it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationCarryThroughTest {

    private val h = ReceiverHarness()

    @After
    fun tearDown() = h.close()

    private data class Shown(val medicineId: Long, val reminderId: Long, val doseId: Long)

    /** A medicine, a daily 09:00 reminder, its first fire: one dose showing. */
    private fun shown(name: String = "Ibuprofen", sticky: Boolean = false, asksAmount: Boolean = false): Shown {
        val medicineId = h.medicine(name)
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(hour = 9, minute = 0, dose = 1.0), sticky = sticky, variableAmount = asksAmount)
        h.fire(h.regularAlarms(reminderId).single())
        val doseId = h.doseEvents(reminderId).single().id
        assertTrue("the dose's notification is showing", doseId.toInt() in h.notificationIds)
        return Shown(medicineId, reminderId, doseId)
    }

    // ---- an answer in the app or the widget (repository) clears the shade ----

    @Test
    fun `resolving a dose through the repository, as Today and the widget do, removes its notification`() {
        val a = shown("Atorvastatin")
        val b = shown("Ramipril")
        runBlocking { h.container.doseEventRepository.resolve(a.doseId, DoseStatus.TAKEN) }
        assertEquals(listOf(b.doseId.toInt()), h.notificationIds.filter { it > 0 })
        // The summary stays while the group has a child: cancelling it would
        // cancel the other dose's reminder too (§7 R115).
        assertEquals("one dose left keeps the summary", 1, h.notificationIds.count { it < 0 })
    }

    // ---- a sticky notification whose dose is gone ----------------------------

    /** A notification for a dose with no row: what a sticky one becomes when
     *  its dose goes by a path that does not clear the shade. */
    private fun orphanShowing(): Int {
        val s = shown(sticky = true)
        val reminder = runBlocking { checkNotNull(h.container.reminderRepository.getById(s.reminderId)) }
        val medicine = h.medicineRow(s.medicineId)
        val orphan = DoseEvent(id = 9_999L, reminderId = s.reminderId, medicineId = s.medicineId, scheduledEpochMillis = 0L, doseAmount = 1.0, createdAtEpochMillis = 0L)
        runBlocking { h.container.notificationBuilder.buildAndPost(orphan, reminder, medicine) }
        assertTrue(9_999 in h.notificationIds)
        return 9_999
    }

    @Test
    fun `Take on a notification whose dose is gone removes the notification`() {
        val id = orphanShowing()
        h.action(ACTION_TAKE, id.toLong())
        assertTrue("still showing: ${h.notificationIds}", id !in h.notificationIds)
    }

    @Test
    fun `Skip on a notification whose dose is gone removes the notification`() {
        val id = orphanShowing()
        h.action(ACTION_SKIP, id.toLong())
        assertTrue("still showing: ${h.notificationIds}", id !in h.notificationIds)
    }

    @Test
    fun `Snooze on a notification whose dose is gone removes the notification`() {
        val id = orphanShowing()
        h.action(ACTION_SNOOZE, id.toLong())
        assertTrue("still showing: ${h.notificationIds}", id !in h.notificationIds)
    }

    // ---- deleting the reminder or the medicine --------------------------------

    @Test
    fun `deleting a reminder removes its sticky notification`() {
        val s = shown(sticky = true)
        val reminder = runBlocking { checkNotNull(h.container.reminderRepository.getById(s.reminderId)) }
        runBlocking { h.container.reminderRepository.delete(reminder) }
        assertEquals(emptyList<Int>(), h.notificationIds)
    }

    @Test
    fun `deleting a medicine for good removes its sticky notification and leaves the other`() {
        val gone = shown("Atorvastatin", sticky = true)
        val kept = shown("Ramipril", sticky = true)
        runBlocking { h.container.medicineRepository.deletePermanently(h.medicineRow(gone.medicineId)) }
        assertEquals(listOf(kept.doseId.toInt()), h.notificationIds.filter { it > 0 })
    }

    // ---- a stale Take on a reminder that now asks the amount ----------------

    @Test
    fun `a broadcast Take on a reminder that asks the amount opens the amount screen and records nothing`() {
        val s = shown(asksAmount = true)
        h.action(ACTION_TAKE, s.doseId)
        assertEquals(DoseStatus.PENDING, h.doseEvent(s.doseId).status)
        assertEquals(10.0, h.medicineRow(s.medicineId).stockInfo.quantity, 0.0)
        val started = shadowOf(h.context as Application).nextStartedActivity
        assertNotNull("Take opened no screen", started)
        assertEquals(TakeAmountActivity::class.java.name, started.component?.className)
        assertEquals(s.doseId, started.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L))
        assertTrue("the dose stays showing until the amount is typed", s.doseId.toInt() in h.notificationIds)
    }
}
