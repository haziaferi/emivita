package com.hazia.emivita.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A one-off dose (§7 R111, C-15) travels in the backup and restores as it was,
 * and the format moves to 3 because a build at format 2, whose `medicineId`
 * cannot be null, would fail on the file instead of refusing it by name.
 */
@RunWith(RobolectricTestRunner::class)
class BackupOneOffDoseTest {
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

    private val oneOff = DoseEvent(
        id = 3, medicineId = null, customName = "Paracetamol", customProfileId = 1, scheduledEpochMillis = 5000,
        doseAmount = 2.0, status = DoseStatus.TAKEN, resolvedAtEpochMillis = 5000, notes = "headache", createdAtEpochMillis = 5001,
    )

    private fun snapshot() = BackupData(
        exportedAtEpochMillis = 0,
        profiles = listOf(Profile(id = 1, name = "Me", createdAtEpochMillis = 0)),
        medicines = listOf(Medicine(id = 1, profileId = 1, name = "Metformin", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0)),
        tags = emptyList(), medicineTagCrossRefs = emptyList(), doseGroups = emptyList(), reminders = emptyList(),
        doseEvents = listOf(
            DoseEvent(id = 2, medicineId = 1, scheduledEpochMillis = 4000, doseAmount = 1.0, status = DoseStatus.TAKEN, createdAtEpochMillis = 4000),
            oneOff,
        ),
    )

    @Test
    fun `a one-off dose round-trips through the file and the restore`() = runBlocking {
        val json = BackupJson.encodeToString(BackupData.serializer(), snapshot())
        val decoded = (decodeBackup(json) as BackupDecodeResult.Ok).data
        assertEquals(snapshot().doseEvents, decoded.doseEvents)

        backup.restoreSnapshot(decoded)
        assertEquals(oneOff, database.doseEventDao().getById(3))
        assertEquals(snapshot().doseEvents, backup.exportSnapshot(0).doseEvents.sortedBy { it.id })
    }

    /** `DoseEvent` as a format-2 build declares it: `medicineId` cannot be null. */
    @Serializable
    private data class DoseEventFormat2(
        val id: Long = 0, val reminderId: Long? = null, val medicineId: Long, val scheduledEpochMillis: Long,
        val doseAmount: Double, val status: DoseStatus = DoseStatus.PENDING, val resolvedAtEpochMillis: Long? = null,
        val stockBefore: Double = -1.0, val stockAfter: Double = -1.0, val repeatCount: Int = 0, val notes: String? = null,
        val createdAtEpochMillis: Long,
    )

    @Test
    fun `a format-2 build cannot decode a one-off dose, so the file says format 3 and that build refuses it`() {
        val json = BackupJson.encodeToString(DoseEvent.serializer(), oneOff)
        assertThrows(SerializationException::class.java) { BackupJson.decodeFromString(DoseEventFormat2.serializer(), json) }

        val file = BackupJson.encodeToString(BackupData.serializer(), snapshot())
        val written = BackupJson.parseToJsonElement(file).jsonObject["schemaVersion"]!!.jsonPrimitive.int
        assertEquals(3, written)
        assertTrue("a build at format 2 refuses anything newer than 2 by name (decodeBackup's rule)", written > 2)
    }
}
