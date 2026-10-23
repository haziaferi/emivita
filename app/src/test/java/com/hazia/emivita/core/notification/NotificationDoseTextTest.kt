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
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.data.settings.SwipeAction
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.ui.theme.DEFAULT_PALETTE
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The reminder's text (§7 R120, the owner's pick of 2026-10-23): the amount
 * with its unit, then the time, "1 gocce alle 08:00", as Today shows the dose;
 * "1 dose at 08:00" without a unit. It was "Dose of 1 at 08:00", the bare
 * amount. A repeat re-posts through the same builder; the lock-screen version
 * still names nothing.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationDoseTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: EmivitaDatabase
    private val scheduled = 1_800_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, EmivitaDatabase::class.java).allowMainThreadQueries().build()
        ReminderNotificationChannels.createAll(context)
        shadowOf(context.applicationContext as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        NotificationRateLimit.reset()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun post(dose: Double, doseUnit: String?, hideNames: Boolean = false): Notification {
        val profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        val medicineId = database.medicineDao().insert(
            Medicine(profileId = profileId, name = "Sertraline", iconKey = "pill", doseUnit = doseUnit, sortOrder = 0.0, createdAtEpochMillis = 0),
        )
        val medicine = database.medicineDao().getById(medicineId)!!
        val reminderId = database.reminderDao().insert(
            Reminder(
                medicineId = medicineId, type = ReminderType.FIXED_TIME,
                configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = dose)), createdAtEpochMillis = 0,
            ),
        )
        val reminder = database.reminderDao().getById(reminderId)!!
        val id = database.doseEventDao().insert(
            DoseEvent(reminderId = reminderId, medicineId = medicineId, scheduledEpochMillis = scheduled, doseAmount = dose, status = DoseStatus.PENDING, createdAtEpochMillis = 0),
        )
        val event = database.doseEventDao().getById(id)!!
        val settings = mockk<SettingsRepository>().also {
            coEvery { it.isLockedUntilTaken(any()) } returns false
            every { it.hideMedicineNames } returns flowOf(hideNames)
            every { it.palette } returns flowOf(DEFAULT_PALETTE)
            every { it.snoozeAskEachTime } returns flowOf(false)
            every { it.swipeAction } returns flowOf(SwipeAction.NOTHING)
        }
        val groups = mockk<DoseGroupDao>().also { coEvery { it.getById(any()) } returns null }
        ReminderNotificationBuilder(context, groups, settings).buildAndPost(event, reminder, medicine)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).getNotification(id.toInt())
    }

    private fun Notification.text() = extras.getString(Notification.EXTRA_TEXT)!!
    private val time get() = LocaleFormats.time(scheduled)

    @Test
    fun `with a unit it reads the amount, the unit and the time`() = runTest {
        assertEquals("1 drops at $time", post(1.0, "drops").text())
    }

    @Test
    fun `without a unit it reads the default unit`() = runTest {
        assertEquals("1 dose at $time", post(1.0, null).text())
        assertEquals("2 doses at $time", post(2.0, null).text())
    }

    @Test
    fun `a decimal amount keeps its fraction`() = runTest {
        assertEquals("0.5 ml at $time", post(0.5, "ml").text())
    }

    @Test
    @Config(qualifiers = "it")
    fun `in Italian it is the owner's 1 gocce alle 08 00`() = runTest {
        assertEquals("1 gocce alle $time", post(1.0, "gocce").text())
    }

    @Test
    fun `the lock-screen version names neither the medicine nor the dose`() = runTest {
        val public = post(1.0, "drops", hideNames = true).publicVersion!!
        val shown = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT).map { public.extras.getCharSequence(it).toString() }
        assertFalse(shown.toString(), shown.any { "Sertraline" in it || "drops" in it })
    }
}
