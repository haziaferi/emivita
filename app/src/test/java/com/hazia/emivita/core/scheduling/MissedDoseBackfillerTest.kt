package com.hazia.emivita.core.scheduling

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId

/** The Missed back-fill (spec §9 #26; Appendix F DS-01). Real engine in a
 *  fixed zone, real in-memory Room, the settings stamp mocked. */
@RunWith(RobolectricTestRunner::class)
class MissedDoseBackfillerTest {
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private fun at(iso: String) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
    private lateinit var database: EmivitaDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var doses: DoseEventRepository
    private var profileId = 0L
    private var medicineId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        settings = mockk<SettingsRepository>(relaxed = true).also { every { it.weekendModeEnabled } returns flowOf(false) }
        doses = DoseEventRepository(
            database = database, doseEventDao = database.doseEventDao(), medicineDao = database.medicineDao(),
            schedulingCoordinator = mockk(relaxed = true), occurrenceProjectionCache = mockk(relaxed = true),
            autoSyncManager = mockk<AutoSyncManager>(relaxed = true), reminderDao = database.reminderDao(),
        )
        profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        medicineId = database.medicineDao().insert(Medicine(profileId = profileId, name = "M", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0))
    }

    @After
    fun tearDown() = database.close()

    private fun backfiller(now: Long) = MissedDoseBackfiller(
        database.reminderDao(),
        SchedulingEngine(database.doseEventDao(), database.medicineDao(), settings, fixedZone = zone, nowEpochMillis = { now }),
        doses, settings,
    )

    private suspend fun daily(createdAt: Long = 0L) = database.reminderDao().insert(
        Reminder(medicineId = medicineId, type = ReminderType.FIXED_TIME, createdAtEpochMillis = createdAt,
            configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0))),
    )

    @Test
    fun `slots that passed since the app was last seen become pending rows, without doubling one the alarm reached`() = runTest {
        val r = daily()
        val lastSeen = at("2026-09-09T10:00"); val now = at("2026-09-12T10:00")
        every { settings.appLastSeenEpochMillis } returns flowOf(lastSeen)
        // the alarm did fire the 10th's dose before the phone died
        doses.materializeIfAbsent(r, medicineId, at("2026-09-10T08:00"), 1.0)

        val inserted = backfiller(now).backfill(now)

        assertEquals("11th and 12th; the 10th was already there", 2, inserted)
        val rows = doses.observeInRange(profileId, 0L, now + 1).first().map { it.doseEvent }
        assertEquals(listOf(at("2026-09-10T08:00"), at("2026-09-11T08:00"), at("2026-09-12T08:00")), rows.map { it.scheduledEpochMillis })
        assertEquals(setOf(DoseStatus.PENDING), rows.map { it.status }.toSet())
        coVerify { settings.setAppLastSeenEpochMillis(now) }
    }

    @Test
    fun `an ON_RESOLVED chain gets no invented slots, and nothing is added inside the last minute`() = runTest {
        database.reminderDao().insert(
            Reminder(medicineId = medicineId, type = ReminderType.REPEATING_INTERVAL, createdAtEpochMillis = 0,
                configJson = encodeReminderConfig(ReminderConfig.RepeatingInterval(intervalMinutes = 60, dose = 1.0, startBehavior = IntervalStartBehavior.ON_RESOLVED, anchorEpochMillis = 0L))),
        )
        val r = daily()
        every { settings.appLastSeenEpochMillis } returns flowOf(at("2026-09-12T07:59"))
        // now is 08:00:30 -- the 08:00 slot is the alarm path's, not ours
        assertEquals(0, backfiller(at("2026-09-12T08:00:30")).backfill(at("2026-09-12T08:00:30")))
        assertEquals(0, database.doseEventDao().getPendingScheduledBetween(0L, Long.MAX_VALUE).size)
        // an hour later it is a missed slot
        assertEquals(1, backfiller(at("2026-09-12T09:00")).backfill(at("2026-09-12T09:00")))
        assertEquals(r, database.doseEventDao().getPendingScheduledBetween(0L, Long.MAX_VALUE).single().reminderId)
    }

    @Test
    fun `with no stamp the window is capped at sixty days and starts at the reminder's creation`() = runTest {
        every { settings.appLastSeenEpochMillis } returns flowOf(null)
        val now = at("2026-09-12T10:00")
        daily(createdAt = at("2026-09-10T12:00"))
        assertEquals("only the 11th and the 12th lie after creation", 2, backfiller(now).backfill(now))
    }
}
