package com.hazia.emivita.core.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hazia.emivita.R

/**
 * The decision behind the notifications-blocked card (spec §7 R58).
 *
 * Pure JVM, no Robolectric and no `NotificationManager` -- which is the point
 * of [notificationDelivery] taking two plain values rather than a Context.
 * What cannot be covered here is the pair of reads that produce those values
 * (`areNotificationsEnabled()` and [ReminderNotificationChannels.blockedTiers]);
 * those are framework calls, and the card wires them together in one place.
 */
class NotificationDeliveryTest {

    @Test
    fun `nothing in the way renders no card at all`() {
        val state = notificationDelivery(notificationsEnabled = true, blockedTiers = emptyList())
        assertEquals(NotificationDelivery.Fine, state)
        // Both null is what makes the composable `return` before drawing.
        assertNull(state.cardTitle())
        assertNull(state.cardBody())
    }

    @Test
    fun `notifications off for the app is reported regardless of tier state`() {
        // The regression this whole card exists for: before R58 nothing in the
        // tree called areNotificationsEnabled(), so this state was invisible.
        assertEquals(
            NotificationDelivery.AllBlocked,
            notificationDelivery(notificationsEnabled = false, blockedTiers = emptyList()),
        )
        assertEquals(
            "an app-level block outranks the tier detail -- naming tiers on top of it is noise",
            NotificationDelivery.AllBlocked,
            notificationDelivery(notificationsEnabled = false, blockedTiers = listOf("Alarm")),
        )
    }

    @Test
    fun `a blocked tier is reported when notifications are otherwise on`() {
        val state = notificationDelivery(notificationsEnabled = true, blockedTiers = listOf("Alarm"))
        assertEquals(NotificationDelivery.TiersBlocked(listOf("Alarm")), state)
        assertEquals(R.string.notif_card_one_tier_title, state.cardTitle()!!.res)
        assertEquals("the body should carry the tier as its argument", listOf<Any>("Alarm"), state.cardBody()!!.args)
    }

    @Test
    fun `the heading and the body both agree about how many tiers are blocked`() {
        val one = notificationDelivery(true, listOf("Alarm"))
        val two = notificationDelivery(true, listOf("Standard", "Alarm"))

        assertEquals(R.string.notif_card_one_tier_title, one.cardTitle()!!.res)
        assertEquals(R.string.notif_card_some_tiers_title, two.cardTitle()!!.res)
        // Singular/plural has to hold in the body too, not just the heading.
        assertEquals(R.string.notif_card_one_tier_body, one.cardBody()!!.res)
        assertEquals(R.string.notif_card_some_tiers_body, two.cardBody()!!.res)
        assertEquals("both tiers should be passed, in order", listOf<Any>("Standard, Alarm"), two.cardBody()!!.args)
    }

    @Test
    fun `every failing state produces both a heading and a body`() {
        // A card that renders a title over an empty body, or vice versa, is a
        // half-drawn warning -- the composable returns early on either null,
        // so a state with only one of them would silently show nothing.
        val failing = listOf(
            notificationDelivery(false, emptyList()),
            notificationDelivery(true, listOf("Standard")),
            notificationDelivery(true, listOf("Standard", "High", "Alarm")),
        )
        for (state in failing) {
            assertNotNull("$state should have a heading", state.cardTitle())
            assertNotNull("$state should have a body", state.cardBody())
        }
    }

    @Test
    fun `every tier resolves to its own distinct name resource`() {
        // Since §9 #19 this invariant is structural rather than asserted: the
        // Notifications & Alerts rows and this card both resolve the ids
        // tierNameFor() returns, so they cannot name a tier differently. What
        // is still worth pinning is that every tier has an id of its own -- a
        // copy-paste giving two tiers the same resource would make a blocked
        // tier warning name the wrong one, and nothing else would notice.
        val ids = com.hazia.emivita.core.data.entity.NotificationPriority.entries
            .map { ReminderNotificationChannels.tierNameFor(it) }
        assertEquals("one id per tier", ids.size, ids.toSet().size)
        assertTrue("no tier may resolve to 0", ids.all { it != 0 })
    }
}
