package com.hazia.emivita.feature.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What Today's progress line counts (§7 R135): the planned doses only, due before next, nothing when nothing is planned. */
class TodayProgressTest {

    private fun entry(status: TimelineStatus, reminderId: Long? = 1L, estimated: Boolean = false, armed: Boolean = false) =
        TimelineEntry(medicine = null, doseAmount = 1.0, status = status, doseEventId = null, resolvedAtEpochMillis = null, reminderId = reminderId, estimated = estimated, armed = armed)

    @Test fun `counts the planned doses and names the next one`() {
        val nodes = listOf(
            TimelineNode(800, listOf(entry(TimelineStatus.TAKEN), entry(TimelineStatus.TAKEN, reminderId = null))),
            TimelineNode(1300, listOf(entry(TimelineStatus.SKIPPED))),
            TimelineNode(2045, listOf(entry(TimelineStatus.UPCOMING))),
            TimelineNode(2200, listOf(entry(TimelineStatus.UPCOMING))),
        )
        assertEquals("the logged extra is not in the plan", TodayProgress(taken = 1, planned = 4, dueNow = 0, nextAt = 2045), todayProgress(nodes))
    }

    @Test fun `a dose due now is counted as due, not as next`() {
        val nodes = listOf(TimelineNode(800, listOf(entry(TimelineStatus.PENDING))), TimelineNode(2045, listOf(entry(TimelineStatus.UPCOMING))))
        assertEquals(TodayProgress(taken = 0, planned = 2, dueNow = 1, nextAt = 2045), todayProgress(nodes))
    }

    @Test fun `a projected slot already past is due, not next, an estimate excepted (R140)`() {
        val nodes = listOf(
            TimelineNode(5, listOf(entry(TimelineStatus.UPCOMING, armed = true))),
            TimelineNode(6, listOf(entry(TimelineStatus.UPCOMING, estimated = true, armed = true))),
            TimelineNode(2045, listOf(entry(TimelineStatus.UPCOMING))),
        )
        assertEquals(TodayProgress(taken = 0, planned = 3, dueNow = 1, nextAt = 6, nextEstimated = true), todayProgress(nodes, now = 7))
        assertEquals("before its time it is next", TodayProgress(taken = 0, planned = 3, dueNow = 0, nextAt = 5), todayProgress(nodes, now = 4))
    }

    @Test fun `a past slot no alarm is armed for stays to come (R143)`() {
        // The walk's guess at an interval counted from the answer, while the open dose waits.
        val nodes = listOf(TimelineNode(5, listOf(entry(TimelineStatus.UPCOMING))))
        assertEquals(TodayProgress(taken = 0, planned = 1, dueNow = 0, nextAt = 5), todayProgress(nodes, now = 7))
    }

    @Test fun `every dose answered leaves no next`() {
        val nodes = listOf(TimelineNode(800, listOf(entry(TimelineStatus.TAKEN))), TimelineNode(2045, listOf(entry(TimelineStatus.SKIPPED))))
        assertEquals(TodayProgress(taken = 1, planned = 2, dueNow = 0, nextAt = null), todayProgress(nodes))
    }

    @Test fun `a projected follow-up is next with its estimate`() {
        val nodes = listOf(TimelineNode(900, listOf(entry(TimelineStatus.UPCOMING, estimated = true))))
        assertEquals(TodayProgress(taken = 0, planned = 1, dueNow = 0, nextAt = 900, nextEstimated = true), todayProgress(nodes))
    }

    @Test fun `a projection still running draws no line`() {
        val nodes = listOf(TimelineNode(800, listOf(entry(TimelineStatus.TAKEN))))
        assertNull("'1 of 1, nothing left' would be read from a pass not yet done", todayProgress(nodes, settled = false))
    }

    @Test fun `a pass in progress keeps the last whole count, so the line does not blink`() {
        val held = HeldProgress()
        assertNull("nothing whole yet", held.of(emptyList(), settled = false))
        val before = listOf(TimelineNode(800, listOf(entry(TimelineStatus.TAKEN))), TimelineNode(2045, listOf(entry(TimelineStatus.UPCOMING))))
        val whole = held.of(before, settled = true)
        assertEquals(TodayProgress(taken = 1, planned = 2, dueNow = 0, nextAt = 2045), whole)
        // Mid-pass: the 20:45 slot's medicine is not reached yet, so it is missing from the nodes.
        assertEquals(whole, held.of(listOf(TimelineNode(800, listOf(entry(TimelineStatus.TAKEN)))), settled = false))
        val after = listOf(TimelineNode(800, listOf(entry(TimelineStatus.TAKEN))), TimelineNode(2045, listOf(entry(TimelineStatus.TAKEN))))
        assertEquals(TodayProgress(taken = 2, planned = 2, dueNow = 0, nextAt = null), held.of(after, settled = true))
    }

    @Test fun `nothing planned draws no line`() {
        assertNull(todayProgress(emptyList()))
        assertNull(todayProgress(listOf(TimelineNode(800, listOf(entry(TimelineStatus.TAKEN, reminderId = null))))))
    }
}
