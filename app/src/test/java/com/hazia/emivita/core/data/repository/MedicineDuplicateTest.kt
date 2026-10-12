package com.hazia.emivita.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
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

/** Duplicate and pause-all (spec §9 #24). In-memory Room for the same reason
 *  the other repository tests give. */
@RunWith(RobolectricTestRunner::class)
class MedicineDuplicateTest {
    private lateinit var database: EmivitaDatabase
    private lateinit var medicines: MedicineRepository
    private lateinit var reminders: ReminderRepository
    private lateinit var coordinator: SchedulingCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        coordinator = mockk(relaxed = true)
        reminders = ReminderRepository(database, database.reminderDao(), coordinator, mockk<AutoSyncManager>(relaxed = true))
        medicines = MedicineRepository(
            database = database, medicineDao = database.medicineDao(), tagDao = database.tagDao(),
            doseEventDao = database.doseEventDao(), reminderDao = database.reminderDao(),
            medicineNoteDao = database.medicineNoteDao(),
            schedulingCoordinator = coordinator, occurrenceProjectionCache = mockk(relaxed = true),
            autoSyncManager = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seed(): Long {
        val profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        val id = database.medicineDao().insert(
            Medicine(
                profileId = profileId, name = "Sertraline", iconKey = "pill", barcodeValue = "8001234567890",
                stockInfo = StockInfo(quantity = 12.0, unit = "tablet"), indication = "Mood", sortOrder = 1.0, createdAtEpochMillis = 0,
            ),
        )
        medicines.addTag(id, "Morning")
        return id
    }

    private suspend fun fixed(medicineId: Long) = database.reminderDao().insert(
        Reminder(medicineId = medicineId, type = ReminderType.FIXED_TIME,
            configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0)), createdAtEpochMillis = 0),
    )

    @Test
    fun `duplicate without reminders copies fields and tags, resets barcode and stock, and takes the suffix`() = runTest {
        val id = seed()
        val copyId = medicines.duplicate(id, "(copy)", withReminders = false, reminders)!!
        val copy = database.medicineDao().getById(copyId)!!
        assertEquals("Sertraline (copy)", copy.name)
        assertEquals("Mood", copy.indication)
        assertNull("a barcode identifies one pack", copy.barcodeValue)
        assertNull("a copy starts untracked", copy.stockInfo.unit)
        assertEquals(listOf("Morning"), database.tagDao().observeTagsForMedicine(copyId).first().map { it.name })
        assertTrue(database.reminderDao().observeForMedicine(copyId).first().isEmpty())
    }

    @Test
    fun `duplicate with reminders re-creates each one with its own alarm and remaps a linked child's parent`() = runTest {
        val id = seed()
        val parent = fixed(id)
        database.reminderDao().insert(
            Reminder(medicineId = id, parentReminderId = parent, type = ReminderType.LINKED_FOLLOWING,
                configJson = encodeReminderConfig(ReminderConfig.LinkedFollowing(delayMinutes = 30, dose = 1.0, startBehavior = IntervalStartBehavior.ON_RESOLVED)),
                createdAtEpochMillis = 0),
        )

        val copyId = medicines.duplicate(id, "(copy)", withReminders = true, reminders)!!

        val copied = database.reminderDao().observeForMedicine(copyId).first()
        assertEquals(2, copied.size)
        val copiedParent = copied.single { it.type == ReminderType.FIXED_TIME }
        val copiedChild = copied.single { it.type == ReminderType.LINKED_FOLLOWING }
        assertEquals("the child follows the COPIED parent, not the original's", copiedParent.id, copiedChild.parentReminderId)
        // every re-created reminder was handed to the coordinator for an alarm
        coVerify(exactly = 1) { coordinator.onReminderChanged(copiedParent.id, any()) }
        coVerify(exactly = 1) { coordinator.onReminderChanged(copiedChild.id, any()) }
        // and the original is untouched
        assertEquals(2, database.reminderDao().observeForMedicine(id).first().size)
    }

    @Test
    fun `pause all switches dosing reminders off and leaves the alerts alone`() = runTest {
        val id = seed()
        val dose = fixed(id)
        val alert = database.reminderDao().insert(
            Reminder(medicineId = id, type = ReminderType.EXPIRATION_ALERT,
                configJson = encodeReminderConfig(ReminderConfig.ExpirationAlert(leadDays = 7, frequency = com.hazia.emivita.core.data.config.ExpirationTriggerFrequency.ONCE)),
                createdAtEpochMillis = 0),
        )

        reminders.setAllDosingEnabled(id, enabled = false)

        assertFalse(database.reminderDao().getById(dose)!!.enabled)
        assertTrue("an expiry alert is not a dose; pausing doses does not stop the pack expiring", database.reminderDao().getById(alert)!!.enabled)

        reminders.setAllDosingEnabled(id, enabled = true)
        assertTrue(database.reminderDao().getById(dose)!!.enabled)
    }
}
