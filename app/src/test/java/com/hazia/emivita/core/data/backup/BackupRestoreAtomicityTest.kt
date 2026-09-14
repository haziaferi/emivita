package com.hazia.emivita.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The third half of E-17's contract (spec §9 #33): a snapshot that decodes
 * but cannot be written -- here a reminder whose medicine is not in the
 * file, which the foreign key rejects -- leaves the database exactly as it
 * was. `restoreSnapshot` deletes everything and re-inserts inside one
 * `withTransaction`, so the rejection rolls the delete back too; this is
 * where that is checked rather than believed.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreAtomicityTest {

    private lateinit var database: EmivitaDatabase
    private lateinit var backup: BackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        backup = BackupRepository(
            database = database, profileDao = database.profileDao(), medicineDao = database.medicineDao(), tagDao = database.tagDao(),
            doseGroupDao = database.doseGroupDao(), reminderDao = database.reminderDao(), doseEventDao = database.doseEventDao(),
            medicineNoteDao = database.medicineNoteDao(), schedulingCoordinator = mockk(relaxed = true), occurrenceProjectionCache = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a snapshot the database rejects leaves what was there untouched`() = runBlocking {
        val profileId = database.profileDao().insert(Profile(name = "Me", createdAtEpochMillis = 0))
        val medicineId = database.medicineDao().insert(Medicine(profileId = profileId, name = "Kept", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0))

        val orphanReminder = Reminder(id = 1, medicineId = 999, type = ReminderType.FIXED_TIME, configJson = encodeReminderConfig(ReminderConfig.FixedTime(8, 0, 1.0)), createdAtEpochMillis = 0)
        val bad = BackupData(
            exportedAtEpochMillis = 0, profiles = listOf(Profile(id = 7, name = "Intruder", createdAtEpochMillis = 0)), medicines = emptyList(),
            tags = emptyList(), medicineTagCrossRefs = emptyList(), doseGroups = emptyList(), reminders = listOf(orphanReminder), doseEvents = emptyList(),
        )

        val thrown = runCatching { backup.restoreSnapshot(bad) }.exceptionOrNull()
        assertTrue("the foreign key refused the orphan: $thrown", thrown != null)

        assertEquals(listOf("Me"), database.profileDao().getAllOnce().map { it.name })
        assertEquals("Kept", database.medicineDao().getById(medicineId)!!.name)
        assertEquals(emptyList<Reminder>(), database.reminderDao().getAllEnabled())
    }
}
