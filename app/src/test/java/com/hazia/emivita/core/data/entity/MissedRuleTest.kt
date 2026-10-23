package com.hazia.emivita.core.data.entity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The owner's rule for the figures (§7 R121, 2026-10-23): "If midnight passes
 * without the dose being taken and/or if the dose is skipped, count as
 * missed". Midnight is the local one that ends the dose's scheduled day. A
 * fixed zone and clock throughout.
 */
class MissedRuleTest {
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private fun at(iso: String): Long = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    private fun dose(status: DoseStatus, scheduled: String) = DoseEvent(
        reminderId = 1, medicineId = 1, scheduledEpochMillis = at(scheduled), doseAmount = 1.0,
        status = status, createdAtEpochMillis = 0,
    )

    @Test
    fun `an unanswered dose is not missed before its day's midnight`() {
        val pending = dose(DoseStatus.PENDING, "2026-09-14T08:00")
        assertFalse(pending.isMissedInFiguresAt(at("2026-09-14T08:00"), zone))
        assertFalse(pending.isMissedInFiguresAt(at("2026-09-14T23:59:59.999"), zone))
    }

    @Test
    fun `an unanswered dose is missed from midnight on`() {
        val pending = dose(DoseStatus.PENDING, "2026-09-14T23:30")
        assertTrue(pending.isMissedInFiguresAt(at("2026-09-15T00:00"), zone))
        assertTrue(pending.isMissedInFiguresAt(at("2026-10-14T12:00"), zone))
    }

    @Test
    fun `on a clock-change day midnight is the local one`() {
        // 25 October 2026 has 25 hours in Rome: 24 hours after its 00:00 is
        // 23:00 the same day, still before the midnight that ends it.
        val autumn = dose(DoseStatus.PENDING, "2026-10-25T22:30")
        assertFalse(autumn.isMissedInFiguresAt(at("2026-10-25T23:30"), zone))
        assertTrue(autumn.isMissedInFiguresAt(at("2026-10-26T00:00"), zone))
        // 29 March 2026 has 23 hours: its midnight comes 23 hours after 00:00.
        val spring = dose(DoseStatus.PENDING, "2026-03-29T08:00")
        assertTrue(spring.isMissedInFiguresAt(at("2026-03-30T00:00"), zone))
        assertFalse(spring.isMissedInFiguresAt(at("2026-03-30T00:00") - 1, zone))
    }

    @Test
    fun `a skipped dose is missed at once`() {
        val skipped = dose(DoseStatus.SKIPPED, "2026-09-14T08:00")
        assertTrue(skipped.isMissedInFiguresAt(at("2026-09-14T08:05"), zone))
    }

    @Test
    fun `a taken dose is never missed`() {
        val taken = dose(DoseStatus.TAKEN, "2026-09-14T08:00")
        assertFalse(taken.isMissedInFiguresAt(at("2026-09-20T08:00"), zone))
    }
}
