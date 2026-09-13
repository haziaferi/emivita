package com.hazia.emivita.feature.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The as-needed ceiling rule (spec §9 #26; Appendix F PT-01), pure. */
class PrnHeadroomTest {
    private val hour = 3_600_000L

    @Test
    fun `under the ceiling there is room and no return time`() {
        val h = prnHeadroom(listOf(10 * hour, 12 * hour), max = 3, windowMillis = 24 * hour)
        assertEquals(2, h.taken); assertEquals(3, h.max)
        assertFalse(h.atCeiling); assertNull(h.headroomReturnsAtEpochMillis)
    }

    @Test
    fun `at the ceiling headroom returns when the oldest counted dose ages out`() {
        val h = prnHeadroom(listOf(12 * hour, 10 * hour, 15 * hour), max = 3, windowMillis = 24 * hour)
        assertTrue(h.atCeiling)
        assertEquals("the 10:00 dose leaves the window at 10:00 + 24 h", 34 * hour, h.headroomReturnsAtEpochMillis)
    }

    @Test
    fun `over the ceiling it is the dose that brings the count back under max that matters`() {
        // four taken, max three: room returns when two have aged out, i.e. when the second-oldest leaves
        val h = prnHeadroom(listOf(1 * hour, 2 * hour, 3 * hour, 4 * hour), max = 3, windowMillis = 6 * hour)
        assertEquals(4, h.taken); assertTrue(h.atCeiling)
        assertEquals(2 * hour + 6 * hour, h.headroomReturnsAtEpochMillis)
    }

    @Test
    fun `an empty window is never at the ceiling`() {
        val h = prnHeadroom(emptyList(), max = 1, windowMillis = hour)
        assertFalse(h.atCeiling); assertNull(h.headroomReturnsAtEpochMillis)
    }
}
