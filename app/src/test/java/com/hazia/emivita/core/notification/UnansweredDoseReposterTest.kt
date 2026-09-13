package com.hazia.emivita.core.notification

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.hazia.emivita.core.data.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Re-posting unanswered doses after a reboot or a force-stop (spec §9 #25;
 *  Appendix E B-20). ShadowNotificationManager is what lets the "already
 *  showing" rule be checked for real. */
@RunWith(RobolectricTestRunner::class)
class UnansweredDoseReposterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: EmivitaDatabase
    private lateinit var reposter: UnansweredDoseReposter
    private val now = 1_000_000_000L
    private val hour = 60L * 60 * 1000
    private lateinit var nm: NotificationManager

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        ReminderNotificationChannels.createAll(context)
        shadowOf(context.applicationContext as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        NotificationRateLimit.reset()
        val settings = mockk<SettingsRepository>().also {
            coEvery { it.isLockedUntilTaken(any()) } returns false
            every { it.hideMedicineNames } returns flowOf(false)
            every { it.snoozeAskEachTime } returns flowOf(false)
        }
        val groups = mockk<DoseGroupDao>().also { coEvery { it.getById(any()) } returns null }
        val builder = ReminderNotificationBuilder(context, groups, settings)
        reposter = UnansweredDoseReposter(context, database.doseEventDao(), database.reminderDao(), database.medicineDao(), builder)
        nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seed(enabled: Boolean = true): Pair<Long, Long> {
        val profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        val medicineId = database.medicineDao().insert(Medicine(profileId = profileId, name = "M", iconKey = "pill", stockInfo = StockInfo(), sortOrder = 0.0, createdAtEpochMillis = 0))
        val reminderId = database.reminderDao().insert(
            Reminder(medicineId = medicineId, type = ReminderType.FIXED_TIME, enabled = enabled,
                configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0)), createdAtEpochMillis = 0),
        )
        return medicineId to reminderId
    }

    private suspend fun pending(medicineId: Long, reminderId: Long?, scheduled: Long, status: DoseStatus = DoseStatus.PENDING): Long =
        database.doseEventDao().insert(DoseEvent(reminderId = reminderId, medicineId = medicineId, scheduledEpochMillis = scheduled, doseAmount = 1.0, status = status, createdAtEpochMillis = 0))

    @Test
    fun `a dose that fired an hour ago and is still unanswered gets its notification back`() = runTest {
        val (m, r) = seed()
        val id = pending(m, r, now - hour)
        assertEquals(1, reposter.repost(now))
        assertEquals(listOf(id.toInt()), shadowOf(nm).activeNotifications.map { it.id })
    }

    @Test
    fun `a dose whose notification is still showing is not re-posted, so a launch cannot re-chime it`() = runTest {
        val (m, r) = seed()
        pending(m, r, now - hour)
        assertEquals(1, reposter.repost(now))
        assertEquals(0, reposter.repost(now))
        assertEquals(1, shadowOf(nm).activeNotifications.size)
    }

    @Test
    fun `older than a day, answered, ad-hoc, or on a paused reminder -- none of these come back`() = runTest {
        val (m, r) = seed()
        pending(m, r, now - 30 * hour)                        // too old: Missed on Today already
        pending(m, r, now - 2 * hour, DoseStatus.TAKEN)       // answered
        pending(m, null, now - hour)                           // ad-hoc: no reminder, no notification ever
        val (m2, r2) = seed(enabled = false)
        pending(m2, r2, now - hour)                            // paused reminder
        assertEquals(0, reposter.repost(now))
        assertEquals(0, shadowOf(nm).activeNotifications.size)
    }
}
