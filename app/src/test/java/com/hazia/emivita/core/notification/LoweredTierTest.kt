package com.hazia.emivita.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hazia.emivita.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §7 R91, the owner's call after R90: a tier still on but set below the
 * importance Emivita gave it is reported too. R90 found the case on the 6T:
 * OxygenOS turns a blocked channel back on at LOW. The channel reads go
 * through a real NotificationManager (rule 22), and the decision is checked
 * on its own below.
 */
@RunWith(RobolectricTestRunner::class)
class LoweredTierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun setAlarmImportance(importance: Int) {
        val current = manager.getNotificationChannel(ReminderNotificationChannels.CHANNEL_ALARM)
        manager.deleteNotificationChannel(ReminderNotificationChannels.CHANNEL_ALARM)
        manager.createNotificationChannel(NotificationChannel(current.id, current.name, importance))
    }

    @Test
    fun `channels as created are neither blocked nor lowered`() {
        ReminderNotificationChannels.createAll(context)
        assertEquals(emptyList<String>(), ReminderNotificationChannels.blockedTiers(context))
        assertEquals(emptyList<String>(), ReminderNotificationChannels.loweredTiers(context))
    }

    @Test
    fun `an Alarm tier turned back on at LOW is lowered, not blocked`() {
        ReminderNotificationChannels.createAll(context)
        setAlarmImportance(NotificationManager.IMPORTANCE_LOW)
        val alarm = context.getString(R.string.tier_alarm)
        assertEquals(emptyList<String>(), ReminderNotificationChannels.blockedTiers(context))
        assertEquals(listOf(alarm), ReminderNotificationChannels.loweredTiers(context))
    }

    @Test
    fun `a blocked tier is blocked, not lowered`() {
        ReminderNotificationChannels.createAll(context)
        setAlarmImportance(NotificationManager.IMPORTANCE_NONE)
        val alarm = context.getString(R.string.tier_alarm)
        assertEquals(listOf(alarm), ReminderNotificationChannels.blockedTiers(context))
        assertEquals(emptyList<String>(), ReminderNotificationChannels.loweredTiers(context))
    }

    @Test
    fun `a lowered tier gets its own card, and a blocked one outranks it`() {
        val lowered = notificationDelivery(true, emptyList(), listOf("Alarm"))
        assertEquals(NotificationDelivery.TiersLowered(listOf("Alarm")), lowered)
        assertEquals(R.string.notif_card_one_tier_lowered_title, lowered.cardTitle()!!.res)
        assertEquals(R.string.notif_card_one_tier_lowered_body, lowered.cardBody()!!.res)
        assertEquals(listOf<Any>("Alarm"), lowered.cardBody()!!.args)

        val two = notificationDelivery(true, emptyList(), listOf("Standard", "Alarm"))
        assertEquals(R.string.notif_card_some_tiers_lowered_title, two.cardTitle()!!.res)
        assertEquals(R.string.notif_card_some_tiers_lowered_body, two.cardBody()!!.res)
        assertNotNull(two.cardBody())

        assertEquals(
            "a tier nothing reaches is worse than a quieter one",
            NotificationDelivery.TiersBlocked(listOf("High")),
            notificationDelivery(true, listOf("High"), listOf("Alarm")),
        )
        assertEquals(NotificationDelivery.AllBlocked, notificationDelivery(false, emptyList(), listOf("Alarm")))
    }
}
