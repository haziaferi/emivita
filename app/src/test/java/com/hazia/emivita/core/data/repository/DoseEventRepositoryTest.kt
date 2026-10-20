package com.hazia.emivita.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeSchedulingCoordinator : SchedulingCoordinator {
    val resolvedReminderIds = mutableListOf<Long>()
    val nagCancelledDoseEventIds = mutableListOf<Long>()
    val medicineChangedIds = mutableListOf<Long>()
    override suspend fun onDoseResolved(reminderId: Long) {
        resolvedReminderIds += reminderId
    }
    override suspend fun onReminderChanged(reminderId: Long, notBeforeEpochMillis: Long) = Unit
    override suspend fun onReminderRemoved(reminderId: Long) = Unit
    // Recorded, not ignored: every stock-moving path in DoseEventRepository has
    // to re-arm that medicine's alert alarms (spec §7 R37), and the tests below
    // assert it does.
    override suspend fun onMedicineChanged(medicineId: Long) {
        medicineChangedIds += medicineId
    }
    override suspend fun rescheduleAll() = Unit
    override suspend fun cancelNag(reminderId: Long, doseEventId: Long) {
        nagCancelledDoseEventIds += doseEventId
    }
}

/**
 * Regression coverage for spec §7 R3 ("no tests for resolve()/tag-dedupe
 * race-prone logic") -- an in-memory (unencrypted) Room instance rather than
 * mocked DAOs, since [resolve]/[DoseEventRepository.switchStatus] both go
 * through `database.withTransaction { }`, an *inline* extension function
 * MockK can't intercept; a real RoomDatabase is the only way to exercise
 * that path faithfully. Bypasses [EmivitaDatabase.getInstance]'s SQLCipher
 * `openHelperFactory` entirely -- that's an at-rest encryption concern, not
 * part of what these tests are verifying, and SQLCipher's native library
 * doesn't load under Robolectric's host-JVM environment anyway.
 */
