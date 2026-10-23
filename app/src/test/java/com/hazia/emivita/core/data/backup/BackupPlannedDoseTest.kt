package com.hazia.emivita.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `planned` and `missedAfterEpochMillis` (§7 R118, R119) travel in the backup
 * and restore as written, a deleted reminder's dose (no reminderId, planned)
 * included; a file written before the fields existed derives `planned` from
 * its own reminderId, as MIGRATION_12_13 does, and has no deadline.
 */
@RunWith(RobolectricTestRunner::class)
class BackupPlannedDoseTest {
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

    private fun dose(id: Long, reminderId: Long?, planned: Boolean, status: DoseStatus = DoseStatus.TAKEN, missedAfter: Long? = null) = DoseEvent(
        id = id, reminderId = reminderId, medicineId = 1, scheduledEpochMillis = id * 1000, doseAmount = 1.0, status = status,
        resolvedAtEpochMillis = if (status == DoseStatus.PENDING) null else id * 1000, createdAtEpochMillis = 0,
        planned = planned, missedAfterEpochMillis = missedAfter,
    )

    /** 1 of a live reminder; 2 of a deleted one (planned, no reminderId); 3 as needed; 4 pending, due until 9000. */
    private fun snapshot() = BackupData(
        exportedAtEpochMillis = 0,
        profiles = listOf(Profile(id = 1, name = "Me", createdAtEpochMillis = 0)),
        medicines = listOf(Medicine(id = 1, profileId = 1, name = "m", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0)),
        tags = emptyList(), medicineTagCrossRefs = emptyList(), doseGroups = emptyList(),
        reminders = listOf(Reminder(id = 1, medicineId = 1, type = ReminderType.FIXED_TIME, configJson = encodeReminderConfig(ReminderConfig.FixedTime(8, 0, 1.0)), createdAtEpochMillis = 0)),
        doseEvents = listOf(
            dose(1, 1, planned = true),
            dose(2, null, planned = true),
            dose(3, null, planned = false),
            dose(4, 1, planned = true, status = DoseStatus.PENDING, missedAfter = 9000),
        ),
    )

    private fun decoded(json: String) = (decodeBackup(json) as BackupDecodeResult.Ok).data

    /** The same file as a build before §7 R118 wrote it: neither key on any dose. */
    private fun withoutTheKeys(json: String): String {
        val root = BackupJson.parseToJsonElement(json).jsonObject
        val doses = JsonArray(root["doseEvents"]!!.jsonArray.map { JsonObject(it.jsonObject - "planned" - "missedAfterEpochMillis") })
        return JsonObject(root + ("doseEvents" to doses)).toString()
    }

    @Test
    fun `planned and the deadline round-trip through the file and the restore`() = runBlocking {
        val json = BackupJson.encodeToString(BackupData.serializer(), snapshot())
        val data = decoded(json)
        assertEquals(listOf(true, true, false, true), data.doseEvents.map { it.planned })
        backup.restoreSnapshot(data)
        assertEquals(true, database.doseEventDao().getById(2)!!.planned)
        assertEquals(9000L, database.doseEventDao().getById(4)!!.missedAfterEpochMillis)
        assertEquals(snapshot().doseEvents, backup.exportSnapshot(0).doseEvents.sortedBy { it.id })
    }

    @Test
    fun `a file from before the fields derives planned from its reminderId and has no deadline`() {
        val json = withoutTheKeys(BackupJson.encodeToString(BackupData.serializer(), snapshot()))
        val doses = decoded(json).doseEvents
        assertEquals(listOf(true, false, false, true), doses.map { it.planned })
        assertEquals(listOf<Long?>(null, null, null, null), doses.map { it.missedAfterEpochMillis })
    }
}
