package com.hazia.emivita.core.data.repository

import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.feature.history.ExportRange
import com.hazia.emivita.feature.history.adherenceSubtitle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Deleting a reminder keeps its doses (§7 R118), as its dialog says: "Its past
 * doses stay in History." On the phone a fired dose vanished from History with
 * its reminder, because `dose_events.reminderId` cascaded. They were planned,
 * so they still count to adherence; a pending one whose time has passed stays
 * and reads Missed, one still to come goes. "Delete for good" on the medicine
 * still removes every dose, these included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderDeleteKeepsDosesTest {
    private val h = ReceiverHarness()
    private val doses get() = h.container.doseEventRepository

    @After
    fun tearDown() = h.close()

    /** A fixed clock: the delete and the reading both happen at [now]. */
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private class Seeded(val medicineId: Long, val reminderId: Long, val taken: Long, val skipped: Long, val pastPending: Long, val futurePending: Long)

    private fun seed(): Seeded = runBlocking {
        val medicineId = h.medicine("Metformin")
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(8, 0, 1.0), repeat = RepeatConfig(delayMinutes = 10, maxRepeats = 3))
        suspend fun slot(at: Long) = checkNotNull(doses.materializeIfAbsent(reminderId, medicineId, at, 1.0))
        val taken = slot(now - 3 * hour).also { doses.resolve(it, DoseStatus.TAKEN, now - 3 * hour) }
        val skipped = slot(now - 2 * hour).also { doses.resolve(it, DoseStatus.SKIPPED, now - 2 * hour) }
        // Fired five minutes ago and still repeating: due until its last repeat.
        val pastPending = slot(now - 5 * 60_000L).also { doses.recordShown(it, now - 5 * 60_000L, RepeatConfig(10, 3), 0) }
        // Moved to later, so it exists before its slot (§9 #26 reschedule).
        val futurePending = slot(now + hour)
        Seeded(medicineId, reminderId, taken, skipped, pastPending, futurePending)
    }

    private fun deleteReminder(s: Seeded) = runBlocking {
        h.container.reminderRepository.delete(checkNotNull(h.container.reminderRepository.getById(s.reminderId)), now)
    }

    private fun rows() = runBlocking { doses.getInRangeOnce(h.profileId, 0L, Long.MAX_VALUE, null) }

    @Test
    fun `the answered doses stay in History, detached from the deleted reminder and still planned`() {
        val s = seed()
        deleteReminder(s)
        val kept = rows().associateBy { it.doseEvent.id }
        assertEquals(DoseStatus.TAKEN, kept[s.taken]?.doseEvent?.status)
        assertEquals(DoseStatus.SKIPPED, kept[s.skipped]?.doseEvent?.status)
        assertTrue(kept.values.all { it.doseEvent.reminderId == null && it.doseEvent.planned })
    }

    @Test
    fun `a pending dose whose time has passed stays and reads Missed, one still to come goes`() {
        val s = seed()
        deleteReminder(s)
        val kept = rows().associateBy { it.doseEvent.id }
        assertEquals(setOf(s.taken, s.skipped, s.pastPending), kept.keys)
        assertEquals("its repeats went with the reminder", null, kept.getValue(s.pastPending).doseEvent.missedAfterEpochMillis)
    }

    @Test
    fun `they still count to adherence`() {
        val s = seed()
        deleteReminder(s)
        assertEquals(
            "50% of answered doses taken — 1 taken, 1 skipped, 1 missed, last 30 days",
            adherenceSubtitle(h.context, rows(), ExportRange.LAST_30_DAYS, now),
        )
    }

    @Test
    fun `delete for good on the medicine still removes every dose, the deleted reminder's included`() {
        val s = seed()
        deleteReminder(s)
        val asNeeded = runBlocking { doses.logAdhocDose(s.medicineId, 1.0, now) }
        runBlocking { h.container.medicineRepository.deletePermanently(h.medicineRow(s.medicineId)) }
        val left = listOf(s.taken, s.skipped, s.pastPending, asNeeded).filter { runBlocking { doses.getById(it) } != null }
        assertEquals(emptyList<Long>(), left)
    }
}
