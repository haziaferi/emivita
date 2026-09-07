package com.hazia.emivita.core.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("One reminder tier is blocked", state.cardTitle())
        assertTrue("the body should name the tier", state.cardBody()!!.contains("Alarm"))
    }

    @Test
    fun `the heading and the body both agree about how many tiers are blocked`() {
        val one = notificationDelivery(true, listOf("Alarm"))
        val two = notificationDelivery(true, listOf("Standard", "Alarm"))

        assertTrue(one.cardTitle()!!.startsWith("One"))
        assertTrue(two.cardTitle()!!.startsWith("Some"))
        // Singular/plural has to hold in the body too, not just the heading.
        assertTrue(one.cardBody()!!.contains("that tier"))
        assertTrue(two.cardBody()!!.contains("one of those tiers"))
        assertTrue("both tiers should be named", two.cardBody()!!.contains("Standard and Alarm"))
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
    fun `tier names match the rows the Notifications and Alerts screen prints`() {
        // blockedTiers() maps through tierNameFor(), and the card's body then
        // names a tier the user has to find in Android's settings. If these
        // drift from the screen's own NotificationTierRow labels, the warning
        // names something the user cannot see.
        assertEquals(
            listOf("Standard", "High", "Alarm"),
            com.hazia.emivita.core.data.entity.NotificationPriority.entries
                .map { ReminderNotificationChannels.tierNameFor(it) },
        )
    }
}
