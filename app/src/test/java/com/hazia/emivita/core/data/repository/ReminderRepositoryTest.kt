package com.hazia.emivita.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the LinkedFollowing orphan decision recorded in spec §7 R25: the
 * `ON DELETE SET_NULL` FK detaches a deleted parent's children, which then
 * never fire (`SchedulingEngine.nextOccurrenceFor` returns null for a null
 * parent, pinned in `SchedulingEngineTest`), so `delete()` switches each one
 * off to make that visible.
 *
 * Real in-memory Room rather than mocked DAOs, as in [MedicineRepositoryTest]:
 * `withTransaction` is an inline extension MockK can't intercept, and the
 * SET_NULL behaviour under test is SQLite's, not the repository's.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderRepositoryTest {
    private lateinit var database: EmivitaDatabase
    private lateinit var repository: ReminderRepository
    private lateinit var schedulingCoordinator: SchedulingCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        schedulingCoordinator = mockk<SchedulingCoordinator>(relaxed = true)
        repository = ReminderRepository(
            database = database,
            reminderDao = database.reminderDao(),
            schedulingCoordinator = schedulingCoordinator,
            autoSyncManager = mockk<AutoSyncManager>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedMedicine(): Long {
        val profileId = database.profileDao().insert(Profile(name = "Test Profile", createdAtEpochMillis = 0))
        return database.medicineDao().insert(
            Medicine(profileId = profileId, name = "Ibuprofen", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0)
        )
    }

    private suspend fun seedParent(medicineId: Long): Long = database.reminderDao().insert(
        Reminder(
            medicineId = medicineId,
            type = ReminderType.FIXED_TIME,
            configJson = encodeReminderConfig(
                ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0, repeatPattern = RepeatPattern.Daily),
            ),
            createdAtEpochMillis = 0,
        )
    )

    private suspend fun seedChild(medicineId: Long, parentReminderId: Long): Long = database.reminderDao().insert(
        Reminder(
            medicineId = medicineId,
            parentReminderId = parentReminderId,
            type = ReminderType.LINKED_FOLLOWING,
            configJson = encodeReminderConfig(
                ReminderConfig.LinkedFollowing(
                    delayMinutes = 30,
                    dose = 1.0,
                    startBehavior = IntervalStartBehavior.ON_REMINDED,
                ),
            ),
            createdAtEpochMillis = 0,
        )
    )

    @Test
    fun `deleting a parent switches its linked children off`() = runTest {
        val medicineId = seedMedicine()
        val parentId = seedParent(medicineId)
        val childId = seedChild(medicineId, parentId)

        repository.delete(database.reminderDao().getById(parentId)!!)

        val child = database.reminderDao().getById(childId)
        assertNotNull("the child row must survive -- the FK is SET_NULL, not CASCADE", child)
        assertEquals(
            "a detached child left enabled would sit in the list and never fire",
            false,
            child!!.enabled,
        )
    }

    @Test
    fun `deleting a parent detaches its children rather than deleting them`() = runTest {
        val medicineId = seedMedicine()
        val parentId = seedParent(medicineId)
        val childId = seedChild(medicineId, parentId)

        repository.delete(database.reminderDao().getById(parentId)!!)

        assertNull(database.reminderDao().getById(parentId))
        assertNull(
            "SQLite's ON DELETE SET_NULL is what clears the link",
            database.reminderDao().getById(childId)!!.parentReminderId,
        )
    }

    @Test
    fun `every disabled child is handed to the scheduling coordinator`() = runTest {
        // The row still exists, so it routes through onReminderChanged, whose
        // scheduleOrCancel "no next occurrence" branch cancels the alarm --
        // not onReminderRemoved, which is for a row that is gone.
        val medicineId = seedMedicine()
        val parentId = seedParent(medicineId)
        val firstChildId = seedChild(medicineId, parentId)
        val secondChildId = seedChild(medicineId, parentId)

        repository.delete(database.reminderDao().getById(parentId)!!)

        coVerify(exactly = 1) { schedulingCoordinator.onReminderRemoved(parentId) }
        coVerify(exactly = 1) { schedulingCoordinator.onReminderChanged(firstChildId, any()) }
        coVerify(exactly = 1) { schedulingCoordinator.onReminderChanged(secondChildId, any()) }
    }

    @Test
    fun `deleting a childless reminder still deletes it`() = runTest {
        val medicineId = seedMedicine()
        val reminderId = seedParent(medicineId)

        repository.delete(database.reminderDao().getById(reminderId)!!)

        assertNull(database.reminderDao().getById(reminderId))
        coVerify(exactly = 1) { schedulingCoordinator.onReminderRemoved(reminderId) }
    }

    @Test
    fun `the disable reaches one level only, leaving a grandchild alone`() = runTest {
        // Matches getChildrenOf's documented "one level down only" scope and
        // setEnabled's existing semantics: disabling a chain's head has never
        // disabled the rest of the chain. The grandchild is not orphaned --
        // its own parent still exists, merely switched off.
        val medicineId = seedMedicine()
        val parentId = seedParent(medicineId)
        val childId = seedChild(medicineId, parentId)
        val grandchildId = seedChild(medicineId, childId)

        repository.delete(database.reminderDao().getById(parentId)!!)

        val grandchild = database.reminderDao().getById(grandchildId)!!
        assertTrue("the grandchild keeps its own enabled state", grandchild.enabled)
        assertEquals("and keeps following the child", childId, grandchild.parentReminderId)
    }
}
