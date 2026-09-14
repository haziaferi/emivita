package com.hazia.emivita.core.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.ui.theme.DEFAULT_PALETTE
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The one line over the stock alerts (spec §9 #27; DS-05). */
@RunWith(RobolectricTestRunner::class)
class AlertNotificationSummaryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var builder: AlertNotificationBuilder

    @Before
    fun setUp() {
        ReminderNotificationChannels.createAll(context)
        shadowOf(context.applicationContext as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val settings = mockk<SettingsRepository>().also { every { it.hideMedicineNames } returns flowOf(false); every { it.palette } returns flowOf(DEFAULT_PALETTE) }
        builder = AlertNotificationBuilder(context, settings)
    }

    private fun medicine(id: Long, name: String) = Medicine(id = id, profileId = 1, name = name, iconKey = "pill", stockInfo = StockInfo(quantity = 1.0, unit = "tablet", refillQuantity = 30.0), sortOrder = 0.0, createdAtEpochMillis = 0)
    private fun reminder(id: Long, medicineId: Long) = Reminder(id = id, medicineId = medicineId, type = ReminderType.LOW_STOCK_ALERT, configJson = "{}", createdAtEpochMillis = 0)
    private fun shown() = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications.associateBy { it.id }

    @Test
    fun `one alert is one card and no summary`() = runTest {
        builder.postLowStock(medicine(1, "Sertraline"), reminder(11, 1))
        assertEquals(setOf(-11), shown().keys)
    }

    @Test
    fun `two alerts get a silent summary that names both and is gone when one is refilled`() = runTest {
        builder.postLowStock(medicine(1, "Sertraline"), reminder(11, 1))
        builder.postExpiration(medicine(2, "Ibuprofen"), reminder(12, 2), 20000L)
        val summary = shown()[STOCK_GROUP_SUMMARY_ID]!!.notification
        assertEquals(STOCK_GROUP_KEY, summary.group)
        assertTrue((summary.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0)
        assertEquals("2 medicines need attention", summary.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        val lines = summary.extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertTrue(lines.any { it.contains("Sertraline") }); assertTrue(lines.any { it.contains("Ibuprofen") })
        assertEquals("the children carry the same group", STOCK_GROUP_KEY, shown()[-11]!!.notification.group)

        // the refill receiver's path: cancel the child, then refresh
        NotificationManagerCompat.from(context).cancel(-11)
        builder.updateStockSummary()
        assertNull("one child left, no summary", shown()[STOCK_GROUP_SUMMARY_ID])
        assertEquals(setOf(-12), shown().keys)
    }
}
