package com.hazia.emivita.core.scheduling.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.notification.ACTION_TAKE_ALL
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_IDS
import com.hazia.emivita.core.notification.REMINDER_GROUP_SUMMARY_ID
import com.hazia.emivita.core.scheduling.ReminderActionReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * §7 R110, the owner's decision of 2026-10-21: the group summary's "Take all"
 * leaves out a reminder that asks the amount (R109, B-45), which stays due;
 * with fewer than two doses left to take, the summary has no "Take all".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TakeAllAsksAmountTest {

    private val h = ReceiverHarness()
    private val nm = h.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @After
    fun tearDown() = h.close()

    private data class Due(val medicineId: Long, val doseId: Long)

    /** A medicine, a daily 09:00 reminder, its first fire: one dose showing. */
    private fun fired(name: String, asksAmount: Boolean = false): Due {
        val medicineId = h.medicine(name, stock = 10.0)
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(hour = 9, minute = 0, dose = 1.0), variableAmount = asksAmount)
        h.fire(h.regularAlarms(reminderId).single())
        return Due(medicineId, h.doseEvents(reminderId).single().id)
    }

    private fun summary(): Notification? = shadowOf(nm).getNotification(REMINDER_GROUP_SUMMARY_ID)

    private fun takeAllIds(n: Notification): List<Long> =
        shadowOf(n.actions.single().actionIntent).savedIntent.getLongArrayExtra(EXTRA_DOSE_EVENT_IDS)!!.toList()

    @Test
    fun `the summary's Take all covers the doses that do not ask the amount, and the one that does stays due`() {
        val a = fired("Atorvastatin")
        val asks = fired("Metformin", asksAmount = true)
        val r = fired("Ramipril")
        assertNotNull("three showing get a summary", summary())
        val s = summary()!!
        assertEquals("3 doses due", s.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf(a.doseId, r.doseId), takeAllIds(s))

        h.deliver(Intent(shadowOf(s.actions.single().actionIntent).savedIntent))

        assertEquals(DoseStatus.TAKEN, h.doseEvent(a.doseId).status)
        assertEquals(DoseStatus.TAKEN, h.doseEvent(r.doseId).status)
        assertEquals(DoseStatus.PENDING, h.doseEvent(asks.doseId).status)
        assertEquals(10.0, h.medicineRow(asks.medicineId).stockInfo.quantity, 0.0)
        assertEquals("the one left due is still showing", listOf(asks.doseId.toInt()), h.notificationIds.filter { it > 0 })
    }

    @Test
    fun `with one dose left to take once the asking one is out, the summary has no Take all`() {
        fired("Atorvastatin")
        fired("Metformin", asksAmount = true)
        val s = summary()!!
        assertEquals("2 doses due", s.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, s.actions?.size ?: 0)
    }

    @Test
    fun `a Take all that names the asking dose still leaves it due`() {
        val a = fired("Atorvastatin")
        val asks = fired("Metformin", asksAmount = true)
        // A summary posted before the flag was turned on would carry every id.
        h.deliver(
            Intent(h.context, ReminderActionReceiver::class.java)
                .setAction(ACTION_TAKE_ALL)
                .putExtra(EXTRA_DOSE_EVENT_IDS, longArrayOf(a.doseId, asks.doseId)),
        )
        assertEquals(DoseStatus.TAKEN, h.doseEvent(a.doseId).status)
        assertEquals(DoseStatus.PENDING, h.doseEvent(asks.doseId).status)
        assertEquals(10.0, h.medicineRow(asks.medicineId).stockInfo.quantity, 0.0)
    }
}