@RunWith(RobolectricTestRunner::class)
class DoseEventRepositoryTest {
    private lateinit var database: EmivitaDatabase
    private lateinit var repository: DoseEventRepository
    private lateinit var schedulingCoordinator: FakeSchedulingCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        schedulingCoordinator = FakeSchedulingCoordinator()
        repository = DoseEventRepository(
            database = database,
            doseEventDao = database.doseEventDao(),
            medicineDao = database.medicineDao(),
            reminderDao = database.reminderDao(),
            schedulingCoordinator = schedulingCoordinator,
            occurrenceProjectionCache = mockk<OccurrenceProjectionCache>(relaxed = true),
            autoSyncManager = mockk<AutoSyncManager>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedMedicineWithStock(quantity: Double): Long {
        val profileId = database.profileDao().insert(Profile(name = "Test Profile", createdAtEpochMillis = 0))
        return database.medicineDao().insert(
            Medicine(
                profileId = profileId,
                name = "Ibuprofen",
                iconKey = "pill",
                stockInfo = StockInfo(quantity = quantity, unit = "tablet"),
                sortOrder = 0.0,
                createdAtEpochMillis = 0,
            )
        )
    }

    private suspend fun seedReminder(medicineId: Long): Long =
        database.reminderDao().insert(
            Reminder(medicineId = medicineId, type = ReminderType.FIXED_TIME, configJson = "{}", createdAtEpochMillis = 0)
        )

    private suspend fun seedPendingEvent(reminderId: Long, medicineId: Long, doseAmount: Double): Long =
        database.doseEventDao().insert(
            DoseEvent(reminderId = reminderId, medicineId = medicineId, scheduledEpochMillis = 0, doseAmount = doseAmount, createdAtEpochMillis = 0)
        )

    private suspend fun stockOf(medicineId: Long): Double =
        database.medicineDao().getById(medicineId)!!.stockInfo.quantity

    // ---- Correcting a missed dose (spec §7 R46) ----------------------------

    private suspend fun seedPendingEventAt(reminderId: Long, medicineId: Long, scheduledEpochMillis: Long): Long =
        database.doseEventDao().insert(
            DoseEvent(
                reminderId = reminderId,
                medicineId = medicineId,
                scheduledEpochMillis = scheduledEpochMillis,
                doseAmount = 2.0,
                createdAtEpochMillis = 0,
            )
        )

    @Test
    fun `resolving retroactively records the time given, not now`() = runTest {
        // The whole point of the parameter. A dose taken on Tuesday and only
        // recorded on Friday happened on Tuesday, and the History this app
        // exists to hand a clinician should say so.
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val tuesday = 1_700_000_000_000L
        val eventId = seedPendingEventAt(reminderId, medicineId, tuesday)

        repository.resolve(eventId, DoseStatus.TAKEN, resolvedAtEpochMillis = tuesday)

        val stored = database.doseEventDao().getById(eventId)!!
        assertEquals(DoseStatus.TAKEN, stored.status)
        assertEquals(tuesday, stored.resolvedAtEpochMillis)
        // Stock still moves: the pill left the bottle when it was taken, so the
        // count has been one dose too high ever since and correcting the record
        // is what brings it back in line.
        assertEquals(8.0, stockOf(medicineId), 0.001)
    }

    @Test
    fun `a backdated correction does not become the ON_RESOLVED anchor over a newer dose`() = runTest {
        // DoseEventDao.getLastResolved picks a reminder's ON_RESOLVED anchor
        // with ORDER BY resolvedAtEpochMillis DESC, so a correction stamped
        // "now" would let tidying up last Tuesday's missed dose on Friday
        // re-anchor a live interval reminder to Friday. Recording the truthful
        // time is what makes it safe, so this asserts the anchor, not just the row.
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val tuesday = 1_700_000_000_000L
        val thursday = tuesday + 2 * 24 * 60 * 60 * 1000L

        val newer = seedPendingEventAt(reminderId, medicineId, thursday)
        repository.resolve(newer, DoseStatus.TAKEN, resolvedAtEpochMillis = thursday)

        val missed = seedPendingEventAt(reminderId, medicineId, tuesday)
        repository.resolve(missed, DoseStatus.TAKEN, resolvedAtEpochMillis = tuesday)

        val anchor = database.doseEventDao().getLastResolved(reminderId)!!
        assertEquals(
            "the anchor must stay on the genuinely most recent dose, not the one corrected last",
            thursday,
            anchor.resolvedAtEpochMillis,
        )
    }

    @Test
    fun `a correction newer than everything else does become the anchor`() = runTest {
        // The other half, so the test above cannot pass by the anchor simply
        // ignoring corrections. When the corrected dose really is the most
        // recent one taken, that is exactly what the anchor means.
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val monday = 1_700_000_000_000L
        val wednesday = monday + 2 * 24 * 60 * 60 * 1000L

        val older = seedPendingEventAt(reminderId, medicineId, monday)
        repository.resolve(older, DoseStatus.TAKEN, resolvedAtEpochMillis = monday)

        val missed = seedPendingEventAt(reminderId, medicineId, wednesday)
        repository.resolve(missed, DoseStatus.TAKEN, resolvedAtEpochMillis = wednesday)

        assertEquals(wednesday, database.doseEventDao().getLastResolved(reminderId)!!.resolvedAtEpochMillis)
    }

    @Test
    fun `resolve TAKEN decrements stock and notifies the scheduling coordinator`() = runTest {
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 2.0)

        repository.resolve(eventId, DoseStatus.TAKEN)

        val updated = database.doseEventDao().getById(eventId)!!
        assertEquals(DoseStatus.TAKEN, updated.status)
        assertEquals(10.0, updated.stockBefore, 0.001)
        assertEquals(8.0, updated.stockAfter, 0.001)
        assertEquals(8.0, database.medicineDao().getById(medicineId)!!.stockInfo.quantity, 0.001)
        assertEquals(listOf(reminderId), schedulingCoordinator.resolvedReminderIds)
        assertEquals(listOf(eventId), schedulingCoordinator.nagCancelledDoseEventIds)
    }

