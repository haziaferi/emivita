package com.hazia.emivita.core.scheduling.receivers

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.core.alarm.AlarmRingService
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.notification.ReminderNotifications
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Duration

/**
 * The Alarm tier rings until it is answered (§7 R80; R75's follow-up 1).
 * Posting an Alarm-tier reminder hands the notification to
 * [AlarmRingService], which shows it as its own and rings under it; the
 * service stops when the dose is answered anywhere, when told to, or after
 * ten minutes -- keeping the notification in that last case. Sound itself
 * is not asserted: Robolectric's MediaPlayer has no tone to open, and the
 * ringer rings without one, as it would on a phone with a deleted tone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AlarmRingServiceTest {
    private val h = ReceiverHarness()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = h.close()

    private fun alarmMedicine(): Long = runBlocking {
        val id = h.medicine("Alarm test")
        val m = h.container.medicineRepository.getById(id)!!
        h.container.medicineRepository.update(m.copy(notificationPriority = NotificationPriority.ALARM))
        id
    }

    private fun materializedDose(reminderId: Long, medicineId: Long): Long = runBlocking {
        h.container.doseEventRepository.materializeIfAbsent(reminderId, medicineId, System.currentTimeMillis(), 1.0)!!
    }

    /** Walks the looper's clock forward by [advance] in small steps with real
     *  time between them: the ringer's settings read and the poll's Room query
     *  each hop off Main and post back, and a hop only lands between steps.
     *  Stops early once [until] holds. */
    private fun settle(advance: Duration, stepMillis: Long = 250, until: () -> Boolean) {
        var left = advance.toMillis()
        while (left > 0) {
            Thread.sleep(15)
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            val step = minOf(stepMillis, left)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            left -= step
        }
        Thread.sleep(15)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ringIntent(doseEventId: Long): Intent {
        val notification = Notification.Builder(app, "reminders_alarm_v2").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        return Intent(app, AlarmRingService::class.java).setAction(AlarmRingService.ACTION_RING)
            .putExtra(AlarmRingService.EXTRA_DOSE_EVENT_ID, doseEventId)
            .putExtra(AlarmRingService.EXTRA_NOTIFICATION, notification)
    }

    @Test
    fun `posting an Alarm-tier reminder starts the ring service with the notification, and Prendi stops it`() {
        val medicineId = alarmMedicine()
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(8, 0, 1.0))
        val doseEventId = materializedDose(reminderId, medicineId)
        val dose = h.doseEvent(doseEventId)
        val reminder = runBlocking { h.container.reminderRepository.getById(reminderId)!! }
        runBlocking { h.container.notificationBuilder.buildAndPost(dose, reminder, h.medicineRow(medicineId)) }

        val started = shadowOf(app).nextStartedService
        assertNotNull("an Alarm-tier post starts the ring service", started)
        assertEquals(AlarmRingService::class.java.name, started!!.component!!.className)
        assertEquals(AlarmRingService.ACTION_RING, started.action)
        assertEquals(doseEventId, started.getLongExtra(AlarmRingService.EXTRA_DOSE_EVENT_ID, -1L))
        assertNotNull("the notification travels with it", started.getParcelableExtra<Notification>(AlarmRingService.EXTRA_NOTIFICATION))

        ReminderNotifications.dismiss(app, doseEventId)
        val stop = shadowOf(app).nextStartedService
        assertNotNull("a dismissal tells the service to stop", stop)
        assertEquals(AlarmRingService.ACTION_STOP, stop!!.action)
        assertEquals(doseEventId, stop.getLongExtra(AlarmRingService.EXTRA_DOSE_EVENT_ID, -1L))
    }

    /** §7 R81 (§9 #34's Alarm-channel check): the user blocks the Alarm channel in
     *  Android, and the tone must not play under a notification nobody can see. */
    @Test
    fun `with the Alarm channel blocked in Android, nothing rings`() {
        val nm = app.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.getNotificationChannel(com.hazia.emivita.core.notification.ReminderNotificationChannels.CHANNEL_ALARM).importance =
            android.app.NotificationManager.IMPORTANCE_NONE
        assertTrue(com.hazia.emivita.core.notification.ReminderNotificationChannels.isBlocked(app, NotificationPriority.ALARM))

        val medicineId = alarmMedicine()
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(8, 0, 1.0))
        val doseEventId = materializedDose(reminderId, medicineId)
        val dose = h.doseEvent(doseEventId)
        val reminder = runBlocking { h.container.reminderRepository.getById(reminderId)!! }
        runBlocking { h.container.notificationBuilder.buildAndPost(dose, reminder, h.medicineRow(medicineId)) }

        assertNull("a blocked Alarm channel starts no ring service", shadowOf(app).nextStartedService)
    }

    @Test
    fun `the service shows the notification as its own, and stops once the dose is answered elsewhere`() {
        val medicineId = alarmMedicine()
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(8, 0, 1.0))
        val doseEventId = materializedDose(reminderId, medicineId)

        val controller = Robolectric.buildService(AlarmRingService::class.java, ringIntent(doseEventId)).create().startCommand(0, 1)
        val service = controller.get()
        val shadow = shadowOf(service)
        assertEquals("foreground under the reminder's own id", doseEventId.toInt(), shadow.lastForegroundNotificationId)
        // The id outlives stopForeground in Robolectric, so it alone cannot
        // say the service is still foreground (§7 R83 (d), rule 5).
        assertFalse("still foreground while it rings", shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)

        // Answered from Today, without any dismiss call reaching the service.
        runBlocking { h.container.doseEventRepository.resolve(doseEventId, DoseStatus.TAKEN) }
        settle(Duration.ofMillis(AlarmRingService.POLL_MILLIS * 2)) { shadow.isStoppedBySelf }
        assertTrue("the poll noticed the answer", shadow.isStoppedBySelf)
        assertTrue("the notification goes with it", shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        controller.destroy()
    }

    @Test
    fun `unanswered for ten minutes, the sound stops and the notification stays`() {
        val medicineId = alarmMedicine()
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(8, 0, 1.0))
        val doseEventId = materializedDose(reminderId, medicineId)

        val controller = Robolectric.buildService(AlarmRingService::class.java, ringIntent(doseEventId)).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        settle(Duration.ofMillis(AlarmRingService.RING_TIMEOUT_MILLIS - AlarmRingService.POLL_MILLIS * 2), stepMillis = AlarmRingService.POLL_MILLIS) { false }
        assertFalse("still ringing before the timeout", shadow.isStoppedBySelf)
        settle(Duration.ofMillis(AlarmRingService.POLL_MILLIS * 4), stepMillis = AlarmRingService.POLL_MILLIS) { shadow.isStoppedBySelf }
        assertTrue("stopped after ten minutes", shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertFalse("the notification is detached, not removed", shadow.notificationShouldRemoved)
        assertNull(runBlocking { h.container.doseEventRepository.getById(doseEventId) }?.takeIf { it.status != DoseStatus.PENDING })
        controller.destroy()
    }

    @Test
    fun `a stop for another dose is ignored, a stop for this one ends it`() {
        val medicineId = alarmMedicine()
        val reminderId = h.reminder(medicineId, ReminderConfig.FixedTime(8, 0, 1.0))
        val doseEventId = materializedDose(reminderId, medicineId)
        val controller = Robolectric.buildService(AlarmRingService::class.java, ringIntent(doseEventId)).create().startCommand(0, 1)
        val service = controller.get()

        service.onStartCommand(Intent(app, AlarmRingService::class.java).setAction(AlarmRingService.ACTION_STOP).putExtra(AlarmRingService.EXTRA_DOSE_EVENT_ID, doseEventId + 99), 0, 2)
        assertFalse("another dose's stop does not end this ring", shadowOf(service).isStoppedBySelf)
        service.onStartCommand(Intent(app, AlarmRingService::class.java).setAction(AlarmRingService.ACTION_STOP).putExtra(AlarmRingService.EXTRA_DOSE_EVENT_ID, doseEventId), 0, 3)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }
}
