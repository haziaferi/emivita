package com.hazia.emivita.core.scheduling.receivers

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Reminder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate

/**
 * §7 R105 (census B-44): a stock or expiry alert swiped away is recorded in
 * History as seen, as medTimer records it, without touching stock, and it is
 * neither a dose nor a deletable one. Driven through the posted notification's
 * own delete intent, so the builder's extras and the receiver are both crossed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AlertSeenTest {

    private val h = ReceiverHarness()

    @After
    fun tearDown() = h.close()

    private fun alertReminder(medicineId: Long, type: ReminderType) =
        Reminder(id = 900L, medicineId = medicineId, type = type, configJson = "{}", createdAtEpochMillis = 0)

    private fun swipe() {
        val nm = h.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(nm).activeNotifications.single { it.id == -900 }
        h.deliver(Intent(shadowOf(posted.notification.deleteIntent).savedIntent))
    }

    private fun history() = runBlocking {
        h.container.doseEventRepository.getInRangeOnce(h.profileId, 0L, Long.MAX_VALUE, medicineId = null).map { it.doseEvent }
    }

    @Test
    fun `a swiped low-stock alert is recorded as seen, and stock is untouched`() {
        val medicineId = h.medicine(stock = 2.0)
        runBlocking { h.container.alertNotificationBuilder.postLowStock(h.medicineRow(medicineId), alertReminder(medicineId, ReminderType.LOW_STOCK_ALERT)) }

        swipe()

        val row = history().single()
        assertEquals(DoseStatus.STOCK_ALERT_SEEN, row.status)
        assertEquals(medicineId, row.medicineId)
        assertNull("tied to the medicine, so deleting the alert keeps it", row.reminderId)
        assertEquals(2.0, h.medicineRow(medicineId).stockInfo.quantity, 0.001)
    }

    @Test
    fun `a swiped expiry alert is recorded as its own kind`() {
        val medicineId = h.medicine()
        runBlocking {
            h.container.alertNotificationBuilder.postExpiration(
                h.medicineRow(medicineId), alertReminder(medicineId, ReminderType.EXPIRATION_ALERT), LocalDate.of(2026, 11, 1).toEpochDay(),
            )
        }

        swipe()

        assertEquals(DoseStatus.EXPIRY_ALERT_SEEN, history().single().status)
    }

    @Test
    fun `a seen alert is not the medicine's last dose and cannot be soft-deleted`() = runBlocking {
        val medicineId = h.medicine()
        val id = h.container.doseEventRepository.recordAlertSeen(medicineId, DoseStatus.STOCK_ALERT_SEEN, 1_000L)

        assertNull(h.container.doseEventRepository.lastDose(medicineId))
        h.container.doseEventRepository.softDelete(id)
        assertFalse(h.doseEvent(id).status == DoseStatus.DELETED)
    }
}