    @Test
    fun `resolve SKIPPED records hypothetical sentinels without touching actual stock`() = runTest {
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 2.0)

        repository.resolve(eventId, DoseStatus.SKIPPED)

        val updated = database.doseEventDao().getById(eventId)!!
        assertEquals(DoseStatus.SKIPPED, updated.status)
        // "What it would have been if taken" is still recorded, per ADR-0004,
        // so a later switchStatus() has a real value to reapply.
        assertEquals(10.0, updated.stockBefore, 0.001)
        assertEquals(8.0, updated.stockAfter, 0.001)
        // But the actual medicine row is untouched -- a skip never spends stock.
        assertEquals(10.0, database.medicineDao().getById(medicineId)!!.stockInfo.quantity, 0.001)
    }

    @Test
    fun `switchStatus from Skipped to Taken spends the recorded amount`() = runTest {
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 2.0)
        repository.resolve(eventId, DoseStatus.SKIPPED)

        repository.switchStatus(eventId, DoseStatus.TAKEN)

        assertEquals(DoseStatus.TAKEN, database.doseEventDao().getById(eventId)!!.status)
        assertEquals(8.0, database.medicineDao().getById(medicineId)!!.stockInfo.quantity, 0.001)
    }

    @Test
    fun `switchStatus back to Skipped gives the recorded amount back`() = runTest {
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 2.0)
        repository.resolve(eventId, DoseStatus.TAKEN)
        assertEquals(8.0, database.medicineDao().getById(medicineId)!!.stockInfo.quantity, 0.001)

        repository.switchStatus(eventId, DoseStatus.SKIPPED)

        assertEquals(DoseStatus.SKIPPED, database.doseEventDao().getById(eventId)!!.status)
        assertEquals(10.0, database.medicineDao().getById(medicineId)!!.stockInfo.quantity, 0.001)
    }

    @Test
    fun `resolve on an untracked medicine leaves the sentinel pair at its not-tracked default`() = runTest {
        val medicineId = seedMedicineWithStock(0.0).let {
            // Re-seed without a unit -- untracked medicines have stockInfo.unit == null.
            val profileId = database.medicineDao().getById(it)!!.profileId
            database.medicineDao().insert(
                Medicine(profileId = profileId, name = "Vitamin D", iconKey = "pill", sortOrder = 1.0, createdAtEpochMillis = 0)
            )
        }
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 1.0)

        repository.resolve(eventId, DoseStatus.TAKEN)

        val updated = database.doseEventDao().getById(eventId)!!
        assertEquals(-1.0, updated.stockBefore, 0.001)
        assertEquals(-1.0, updated.stockAfter, 0.001)
    }

    @Test
    fun `switchStatus to Skipped preserves a refill made after the dose was resolved`() = runTest {
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 2.0)
        repository.resolve(eventId, DoseStatus.TAKEN)
        assertEquals(8.0, stockOf(medicineId), 0.001)

        repository.logRefill(medicineId, refillAmount = 60.0, atEpochMillis = 1)
        assertEquals(68.0, stockOf(medicineId), 0.001)

        repository.switchStatus(eventId, DoseStatus.SKIPPED)

        // The absolute write this replaced put stock back to the recorded
        // stockBefore of 10.0, destroying the 60-unit refill outright.
        assertEquals(70.0, stockOf(medicineId), 0.001)
    }

    @Test
    fun `switchStatus to Skipped preserves a later dose taken from the same medicine`() = runTest {
        // The everyday version of the same defect: a twice-daily medicine
        // whose morning dose is corrected after the evening one was taken.
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val morning = seedPendingEvent(reminderId, medicineId, doseAmount = 2.0)
        val evening = database.doseEventDao().insert(
            DoseEvent(
                reminderId = reminderId,
                medicineId = medicineId,
                scheduledEpochMillis = 1,
                doseAmount = 3.0,
                createdAtEpochMillis = 0,
            )
        )
        repository.resolve(morning, DoseStatus.TAKEN)
        repository.resolve(evening, DoseStatus.TAKEN)
        assertEquals(5.0, stockOf(medicineId), 0.001)

        repository.switchStatus(morning, DoseStatus.SKIPPED)

        // Absolute would have written 10.0, silently un-taking the evening dose.
        assertEquals(7.0, stockOf(medicineId), 0.001)
    }

    @Test
    fun `repeated switching is exactly reversible`() = runTest {
        // The rationale the absolute write carried claimed relative math
        // "could compound error across repeated switches". A delta read from
        // the recorded pair gives back exactly what it takes away, so it
        // does not -- which is why that claim is retired rather than worked
        // around. See docs/SPEC.md §7 R27.
        val medicineId = seedMedicineWithStock(10.0)
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 2.0)
        repository.resolve(eventId, DoseStatus.TAKEN)

        repeat(3) {
            repository.switchStatus(eventId, DoseStatus.SKIPPED)
            assertEquals(10.0, stockOf(medicineId), 0.001)
            repository.switchStatus(eventId, DoseStatus.TAKEN)
            assertEquals(8.0, stockOf(medicineId), 0.001)
        }
    }

    @Test
    fun `switchStatus gives back only what was actually consumed when stock ran short`() = runTest {
        // hypotheticalStockSentinels floors stockAfter at 0, so a 3-unit dose
        // against 1 unit of stock records 1 consumed, not 3. Handing back the
        // recorded difference is therefore right; handing back doseAmount
        // would invent 2 units that never existed.
        val medicineId = seedMedicineWithStock(1.0)
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 3.0)
        repository.resolve(eventId, DoseStatus.TAKEN)
        assertEquals(0.0, stockOf(medicineId), 0.001)

        repository.switchStatus(eventId, DoseStatus.SKIPPED)

        assertEquals(1.0, stockOf(medicineId), 0.001)
    }

    @Test
    fun `a dose skipped against short stock and taken after a refill spends the whole dose`() = runTest {
        // Appendix E C F-3 (§7 R103): the skip recorded the floored pair (1, 0),
        // so the switch spent 1 of the 2-unit dose and the bottle read one high.
        // A skipped dose consumed nothing; taking it consumes from stock now.
        val medicineId = seedMedicineWithStock(1.0)
        val reminderId = seedReminder(medicineId)
        val eventId = seedPendingEvent(reminderId, medicineId, doseAmount = 2.0)
        repository.resolve(eventId, DoseStatus.SKIPPED)
        repository.logRefill(medicineId, refillAmount = 29.0, atEpochMillis = 1)
        assertEquals(30.0, stockOf(medicineId), 0.001)

        repository.switchStatus(eventId, DoseStatus.TAKEN)
        assertEquals(28.0, stockOf(medicineId), 0.001)

        // Still exactly reversible: the pair now records what this take consumed.
        repository.switchStatus(eventId, DoseStatus.SKIPPED)
        assertEquals(30.0, stockOf(medicineId), 0.001)
    }

    @Test
    fun `logRefill on a medicine with no unit records nothing and returns null`() = runTest {
        // §7 R60 C F-2: withStockSentinels skips an untracked medicine, so this
        // used to insert a REFILLED row whose sentinels said "not tracked" while
        // the quantity stayed put -- History said refilled, the count did not move.
        val profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        val medicineId = database.medicineDao().insert(
            Medicine(profileId = profileId, name = "Vitamin D", iconKey = "pill",
                stockInfo = StockInfo(quantity = 0.0, unit = null), sortOrder = 0.0, createdAtEpochMillis = 0),
        )

        val id = repository.logRefill(medicineId, refillAmount = 30.0, atEpochMillis = 1)

        assertNull(id)
        assertNull(database.doseEventDao().getMostRecentForMedicine(medicineId))
        assertEquals(0.0, stockOf(medicineId), 0.001)
    }
}
