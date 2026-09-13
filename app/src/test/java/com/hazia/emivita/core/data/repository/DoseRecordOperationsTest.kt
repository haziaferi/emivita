package com.hazia.emivita.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** History as a record (spec §9 #26): the stock recount on an amount edit
 *  (Appendix F PT-F1), reopen, soft delete, reschedule, the dose-count
 *  course, archive instead of delete, and the notes journal. */
@RunWith(RobolectricTestRunner::class)
class DoseRecordOperationsTest {
    private lateinit var database: EmivitaDatabase
    private lateinit var doses: DoseEventRepository
    private lateinit var medicines: MedicineRepository
    private lateinit var coordinator: SchedulingCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        coordinator = mockk(relaxed = true)
        doses = DoseEventRepository(
            database = database, doseEventDao = database.doseEventDao(), medicineDao = database.medicineDao(),
            reminderDao = database.reminderDao(), schedulingCoordinator = coordinator,
            occurrenceProjectionCache = mockk<OccurrenceProjectionCache>(relaxed = true), autoSyncManager = mockk<AutoSyncManager>(relaxed = true),
        )
        medicines = MedicineRepository(
            database = database, medicineDao = database.medicineDao(), tagDao = database.tagDao(),
            doseEventDao = database.doseEventDao(), reminderDao = database.reminderDao(), medicineNoteDao = database.medicineNoteDao(),
            schedulingCoordinator = coordinator, occurrenceProjectionCache = mockk(relaxed = true), autoSyncManager = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() = database.close()

    private var profileId = 0L
    private suspend fun medicine(stock: Double = 10.0): Long {
        if (profileId == 0L) profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        return database.medicineDao().insert(Medicine(profileId = profileId, name = "M", iconKey = "pill", stockInfo = StockInfo(quantity = stock, unit = "tablet"), sortOrder = 0.0, createdAtEpochMillis = 0))
    }
    private suspend fun reminder(medicineId: Long, dosesRemaining: Int? = null): Long = database.reminderDao().insert(
        Reminder(medicineId = medicineId, type = ReminderType.FIXED_TIME, dosesRemaining = dosesRemaining,
            configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0)), createdAtEpochMillis = 0),
    )
    private suspend fun pending(medicineId: Long, reminderId: Long, amount: Double = 1.0, at: Long = 1_000_000L): Long =
        database.doseEventDao().insert(DoseEvent(reminderId = reminderId, medicineId = medicineId, scheduledEpochMillis = at, doseAmount = amount, createdAtEpochMillis = 0))
    private suspend fun stock(medicineId: Long) = database.medicineDao().getById(medicineId)!!.stockInfo.quantity

    @Test
    fun `editing a taken dose's amount moves the stock by the difference and re-writes the pair`() = runTest {
        val m = medicine(10.0); val r = reminder(m); val e = pending(m, r, amount = 1.0)
        doses.resolve(e, DoseStatus.TAKEN)
        assertEquals(9.0, stock(m), 0.001)
        doses.editDoseAmount(e, 3.0)
        assertEquals("two more tablets left the bottle", 7.0, stock(m), 0.001)
        val row = database.doseEventDao().getById(e)!!
        assertEquals(10.0, row.stockBefore, 0.001); assertEquals(7.0, row.stockAfter, 0.001)
        doses.editDoseAmount(e, 0.5)
        assertEquals(9.5, stock(m), 0.001)
        // and the switch still reverses the right amount afterwards
        doses.switchStatus(e, DoseStatus.SKIPPED)
        assertEquals(10.0, stock(m), 0.001)
    }

    @Test
    fun `editing a skipped or pending dose's amount changes the number, not the bottle`() = runTest {
        val m = medicine(10.0); val r = reminder(m)
        val skipped = pending(m, r); doses.resolve(skipped, DoseStatus.SKIPPED)
        doses.editDoseAmount(skipped, 4.0)
        assertEquals(10.0, stock(m), 0.001)
        assertEquals(6.0, database.doseEventDao().getById(skipped)!!.stockAfter, 0.001)
        val open = pending(m, r, at = 2_000_000L)
        doses.editDoseAmount(open, 2.0)
        assertEquals(10.0, stock(m), 0.001)
        assertEquals(-1.0, database.doseEventDao().getById(open)!!.stockAfter, 0.001)
    }

    @Test
    fun `reopening a taken dose gives the stock back, clears the pair, and re-arms through the coordinator`() = runTest {
        val m = medicine(10.0); val r = reminder(m); val e = pending(m, r, amount = 2.0)
        doses.resolve(e, DoseStatus.TAKEN)
        assertEquals(8.0, stock(m), 0.001)
        val reopened = doses.reopen(e)!!
        assertEquals(DoseStatus.PENDING, reopened.status)
        assertNull(reopened.resolvedAtEpochMillis)
        assertEquals(-1.0, reopened.stockBefore, 0.001)
        assertEquals(10.0, stock(m), 0.001)
        coVerify { coordinator.onReminderChanged(r, any()) }
        assertNull("a pending dose cannot be reopened", doses.reopen(e))
    }

    @Test
    fun `soft-deleting hides the row from every read, keeps its slot claimed, and gives stock back`() = runTest {
        val m = medicine(10.0); val r = reminder(m); val e = pending(m, r, amount = 2.0)
        doses.resolve(e, DoseStatus.TAKEN)
        doses.softDelete(e)
        assertEquals(10.0, stock(m), 0.001)
        assertEquals(DoseStatus.DELETED, database.doseEventDao().getById(e)!!.status)
        assertTrue(doses.observeInRange(profileId, 0L, 5_000_000L).first().isEmpty())
        assertNull("a resolved anchor never points at a deleted dose", database.doseEventDao().getLastResolved(r))
        assertNull("the slot stays claimed: nothing new is materialised for it", doses.materializeIfAbsent(r, m, 1_000_000L, 1.0))
        assertFalse("a refill is not deletable this way", run { val ref = doses.logRefill(m, 5.0, 3_000_000L)!!; doses.softDelete(ref); database.doseEventDao().getById(ref)!!.status == DoseStatus.DELETED })
    }

    @Test
    fun `rescheduling a pending dose moves its slot and asks for a one-off alarm there`() = runTest {
        val m = medicine(); val r = reminder(m); val e = pending(m, r)
        assertTrue(doses.reschedule(e, 4_000_000L))
        assertEquals(4_000_000L, database.doseEventDao().getById(e)!!.scheduledEpochMillis)
        coVerify(exactly = 1) { coordinator.onDoseRescheduled(r, e, 4_000_000L) }
        doses.resolve(e, DoseStatus.TAKEN)
        assertFalse("only a pending dose can move", doses.reschedule(e, 5_000_000L))
    }

    @Test
    fun `take now on a projected slot materialises it and answers Taken, once`() = runTest {
        val m = medicine(10.0); val r = reminder(m)
        doses.takeProjectedNow(r, m, 7_000_000L, 1.0, nowEpochMillis = 6_500_000L)
        val row = database.doseEventDao().findByReminderAndTime(r, 7_000_000L)!!
        assertEquals(DoseStatus.TAKEN, row.status)
        assertEquals(6_500_000L, row.resolvedAtEpochMillis)
        assertEquals(9.0, stock(m), 0.001)
        // a second tap (or the alarm) finds the row already answered and changes nothing
        doses.takeProjectedNow(r, m, 7_000_000L, 1.0, nowEpochMillis = 6_600_000L)
        assertEquals(6_500_000L, database.doseEventDao().findByReminderAndTime(r, 7_000_000L)!!.resolvedAtEpochMillis)
        assertEquals(9.0, stock(m), 0.001)
    }

    @Test
    fun `a dose-count course counts down on Taken only and stops at zero`() = runTest {
        val m = medicine(); val r = reminder(m, dosesRemaining = 2)
        doses.resolve(pending(m, r, at = 1L), DoseStatus.SKIPPED)
        assertEquals(2, database.reminderDao().getById(r)!!.dosesRemaining)
        doses.resolve(pending(m, r, at = 2L), DoseStatus.TAKEN)
        assertEquals(1, database.reminderDao().getById(r)!!.dosesRemaining)
        doses.resolve(pending(m, r, at = 3L), DoseStatus.TAKEN)
        doses.resolve(pending(m, r, at = 4L), DoseStatus.TAKEN)
        assertEquals("floored at zero", 0, database.reminderDao().getById(r)!!.dosesRemaining)
    }

    @Test
    fun `archiving hides a medicine, pauses its reminders, cancels their alarms and keeps its history`() = runTest {
        val m = medicine(); val r = reminder(m); val e = pending(m, r)
        doses.resolve(e, DoseStatus.TAKEN)
        val med = database.medicineDao().getById(m)!!
        medicines.archive(med)
        assertTrue(medicines.observeForProfile(profileId).first().isEmpty())
        assertEquals(listOf(m), medicines.observeArchivedForProfile(profileId).first().map { it.id })
        assertFalse("reminders are paused, not deleted -- deleting them cascades to the history", database.reminderDao().observeForMedicine(m).first().single().enabled)
        coVerify(exactly = 1) { coordinator.onReminderRemoved(r) }
        assertEquals("the record survives", 1, doses.observeInRange(profileId, 0L, 5_000_000L).first().size)
        medicines.unarchive(m)
        assertEquals(listOf(m), medicines.observeForProfile(profileId).first().map { it.medicine.id })
    }

    @Test
    fun `the notes journal adds, edits with an edited stamp, deletes, and cascades with the medicine`() = runTest {
        val m = medicine()
        val id = medicines.addNote(m, "  Dizzy after the dose went up  ")
        val note = medicines.observeNotes(m).first().single()
        assertEquals("Dizzy after the dose went up", note.text)
        assertNull(note.editedAtEpochMillis)
        medicines.editNote(note, "Dizzy for two days")
        val edited = medicines.observeNotes(m).first().single()
        assertEquals("Dizzy for two days", edited.text)
        assertTrue(edited.editedAtEpochMillis != null)
        medicines.deleteNote(id)
        assertTrue(medicines.observeNotes(m).first().isEmpty())
        medicines.addNote(m, "kept")
        medicines.deletePermanently(database.medicineDao().getById(m)!!)
        assertTrue(database.medicineNoteDao().getForMedicine(m).isEmpty())
    }
}
