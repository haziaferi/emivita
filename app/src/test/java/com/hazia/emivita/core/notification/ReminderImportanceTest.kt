package com.hazia.emivita.core.notification

import android.app.Notification
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
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.data.settings.SwipeAction
import com.hazia.emivita.ui.theme.DEFAULT_PALETTE
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

/** A reminder's own tier is the one its notification posts on (§7 R127, B-38): it is read before the medicine's. */
@RunWith(RobolectricTestRunner::class)
class ReminderImportanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: EmivitaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        ReminderNotificationChannels.createAll(context)
        shadowOf(context.applicationContext as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        NotificationRateLimit.reset()
    }

    @After
    fun tearDown() = database.close()

    /** A Standard medicine's 08:00 reminder at [importance], fired; the posted notification. */
    private suspend fun post(importance: NotificationPriority?): Notification {
        val profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        val medicineId = database.medicineDao().insert(
            Medicine(profileId = profileId, name = "Metformina", iconKey = "pill", notificationPriority = NotificationPriority.STANDARD, sortOrder = 0.0, createdAtEpochMillis = 0),
        )
        val reminderId = database.reminderDao().insert(
            Reminder(
                medicineId = medicineId, type = ReminderType.FIXED_TIME, importance = importance,
                configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0)), createdAtEpochMillis = 0,
            ),
        )
        val id = database.doseEventDao().insert(
            DoseEvent(reminderId = reminderId, medicineId = medicineId, scheduledEpochMillis = 1_000_000L, doseAmount = 1.0, status = DoseStatus.PENDING, createdAtEpochMillis = 0),
        )
        val settings = mockk<SettingsRepository>().also {
            coEvery { it.isLockedUntilTaken(any()) } returns false
            every { it.hideMedicineNames } returns flowOf(false)
            every { it.palette } returns flowOf(DEFAULT_PALETTE)
            every { it.snoozeAskEachTime } returns flowOf(false)
            every { it.swipeAction } returns flowOf(SwipeAction.NOTHING)
        }
        val groups = mockk<DoseGroupDao>().also { coEvery { it.getById(any()) } returns null }
        ReminderNotificationBuilder(context, groups, settings).buildAndPost(
            database.doseEventDao().getById(id)!!, database.reminderDao().getById(reminderId)!!, database.medicineDao().getById(medicineId)!!,
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).getNotification(id.toInt())
    }

    @Test
    fun `with no tier of its own it posts on the medicine's`() = runTest {
        assertEquals(ReminderNotificationChannels.CHANNEL_STANDARD, post(null).channelId)
    }

    @Test
    fun `a reminder raised to High posts on the High channel`() = runTest {
        assertEquals(ReminderNotificationChannels.CHANNEL_HIGH, post(NotificationPriority.HIGH).channelId)
    }
}
