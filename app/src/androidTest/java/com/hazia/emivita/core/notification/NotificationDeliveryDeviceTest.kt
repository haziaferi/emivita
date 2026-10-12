package com.hazia.emivita.core.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hazia.emivita.DeviceTestSupport
import com.hazia.emivita.core.data.entity.NotificationPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two framework reads behind the notifications-blocked card, on real
 * hardware (spec §7 R58, §9 #20).
 *
 * The JVM `NotificationDeliveryTest` covers the decision, and this covers the
 * values: the three channels `createAll` registers exist on the device and
 * [ReminderNotificationChannels.blockedTiers] and the card's input agree with
 * NotificationManager.
 *
 * Asserts consistency, never "everything enabled", because the phone may
 * legitimately have the very blocked state the card exists to describe.
 */
@RunWith(AndroidJUnit4::class)
class NotificationDeliveryDeviceTest {

    @Test
    fun allThreeChannelsExistAndBlockedTiersAgreesWithTheSystem() {
        val app = DeviceTestSupport.app()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val expectBlocked = mutableListOf<String>()
        for (priority in NotificationPriority.entries) {
            val id = ReminderNotificationChannels.channelIdFor(priority)
            val channel = nm.getNotificationChannel(id)
            assertNotNull(
                "channel '$id' should exist -- createAll() runs in EmivitaApplication.onCreate",
                channel,
            )
            if (channel!!.importance == NotificationManager.IMPORTANCE_NONE) {
                expectBlocked += app.getString(ReminderNotificationChannels.tierNameFor(priority))
            }
        }

        val reported = ReminderNotificationChannels.blockedTiers(app)
        assertEquals(
            "blockedTiers() must report exactly the tiers NotificationManager says are IMPORTANCE_NONE",
            expectBlocked, reported,
        )
    }

    @Test
    fun theAlarmTierChannelIsActuallyAnAlarm() {
        // §7 R59: the original alarm channel was created with the defaults --
        // the notification chime on the notification stream, no vibration --
        // so an "alarm" chimed once like a message. Nothing on the JVM can see
        // what a channel was created with; only the OS can. This pins the
        // three properties that make the difference, and that the pre-v2
        // channel is gone (getNotificationChannel returns null for a deleted
        // one), so a fresh install cannot come back with the chime.
        val app = DeviceTestSupport.app()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarm = nm.getNotificationChannel(ReminderNotificationChannels.CHANNEL_ALARM)
        assertNotNull("alarm channel should exist", alarm)
        assertEquals("alarm tier must use the ALARM audio stream",
            android.media.AudioAttributes.USAGE_ALARM, alarm!!.audioAttributes?.usage)
        assertEquals("alarm tier must play the system alarm tone, not the notification chime",
            android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, alarm.sound)
        assertEquals("alarm tier must vibrate", true, alarm.shouldVibrate())
        assertEquals("the pre-v2 alarm channel must be gone", null, nm.getNotificationChannel("reminders_alarm"))
    }

    @Test
    fun theCardsInputsAreWiredToTheRealSystemState() {
        val app = DeviceTestSupport.app()
        val enabled = NotificationManagerCompat.from(app).areNotificationsEnabled()
        val blocked = ReminderNotificationChannels.blockedTiers(app)
        val state = notificationDelivery(enabled, blocked)

        // The same three-way mapping the JVM test pins, evaluated on live
        // inputs rather than literals. Whatever this device is set to, the
        // card must describe it, never contradict it.
        val expected = when {
            !enabled -> NotificationDelivery.AllBlocked
            blocked.isNotEmpty() -> NotificationDelivery.TiersBlocked(blocked)
            else -> NotificationDelivery.Fine
        }
        assertEquals(expected, state)

        // Reported so a device run leaves a record of what it saw.
        println(
            "NotificationDeliveryDeviceTest: notificationsEnabled=$enabled " +
                "blockedTiers=$blocked -> ${state::class.simpleName}",
        )
    }
}
