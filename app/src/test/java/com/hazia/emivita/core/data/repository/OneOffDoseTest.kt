package com.hazia.emivita.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.displayName
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.entity.hasOneSubject
import com.hazia.emivita.core.data.export.HistoryExporter
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/**
 * C-15 V1, the owner's pick (§7 R111): a dose of a medicine that is not on the
 * list, logged once under its name. No medicine row is made, it reads back by
 * its own profile and name, the CSV names it, it cannot be reopened into a
 * missed dose, and it goes with its profile.
 */
@RunWith(RobolectricTestRunner::class)
class OneOffDoseTest {
    private lateinit var database: EmivitaDatabase
    private lateinit var doses: DoseEventRepository
    private var profileId = 0L
    private var otherProfileId = 0L
    private var medicineId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        doses = DoseEventRepository(
            database = database, doseEventDao = database.doseEventDao(), medicineDao = database.medicineDao(),
            reminderDao = database.reminderDao(), schedulingCoordinator = mockk<SchedulingCoordinator>(relaxed = true),
            occurrenceProjectionCache = mockk<OccurrenceProjectionCache>(relaxed = true), autoSyncManager = mockk<AutoSyncManager>(relaxed = true),
        )
        profileId = database.profileDao().insert(Profile(name = "Me", createdAtEpochMillis = 0))
        otherProfileId = database.profileDao().insert(Profile(name = "Other", createdAtEpochMillis = 0))
        medicineId = database.medicineDao().insert(
            Medicine(profileId = profileId, name = "Metformin", iconKey = "pill", stockInfo = StockInfo(quantity = 10.0, unit = "tablet"), sortOrder = 0.0, createdAtEpochMillis = 0),
        )
    }

    @After
    fun tearDown() = database.close()

    private val at = 1_700_000_000_000L

    private suspend fun rows(profile: Long = profileId) = doses.getInRangeOnce(profile, 0L, Long.MAX_VALUE, null)

    @Test
    fun `logging a one-off records Taken under its name with no medicine, and adds no medicine`() = runBlocking {
        val before = database.medicineDao().getAllOnce()
        val id = doses.logOneOffDose(profileId, "  Paracetamol ", 2.0, at)

        val event = database.doseEventDao().getById(id)!!
        assertEquals(DoseStatus.TAKEN, event.status)
        assertNull("no medicine behind it", event.medicineId)
        assertNull(event.reminderId)
        assertEquals("Paracetamol", event.customName)
        assertEquals(profileId, event.customProfileId)
        assertEquals(2.0, event.doseAmount, 0.0)
        assertEquals(at, event.resolvedAtEpochMillis)
        assertEquals("nothing new in Medicines", before, database.medicineDao().getAllOnce())
        assertEquals("the listed medicine's stock is untouched", 10.0, database.medicineDao().getById(medicineId)!!.stockInfo.quantity, 0.0)
    }

    @Test
    fun `a dose names exactly one subject, a medicine or a one-off name with its profile`() {
        fun dose(medicine: Long?, name: String?, profile: Long?) =
            DoseEvent(medicineId = medicine, customName = name, customProfileId = profile, scheduledEpochMillis = 0, doseAmount = 1.0, createdAtEpochMillis = 0)
        assertTrue(dose(1, null, null).hasOneSubject)
        assertTrue(dose(null, "Paracetamol", 1).hasOneSubject)
        assertFalse("both", dose(1, "Paracetamol", 1).hasOneSubject)
        assertFalse("a medicine and a stray profile", dose(1, null, 1).hasOneSubject)
        assertFalse("neither", dose(null, null, null).hasOneSubject)
        assertFalse("a name with no profile", dose(null, "Paracetamol", null).hasOneSubject)
        assertFalse("a blank name", dose(null, " ", 1).hasOneSubject)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { doses.logOneOffDose(profileId, "   ", 1.0, at) } }
    }

    @Test
    fun `it reads back in its own profile by its name, beside a listed medicine's dose`() = runBlocking {
        doses.logAdhocDose(medicineId, 1.0, at - 1)
        doses.logOneOffDose(profileId, "Paracetamol", 1.0, at)

        val mine = rows()
        assertEquals(listOf("Paracetamol", "Metformin"), mine.map { it.displayName })
        assertNull(mine.first().medicine)
        assertEquals(medicineId, mine.last().medicine!!.id)
        assertEquals(mine, doses.observeInRange(profileId, 0L, Long.MAX_VALUE).first().sortedByDescending { it.doseEvent.scheduledEpochMillis })
        assertEquals("another profile does not see it", emptyList<String>(), rows(otherProfileId).map { it.displayName })
    }

    @Test
    fun `the CSV's Medicine column carries the one-off's name`() = runBlocking {
        doses.logOneOffDose(profileId, "Paracetamol, 500", 1.0, at)
        val csv = HistoryExporter.buildCsv(rows(), ZoneOffset.UTC)
        val line = csv.lines()[1]
        assertTrue(line, line.contains(",\"Paracetamol, 500\",Taken,1,,"))
    }

    @Test
    fun `a one-off cannot be reopened into a missed dose, and soft-deletes out of the reads`() = runBlocking {
        val id = doses.logOneOffDose(profileId, "Paracetamol", 1.0, at)
        assertNull(doses.reopen(id))
        assertEquals(DoseStatus.TAKEN, database.doseEventDao().getById(id)!!.status)

        doses.softDelete(id)
        assertEquals(DoseStatus.DELETED, database.doseEventDao().getById(id)!!.status)
        assertEquals(emptyList<String>(), rows().map { it.displayName })
    }

    @Test
    fun `deleting its profile takes the one-off with it, as the profile's medicines go`() = runBlocking {
        val id = doses.logOneOffDose(otherProfileId, "Paracetamol", 1.0, at)
        database.profileDao().delete(database.profileDao().getAllOnce().single { it.id == otherProfileId })
        assertNull(database.doseEventDao().getById(id))
    }

    @Test
    fun `a scheduled dose still materialises with its medicine`() = runBlocking {
        val reminderId = database.reminderDao().insert(
            Reminder(medicineId = medicineId, type = ReminderType.FIXED_TIME, configJson = encodeReminderConfig(ReminderConfig.FixedTime(8, 0, 1.0)), createdAtEpochMillis = 0),
        )
        val id = doses.materializeIfAbsent(reminderId, medicineId, at, 1.0)!!
        assertEquals(medicineId, database.doseEventDao().getById(id)!!.medicineId)
        assertNull(database.doseEventDao().getById(id)!!.customName)
    }
}
