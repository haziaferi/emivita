package com.hazia.emivita.feature.today

import com.hazia.emivita.core.data.entity.Medicine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Log dose's second-dose check (§7 R133, the owner: "Warn + 'count as'"): a scheduled medicine already taken today is
 * warned about; one with today's dose still open is offered "count as that dose"; nothing else is asked.
 */
class SecondDoseCheckTest {
    private val met = Medicine(id = 1, profileId = 1, name = "Metformina", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0)
    private val other = Medicine(id = 2, profileId = 1, name = "Levotiroxina", iconKey = "pill", sortOrder = 1.0, createdAtEpochMillis = 0)
    private val h = 3_600_000L

    private fun node(at: Long, m: Medicine, status: TimelineStatus, resolvedAt: Long? = null, reminderId: Long? = 10L, estimated: Boolean = false) =
        TimelineNode(at, listOf(TimelineEntry(m, 1.0, status, doseEventId = null, resolvedAtEpochMillis = resolvedAt, reminderId = reminderId, estimated = estimated)))

    @Test fun taken_today_and_nothing_open_warns() {
        val c = secondDoseCheck(1, listOf(node(8 * h, met, TimelineStatus.TAKEN, resolvedAt = 8 * h + 120_000)), now = 12 * h)
        assertEquals(8 * h + 120_000, c?.takenTodayAt)
        assertNull("nothing to count as", c?.openSlot)
    }

    @Test fun before_the_slot_offers_count_as_that_dose() {
        val c = secondDoseCheck(1, listOf(node(8 * h, met, TimelineStatus.UPCOMING, reminderId = 11)), now = 7 * h)
        assertEquals(OpenSlot(11, 8 * h, 1.0), c?.openSlot)
        assertNull(c?.takenTodayAt)
    }

    @Test fun taken_in_the_morning_with_the_evening_open_offers_the_evening() {
        val nodes = listOf(
            node(8 * h, met, TimelineStatus.TAKEN, resolvedAt = 8 * h),
            node(20 * h, met, TimelineStatus.UPCOMING, reminderId = 12),
        )
        val c = secondDoseCheck(1, nodes, now = 19 * h)
        assertEquals(8 * h, c?.takenTodayAt)
        assertEquals(12L, c?.openSlot?.reminderId)
    }

    @Test fun the_nearest_open_slot_is_offered_and_estimates_are_not() {
        val nodes = listOf(
            node(8 * h, met, TimelineStatus.PENDING, reminderId = 11),
            node(9 * h, met, TimelineStatus.UPCOMING, reminderId = 13, estimated = true),
            node(20 * h, met, TimelineStatus.UPCOMING, reminderId = 12),
        )
        assertEquals("due at 08:00, nearest to 10:00", 11L, secondDoseCheck(1, nodes, now = 10 * h)?.openSlot?.reminderId)
    }

    @Test fun another_medicine_or_a_skip_says_nothing() {
        val nodes = listOf(node(8 * h, other, TimelineStatus.TAKEN, resolvedAt = 8 * h), node(9 * h, met, TimelineStatus.SKIPPED))
        assertNull(secondDoseCheck(1, nodes, now = 12 * h))
    }
}
