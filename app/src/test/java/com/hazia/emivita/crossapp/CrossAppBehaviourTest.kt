package com.hazia.emivita.crossapp

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.DoseGroupDao
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.notification.ReminderNotificationBuilder
import com.hazia.emivita.core.notification.ReminderNotificationChannels
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import com.hazia.emivita.core.scheduling.nextExpirationAlertOccurrence
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.ZoneId

/**
 * Behaviour-derived tests from the cross-app pass (spec §7 R62, Appendix F).
 * Written fresh from the case descriptions -- every app in that pass is GPL,
 * so nothing here is a port. Each case pins where Emivita stands today on a
 * decision another app made differently, and names the Appendix F row that
 * would flip it. When one of those rows is adopted, the test that documents
 * the old behaviour is the first thing to change, and it says so.
 */
@RunWith(RobolectricTestRunner::class)
class CrossAppBehaviourTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: EmivitaDatabase
    private lateinit var repository: DoseEventRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        repository = DoseEventRepository(
            database = database,
            doseEventDao = database.doseEventDao(),
            medicineDao = database.medicineDao(),
            schedulingCoordinator = mockk<SchedulingCoordinator>(relaxed = true),
            occurrenceProjectionCache = mockk<OccurrenceProjectionCache>(relaxed = true),
            autoSyncManager = mockk<AutoSyncManager>(relaxed = true),
        )
        ReminderNotificationChannels.createAll(context)
        // postIfPermitted drops the notification without this (R58) -- the guard
        // this test would otherwise be measuring instead of the builder.
        shadowOf(context.applicationContext as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() = database.close()

    private suspend fun medicine(): Medicine {
        val profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        val id = database.medicineDao().insert(
            Medicine(profileId = profileId, name = "Ibuprofen", iconKey = "pill", stockInfo = StockInfo(quantity = 20.0, unit = "tablet"), sortOrder = 0.0, createdAtEpochMillis = 0),
        )
        return database.medicineDao().getById(id)!!
    }

    // ---- Pill Time PT-01/02, Did I Take My Meds? DT-02 -------------------------

    @Test
    fun `an as-needed dose has no ceiling -- a second one ten minutes later is accepted`() = runTest {
        // Pill Time keeps "max N per H hours" and shows the count in red when
        // reached (display only); Did I Take My Meds? blocks a second take of
        // the same slot outright. Emivita records both and judges neither.
        // Appendix F PT-01 (cost M) is the row that would change this; until
        // then the decision is: an adherence record does not refuse a fact.
        val m = medicine()
        val first = repository.logAdhocDose(m.id, 1.0, atEpochMillis = 1_000_000L)
        val second = repository.logAdhocDose(m.id, 1.0, atEpochMillis = 1_600_000L)
        assertNotNull(database.doseEventDao().getById(first))
        assertNotNull(database.doseEventDao().getById(second))
        assertEquals(18.0, database.medicineDao().getById(m.id)!!.stockInfo.quantity, 0.001)
    }

    // ---- HomeMedkit HM-F1 -------------------------------------------------------

    @Test
    fun `an expiration alert still fires for a pack with no stock left -- open decision`() {
        // HomeMedkit only sweeps medicines with stock > 0 for expiry. Emivita's
        // calculator has no stock input: an empty, expired pack keeps alerting
        // daily. Pinned as it is; Appendix F HM-F1 lists it as a finding (S).
        val cfg = ReminderConfig.ExpirationAlert(leadDays = 2, frequency = ExpirationTriggerFrequency.DAILY, hour = 9, minute = 0)
        val zone = ZoneId.of("Z")
        val next = nextExpirationAlertOccurrence(cfg, notBeforeEpochMillis = 0L, expirationEpochDay = 1L, lastNotifiedEpochDay = null, zone = zone)
        assertNotNull("the calculator cannot see stock, so it arms regardless", next)
    }

    // ---- Daily Pill DP-01/DP-02/DP-F1, Emivita §9 #22 group 1 -------------------

    @Test
    fun `a Standard reminder notification opens the app when tapped, never times out, and carries a nameless public version`() = runTest {
        // The content intent is §9 #22 group 1's fix (R61) and must stay.
        // Day-end expiry (Daily Pill DP-02) was put to the owner and declined
        // (§9 #25): a reminder stands until answered. The public version
        // (PT-06 / DP-F1) was adopted in §9 #25 -- this line read "no public
        // version yet" until then, which is what this test is for.
        val m = medicine()
        val reminderId = database.reminderDao().insert(
            Reminder(
                medicineId = m.id, type = ReminderType.FIXED_TIME,
                configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0)),
                createdAtEpochMillis = 0,
            ),
        )
        val reminder = database.reminderDao().getById(reminderId)!!
        val eventId = database.doseEventDao().insert(
            DoseEvent(reminderId = reminderId, medicineId = m.id, scheduledEpochMillis = 1_000_000L, doseAmount = 1.0, status = DoseStatus.PENDING, createdAtEpochMillis = 0),
        )
        val event = database.doseEventDao().getById(eventId)!!
        val settings = mockk<SettingsRepository>().also {
            coEvery { it.isLockedUntilTaken(any()) } returns false
            every { it.hideMedicineNames } returns flowOf(false)
        }
        val groups = mockk<DoseGroupDao>().also { coEvery { it.getById(any()) } returns null }

        ReminderNotificationBuilder(context, groups, settings).buildAndPost(event, reminder, m)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(nm).allNotifications.single()
        assertNotNull("tapping the body must open the app (R61)", posted.contentIntent)
        assertEquals("a reminder stands until answered (Appendix F DP-02 would change this)", 0L, posted.timeoutAfter)
        val public = posted.publicVersion
        assertNotNull("a redacted lock-screen copy (Appendix F PT-06, §9 #25)", public)
        assertEquals("the public copy names no medicine", "Medication reminder", public.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertNotNull("a swipe reaches the receiver (§9 #25 swipe setting)", posted.deleteIntent)
    }
}
