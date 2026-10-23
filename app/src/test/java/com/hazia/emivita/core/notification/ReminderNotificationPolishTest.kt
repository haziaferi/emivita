package com.hazia.emivita.core.notification

import com.hazia.emivita.core.data.settings.SwipeAction
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
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.format.maskedMedicineName
import io.mockk.coEvery
import com.hazia.emivita.ui.theme.DEFAULT_PALETTE
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** §9 #25 piece A: hidden names, "N left", and the ten-second chime window. */
@RunWith(RobolectricTestRunner::class)
class ReminderNotificationPolishTest {

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

    private suspend fun post(hideNames: Boolean, tracked: Boolean, eventId: Long = 1L, askEachTime: Boolean = false): Notification {
        val profileId = database.profileDao().insert(Profile(name = "P", createdAtEpochMillis = 0))
        val medicineId = database.medicineDao().insert(
            Medicine(
                profileId = profileId, name = "Sertraline", iconKey = "pill",
                stockInfo = if (tracked) StockInfo(quantity = 30.0, unit = "tablet") else StockInfo(), sortOrder = 0.0, createdAtEpochMillis = 0,
            ),
        )
        val medicine = database.medicineDao().getById(medicineId)!!
        val reminderId = database.reminderDao().insert(
            Reminder(
                medicineId = medicineId, type = ReminderType.FIXED_TIME,
                configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 2.0)), createdAtEpochMillis = 0,
            ),
        )
        val reminder = database.reminderDao().getById(reminderId)!!
        val id = database.doseEventDao().insert(
            DoseEvent(id = eventId, reminderId = reminderId, medicineId = medicineId, scheduledEpochMillis = 1_000_000L, doseAmount = 2.0, status = DoseStatus.PENDING, createdAtEpochMillis = 0),
        )
        val event = database.doseEventDao().getById(id)!!
        val settings = mockk<SettingsRepository>().also {
            coEvery { it.isLockedUntilTaken(any()) } returns false
            every { it.hideMedicineNames } returns flowOf(hideNames)
            every { it.palette } returns flowOf(DEFAULT_PALETTE)
            every { it.snoozeAskEachTime } returns flowOf(askEachTime)
            every { it.swipeAction } returns flowOf(SwipeAction.NOTHING) // every button shown, as before §7 R109
        }
        val groups = mockk<DoseGroupDao>().also { coEvery { it.getById(any()) } returns null }
        ReminderNotificationBuilder(context, groups, settings).buildAndPost(event, reminder, medicine)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).getNotification(id.toInt())
    }

    @Test
    fun `with names hidden the title is the first letter and asterisks`() = runTest {
        val n = post(hideNames = true, tracked = false)
        assertEquals("S*********", n.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `a tracked medicine says how much is left after this dose`() = runTest {
        val n = post(hideNames = false, tracked = true)
        val text = n.extras.getString(Notification.EXTRA_TEXT)!!
        assertTrue(text, text.contains("28 tablet left after this dose"))
        val untracked = post(hideNames = false, tracked = false, eventId = 2L).extras.getString(Notification.EXTRA_TEXT)!!
        assertFalse(untracked, untracked.contains("left after"))
    }

    @Test
    fun `two reminders inside ten seconds alert once`() {
        assertFalse(NotificationRateLimit.shouldBeSilent(nowEpochMillis = 100_000L))
        assertTrue(NotificationRateLimit.shouldBeSilent(nowEpochMillis = 105_000L))
        assertFalse(NotificationRateLimit.shouldBeSilent(nowEpochMillis = 111_000L))
    }

    @Test
    fun `masking keeps the first letter and a one-letter name`() {
        assertEquals("S*********", maskedMedicineName("Sertraline", hide = true))
        assertEquals("Sertraline", maskedMedicineName("Sertraline", hide = false))
        assertEquals("X", maskedMedicineName("X", hide = true))
    }

    @Test
    fun `the test reminder posts one real notification on the Standard channel with nothing to take`() {
        postTestReminder(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = shadowOf(nm).getNotification(TEST_NOTIFICATION_ID)
        assertEquals(ReminderNotificationChannels.CHANNEL_STANDARD, n.channelId)
        assertEquals(0, n.actions?.size ?: 0)
        assertEquals("Test reminder", n.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `two showing reminders get a summary with Take all over exactly those, one does not`() = runTest {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        post(hideNames = false, tracked = false, eventId = 11L)
        assertEquals("a lone reminder is a lone card", null, shadowOf(nm).getNotification(REMINDER_GROUP_SUMMARY_ID))
        post(hideNames = false, tracked = false, eventId = 12L)
        val summary = shadowOf(nm).getNotification(REMINDER_GROUP_SUMMARY_ID)
        assertEquals("2 doses due", summary.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Take all", summary.actions.single().title)
        val ids = shadowOf(summary.actions.single().actionIntent).savedIntent.getLongArrayExtra(EXTRA_DOSE_EVENT_IDS)!!.toList()
        assertEquals(listOf(11L, 12L), ids)
        // One answered and dismissed: the summary stays, counting one, because
        // cancelling it would cancel the other dose's reminder too (§7 R115:
        // seen on the 6T; Robolectric's shadow does not cascade, so the test
        // pins the summary). It goes once the group is empty.
        nm.cancel(11)
        val settings = mockk<SettingsRepository>().also {
            every { it.hideMedicineNames } returns flowOf(false)
            every { it.palette } returns flowOf(DEFAULT_PALETTE)
            every { it.snoozeAskEachTime } returns flowOf(false)
            every { it.swipeAction } returns flowOf(SwipeAction.NOTHING) // every button shown, as before §7 R109
        }
        val builder = ReminderNotificationBuilder(context, mockk(relaxed = true), settings)
        builder.updateGroupSummary()
        val lone = shadowOf(nm).getNotification(REMINDER_GROUP_SUMMARY_ID)
        assertEquals("1 dose due", lone?.extras?.getString(Notification.EXTRA_TITLE))
        assertEquals("one dose left has nothing to take all of", 0, lone!!.actions?.size ?: 0)
        nm.cancel(12)
        builder.updateGroupSummary()
        assertEquals(null, shadowOf(nm).getNotification(REMINDER_GROUP_SUMMARY_ID))
    }

    @Test
    fun `with ask-each-time on, the Snooze action opens the picker instead of snoozing`() = runTest {
        val n = post(hideNames = false, tracked = false, eventId = 21L, askEachTime = true)
        val snooze = n.actions.first { it.title == "Snooze" }
        val target = shadowOf(snooze.actionIntent).savedIntent.component!!.className
        assertTrue(target, target.endsWith("SnoozePickerActivity"))
        val plain = post(hideNames = false, tracked = false, eventId = 22L, askEachTime = false)
        assertEquals(null, shadowOf(plain.actions.first { it.title == "Snooze" }.actionIntent).savedIntent.component?.className?.takeIf { it.endsWith("SnoozePickerActivity") })
    }
}
