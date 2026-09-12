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
 * `NotificationDeliveryTest` on the JVM covers the decision given two plain
 * values. This covers the values themselves: that the three channels
 * `createAll` registers in `EmivitaApplication.onCreate` actually exist on
 * the device, that [ReminderNotificationChannels.blockedTiers] agrees with
 * what NotificationManager reports about them, and that the card's input
 * function is wired to both reads.
 *
 * Deliberately asserts *consistency*, not a fixed answer. The developer's own
 * phone may legitimately have a tier blocked or notifications off, and a test
 * that demanded "everything enabled" would fail on the exact state the card
 * exists to describe. What must always hold is that the card and the OS
 * agree.
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
