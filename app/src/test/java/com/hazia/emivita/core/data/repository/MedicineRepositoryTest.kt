package com.hazia.emivita.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import io.mockk.coVerify
import io.mockk.mockk
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.entity.Reminder
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for spec §7 R1/R3 -- `addTag()` used to run its
 * `Tag` insert and `MedicineTagCrossRef` attach as two separate unwrapped
 * suspend calls; a crash between them could leave an orphaned Tag row with
 * nothing pointing to it. Now wrapped in one `database.withTransaction { }`
 * (same reason [DoseEventRepositoryTest] uses a real in-memory Room instance
 * rather than mocked DAOs -- `withTransaction` is an inline extension MockK
 * can't intercept).
 */
@RunWith(RobolectricTestRunner::class)
class MedicineRepositoryTest {
    private lateinit var database: EmivitaDatabase
    private lateinit var repository: MedicineRepository
    private val scheduling = mockk<SchedulingCoordinator>(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MedicineRepository(
            database = database,
            medicineDao = database.medicineDao(),
            tagDao = database.tagDao(),
            doseEventDao = database.doseEventDao(),
            reminderDao = database.reminderDao(),
            medicineNoteDao = database.medicineNoteDao(),
            schedulingCoordinator = scheduling,
            occurrenceProjectionCache = mockk<OccurrenceProjectionCache>(relaxed = true),
            autoSyncManager = mockk<AutoSyncManager>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedMedicine(name: String, sortOrder: Double): Long {
        val profileId = database.profileDao().insert(Profile(name = "Test Profile", createdAtEpochMillis = 0))
        return database.medicineDao().insert(
            Medicine(profileId = profileId, name = name, iconKey = "pill", sortOrder = sortOrder, createdAtEpochMillis = 0)
        )
    }

    @Test
    fun `addTag creates exactly one row for a new tag name`() = runTest {
        val medicineId = seedMedicine("Ibuprofen", 0.0)

        repository.addTag(medicineId, "Pain relief")

        val tags = database.tagDao().getAllOnce()
        assertEquals(1, tags.size)
        val crossRefs = database.tagDao().getAllCrossRefsOnce()
        assertEquals(1, crossRefs.size)
        assertEquals(tags.single().id, crossRefs.single().tagId)
        assertEquals(medicineId, crossRefs.single().medicineId)
    }

    @Test
    fun `addTag reuses an existing tag by name instead of inserting a duplicate`() = runTest {
        val medicineAId = seedMedicine("Ibuprofen", 0.0)
        val medicineBId = seedMedicine("Paracetamol", 1.0)

        // Same tag name attached to two different medicines -- the dedupe path
        // (OnConflictStrategy.IGNORE returning -1, then resolved via getByName)
        // is exactly what R1's transaction wrap was protecting.
        repository.addTag(medicineAId, "Pain relief")
        repository.addTag(medicineBId, "Pain relief")

        val tags = database.tagDao().getAllOnce()
        assertEquals("addTag must not create a second Tag row for the same name", 1, tags.size)

        val crossRefs = database.tagDao().getAllCrossRefsOnce()
        assertEquals(2, crossRefs.size)
        assertEquals(setOf(medicineAId, medicineBId), crossRefs.map { it.medicineId }.toSet())
        crossRefs.forEach { assertEquals(tags.single().id, it.tagId) }
    }

    @Test
    fun `addTag is idempotent for the same medicine and tag name`() = runTest {
        val medicineId = seedMedicine("Ibuprofen", 0.0)

        repository.addTag(medicineId, "Pain relief")
        repository.addTag(medicineId, "Pain relief")

        assertEquals(1, database.tagDao().getAllOnce().size)
        // attachToMedicine() is itself OnConflictStrategy.IGNORE on the
        // (medicineId, tagId) primary key -- a repeat attach is a no-op, not
        // a second cross-ref row.
        assertEquals(1, database.tagDao().getAllCrossRefsOnce().size)
    }

    @Test
    fun `delete cascades to the medicine's reminders and every dose event, including PRN ones`() = runTest {
        val medicineId = seedMedicine("Ibuprofen", 0.0)
        val reminderId = database.reminderDao().insert(
            com.hazia.emivita.core.data.entity.Reminder(
                medicineId = medicineId,
                type = com.hazia.emivita.core.data.config.ReminderType.FIXED_TIME,
                configJson = "{}",
                createdAtEpochMillis = 0,
            )
        )
        // Reminder-linked event (cascades via reminders.medicineId -> its own
        // reminderId FK) and a PRN event (only ever linked by the denormalized
        // medicineId column, no FK at all) -- delete() must catch both.
        database.doseEventDao().insert(
            com.hazia.emivita.core.data.entity.DoseEvent(
                reminderId = reminderId, medicineId = medicineId, scheduledEpochMillis = 0, doseAmount = 1.0, createdAtEpochMillis = 0,
            )
        )
        database.doseEventDao().insert(
            com.hazia.emivita.core.data.entity.DoseEvent(
                reminderId = null, medicineId = medicineId, scheduledEpochMillis = 0, doseAmount = 1.0, createdAtEpochMillis = 0,
            )
        )

        val medicine = database.medicineDao().getById(medicineId)!!
        repository.delete(medicine)

        assertEquals(null, database.medicineDao().getById(medicineId))
        assertEquals(0, database.reminderDao().observeForMedicine(medicineId).first().size)
        assertEquals(0, database.doseEventDao().getAllOnce().count { it.medicineId == medicineId })
    }

    // ---- the add sheet's Save (§7 R114) ----

    private fun firstReminder(groupId: Long? = null) = Reminder(
        medicineId = 0, groupId = groupId, type = ReminderType.FIXED_TIME,
        configJson = encodeReminderConfig(ReminderConfig.FixedTime(8, 0, 1.0)), createdAtEpochMillis = 0,
    )

    @Test
    fun `createWithFirstReminder writes both, and arms the reminder after the commit`() = runTest {
        val profileId = database.profileDao().insert(Profile(name = "Test Profile", createdAtEpochMillis = 0))
        val id = repository.createWithFirstReminder(
            Medicine(profileId = profileId, name = "Metformin", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0),
            firstReminder(),
        )
        val reminder = database.reminderDao().observeForMedicine(id).first().single()
        assertEquals("Metformin", database.medicineDao().getById(id)?.name)
        coVerify(exactly = 1) { scheduling.onReminderChanged(reminder.id, any()) }
    }

    @Test
    fun `a reminder that cannot be written leaves no medicine behind`() = runTest {
        val profileId = database.profileDao().insert(Profile(name = "Test Profile", createdAtEpochMillis = 0))
        try {
            // A dose group that does not exist: the reminder's foreign key refuses it.
            repository.createWithFirstReminder(
                Medicine(profileId = profileId, name = "Metformin", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0),
                firstReminder(groupId = 999L),
            )
            fail("the reminder insert should have thrown")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // expected
        }
        val left = database.medicineDao().observeForProfile(profileId).first().map { it.medicine.name }
        assertTrue("a medicine without the reminder it was saved with: $left", left.isEmpty())
        coVerify(exactly = 0) { scheduling.onReminderChanged(any(), any()) }
    }
}
