package com.hazia.emivita.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.config.LowStockTriggerMode
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
 * The *Al bisogno* switch (§7 R116) travels in the backup and restores as set;
 * a file written before the field existed decodes it as MIGRATION_11_12 sets
 * it, so restoring an old backup moves nothing off *Al bisogno*.
 */
@RunWith(RobolectricTestRunner::class)
class BackupAsNeededTest {
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

    private fun med(id: Long, asNeeded: Boolean = false) =
        Medicine(id = id, profileId = 1, name = "m$id", iconKey = "pill", sortOrder = id.toDouble(), asNeeded = asNeeded, createdAtEpochMillis = 0)

    private fun taken(id: Long, medicineId: Long) =
        DoseEvent(id = id, medicineId = medicineId, scheduledEpochMillis = id * 1000, doseAmount = 1.0, status = DoseStatus.TAKEN, resolvedAtEpochMillis = id * 1000, createdAtEpochMillis = 0)

    /** 1 logged, no reminder; 2 never logged; 3 a dosing reminder, logged; 4 a stock alert only, logged. */
    private fun snapshot(asNeeded: Set<Long> = emptySet()) = BackupData(
        exportedAtEpochMillis = 0,
        profiles = listOf(Profile(id = 1, name = "Me", createdAtEpochMillis = 0)),
        medicines = (1L..4L).map { med(it, it in asNeeded) },
        tags = emptyList(), medicineTagCrossRefs = emptyList(), doseGroups = emptyList(),
        reminders = listOf(
            Reminder(id = 1, medicineId = 3, type = ReminderType.FIXED_TIME, configJson = encodeReminderConfig(ReminderConfig.FixedTime(8, 0, 1.0)), createdAtEpochMillis = 0),
            Reminder(id = 2, medicineId = 4, type = ReminderType.LOW_STOCK_ALERT, configJson = encodeReminderConfig(ReminderConfig.LowStockAlert(5.0, LowStockTriggerMode.ONCE_WHEN_BELOW, 9, 0)), createdAtEpochMillis = 0),
        ),
        doseEvents = listOf(taken(1, 1), taken(2, 3), taken(3, 4)),
    )

    private fun decoded(json: String) = (decodeBackup(json) as BackupDecodeResult.Ok).data

    /** The same file as a build before §7 R116 wrote it: no `asNeeded` key on any medicine. */
    private fun withoutTheKey(json: String): String {
        val root = BackupJson.parseToJsonElement(json).jsonObject
        val medicines = JsonArray(root["medicines"]!!.jsonArray.map { JsonObject(it.jsonObject - "asNeeded") })
        return JsonObject(root + ("medicines" to medicines)).toString()
    }

    @Test
    fun `the switch round-trips through the file and the restore`() = runBlocking {
        val json = BackupJson.encodeToString(BackupData.serializer(), snapshot(asNeeded = setOf(2)))
        val data = decoded(json)
        assertEquals(listOf(false, true, false, false), data.medicines.map { it.asNeeded })
        backup.restoreSnapshot(data)
        assertEquals(true, database.medicineDao().getById(2)!!.asNeeded)
        assertEquals(listOf(2L), backup.exportSnapshot(0).medicines.filter { it.asNeeded }.map { it.id })
    }

    @Test
    fun `a file that says off keeps it off, logged or not`() {
        val json = BackupJson.encodeToString(BackupData.serializer(), snapshot())
        assertEquals(listOf(false, false, false, false), decoded(json).medicines.map { it.asNeeded })
    }

    @Test
    fun `a file from before the switch gets it as the migration gives it`() {
        val json = withoutTheKey(BackupJson.encodeToString(BackupData.serializer(), snapshot(asNeeded = setOf(2))))
        // logged with no reminder: on; never logged: off; a dosing reminder: off; alert-only and logged: on
        assertEquals(listOf(true, false, false, true), decoded(json).medicines.map { it.asNeeded })
    }
}
