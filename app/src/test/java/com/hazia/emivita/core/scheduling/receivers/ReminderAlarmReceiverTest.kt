package com.hazia.emivita.core.scheduling.receivers

import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.core.notification.ACTION_TAKE
import com.hazia.emivita.core.notification.preAlarmNotificationId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The alarm receiver on the JVM (spec §9 #30; Appendix E B-47). medTimer's
 * processor cases -- `scheduleReminder`, `noReminder`,
 * `reminderNotificationNotActive`, `rescheduleReminderEvent`, `repeat` --
 * in Emivita's terms, plus the paths the E.5.B inventory listed as untested:
 * a disabled reminder's late alarm, an alert fire whose condition has gone,
 * a pre-alarm. Everything runs through the production container over
 * in-memory Room; see [ReceiverHarness] for how an alarm is delivered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderAlarmReceiverTest {

    private val h = ReceiverHarness()

    @After
    fun tearDown() = h.close()

    private fun fixedTime(hour: Int = 9, minute: Int = 0, dose: Double = 1.0) = ReminderConfig.FixedTime(hour = hour, minute = minute, dose = dose)

    @Test
    fun `scheduleReminder -- creating a fixed-time reminder arms one regular alarm at its next slot, in the future`() {
        val reminderId = h.reminder(h.medicine(), fixedTime(hour = 9, minute = 30))

        val armed = h.regularAlarms(reminderId)
        assertEquals(1, armed.size)
        assertEquals(LocalTime.of(9, 30), Instant.ofEpochMilli(armed.single().triggerAtMs).atZone(ZoneId.systemDefault()).toLocalTime())
        assertTrue(armed.single().triggerAtMs > System.currentTimeMillis())
    }

    @Test
    fun `noReminder -- an empty database arms nothing, and deleting the reminder cancels its alarm`() {
        runBlocking { h.container.schedulingCoordinator.rescheduleAll() }
        assertEquals(emptyList<Any>(), h.alarms)

        val reminderId = h.reminder(h.medicine(), fixedTime())
        assertEquals(1, h.regularAlarms(reminderId).size)
        runBlocking { h.container.reminderRepository.delete(h.container.reminderRepository.getById(reminderId)!!) }
        assertEquals(emptyList<Any>(), h.regularAlarms(reminderId))
    }

    @Test
    fun `reminderNotificationNotActive -- the fire materializes one pending dose, posts its notification, arms the next slot`() {
        val reminderId = h.reminder(h.medicine(), fixedTime())
        val firedAt = h.fire(h.regularAlarms(reminderId).single())

        val event = h.doseEvents(reminderId).single()
        assertEquals(DoseStatus.PENDING, event.status)
        assertEquals(firedAt, event.scheduledEpochMillis)
        assertEquals(listOf(event.id.toInt()), h.notificationIds)
        assertEquals(listOf(firedAt + 86_400_000L), h.regularAlarms(reminderId).map { it.triggerAtMs })
    }

    @Test
    fun `rescheduleReminderEvent -- the same alarm delivered twice is one dose, re-shown, not doubled`() {
        val reminderId = h.reminder(h.medicine(), fixedTime())
        val first = h.regularAlarms(reminderId).single()
        val firstIntent = h.capture(first)
        h.fire(first)
        h.deliver(firstIntent)

        val event = h.doseEvents(reminderId).single()
        assertEquals(DoseStatus.PENDING, event.status)
        assertEquals(listOf(event.id.toInt()), h.notificationIds)
        assertEquals(1, h.regularAlarms(reminderId).size)
    }

    @Test
    fun `a duplicate delivery for a dose already taken posts nothing`() {
        val reminderId = h.reminder(h.medicine(), fixedTime())
        val first = h.regularAlarms(reminderId).single()
        val firstIntent = h.capture(first)
        h.fire(first)
        h.action(ACTION_TAKE, h.doseEvents(reminderId).single().id)
        assertEquals(emptyList<Int>(), h.notificationIds)

        h.deliver(firstIntent)

        assertEquals(DoseStatus.TAKEN, h.doseEvents(reminderId).single().status)
        assertEquals(emptyList<Int>(), h.notificationIds)
    }

    @Test
    fun `repeat -- a nag re-shows the unanswered dose, counts, re-arms, and stops at the cap`() {
        val reminderId = h.reminder(h.medicine(), fixedTime(), repeat = RepeatConfig(delayMinutes = 5, maxRepeats = 2))
        val firedAt = h.fire(h.regularAlarms(reminderId).single())
        val event = h.doseEvents(reminderId).single()

        val nag1 = h.nagAlarms(reminderId).single()
        assertTrue("first nag about five minutes out", nag1.triggerAtMs - System.currentTimeMillis() in 4 * 60_000L..5 * 60_000L)
        assertEquals(firedAt, h.scheduledAtOf(nag1))

        h.fire(nag1)
        assertEquals(1, h.doseEvent(event.id).repeatCount)
        assertEquals(listOf(event.id.toInt()), h.notificationIds)
        val nag2 = h.nagAlarms(reminderId).single()

        h.fire(nag2)
        assertEquals(2, h.doseEvent(event.id).repeatCount)
        assertEquals(emptyList<Any>(), h.nagAlarms(reminderId))
        assertEquals(DoseStatus.PENDING, h.doseEvent(event.id).status)
    }

    @Test
    fun `answering the dose cancels its pending nag`() {
        val reminderId = h.reminder(h.medicine(), fixedTime(), repeat = RepeatConfig(delayMinutes = 5, maxRepeats = 3))
        h.fire(h.regularAlarms(reminderId).single())
        assertEquals(1, h.nagAlarms(reminderId).size)

        h.action(ACTION_TAKE, h.doseEvents(reminderId).single().id)

        assertEquals(emptyList<Any>(), h.nagAlarms(reminderId))
    }

    @Test
    fun `a disabled reminder's late alarm materializes nothing and posts nothing`() {
        val reminderId = h.reminder(h.medicine(), fixedTime())
        val intent = h.capture(h.regularAlarms(reminderId).single())
        runBlocking { h.container.reminderRepository.setEnabled(h.container.reminderRepository.getById(reminderId)!!, false) }
        assertEquals(emptyList<Any>(), h.regularAlarms(reminderId))

        h.deliver(intent)

        assertEquals(emptyList<Any>(), h.doseEvents(reminderId))
        assertEquals(emptyList<Int>(), h.notificationIds)
    }

    @Test
    fun `a low-stock alert re-checks live stock -- refilled between arming and firing, it posts nothing and stays armed`() {
        val medicineId = h.medicine(stock = 5.0)
        val reminderId = h.reminder(medicineId, ReminderConfig.LowStockAlert(threshold = 10.0, triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW))
        val intent = h.capture(h.regularAlarms(reminderId).single())
        runBlocking {
            val m = h.medicineRow(medicineId)
            h.container.medicineRepository.update(m.copy(stockInfo = m.stockInfo.copy(quantity = 30.0)))
        }

        h.deliver(intent)

        assertEquals(emptyList<Int>(), h.notificationIds)
        assertNull(runBlocking { h.container.reminderRepository.getById(reminderId) }!!.lastAlertNotifiedEpochDay)
    }

    @Test
    fun `a low-stock alert still low when it fires posts, records the day, and re-arms`() {
        val medicineId = h.medicine(stock = 5.0)
        val reminderId = h.reminder(medicineId, ReminderConfig.LowStockAlert(threshold = 10.0, triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW))

        h.fire(h.regularAlarms(reminderId).single())

        assertEquals(listOf(-reminderId.toInt()), h.notificationIds)
        assertEquals(LocalDate.now().toEpochDay(), runBlocking { h.container.reminderRepository.getById(reminderId) }!!.lastAlertNotifiedEpochDay)
        assertEquals(1, h.regularAlarms(reminderId).size)
    }

    @Test
    fun `a pre-alarm posts the heads-up only while the reminder still wants one`() {
        // 23:59 is always ahead of a 30-minute lead computed from now, whatever the clock says.
        val reminderId = h.reminder(h.medicine(), fixedTime(hour = 23, minute = 59), preAlarmMinutes = 30)
        val pre = h.preAlarms(reminderId).single()
        assertEquals(h.scheduledAtOf(pre) - 30 * 60_000L, pre.triggerAtMs)
        val intent = h.capture(pre)

        h.fire(pre)
        assertEquals(listOf(preAlarmNotificationId(reminderId)), h.notificationIds)
        assertEquals(emptyList<Any>(), h.doseEvents(reminderId))

        h.dismissFromShade(preAlarmNotificationId(reminderId))
        runBlocking {
            val r = h.container.reminderRepository.getById(reminderId)!!
            h.container.reminderRepository.updateAdvancedFields(r, autoResolve = false, activeFromEpochDay = null, activeUntilEpochDay = null, repeatOnUnanswered = null, weekendModeOptIn = false, preAlarmMinutes = null)
        }
        h.deliver(intent)
        assertEquals(emptyList<Int>(), h.notificationIds)
    }
}
