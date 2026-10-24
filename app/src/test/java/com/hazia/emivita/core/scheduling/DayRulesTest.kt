package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.config.nextDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The day rules that combine (§7 R127, A-10 + A-13 V2): the scoring rig's three hand cases
 * (`P2DayRulesMeasureTest.calculator`, 3/3 agreed), here on a fixed date so each answer is written
 * out by hand, plus the stored shape.
 */
class DayRulesTest {
    private val zone = ZoneOffset.UTC

    /** Friday 23 October 2026 at noon: 08:00 has passed. */
    private val today = LocalDate.of(2026, 10, 23)
    private val noon = today.atTime(12, 0).toInstant(zone).toEpochMilli()

    private fun next(pattern: RepeatPattern): LocalDate? =
        nextFixedTimeOccurrence(ReminderConfig.FixedTime(8, 0, 1.0, pattern), noon, zone)
            ?.let { Instant.ofEpochMilli(it.epochMillis).atZone(zone).toLocalDate() }

    @Test
    fun `days 1 and 15 fire next on the 1st of November`() {
        assertEquals(LocalDate.of(2026, 11, 1), next(RepeatPattern.DayRules(daysOfMonth = setOf(1, 15))))
    }

    @Test
    fun `Monday Wednesday Friday inside a 21 on 7 off cycle skip the pause week`() {
        // The cycle starts today: 23 Oct to 12 Nov are on, 13 to 19 Nov off, 20 Nov on again.
        val rules = RepeatPattern.DayRules(weekdays = setOf(1, 3, 5), cycle = RepeatPattern.CyclicActivePause(today.toEpochDay(), 21, 7))
        assertEquals("Monday 26 October is the first", LocalDate.of(2026, 10, 26), next(rules))
        val dates = rules.nextDates(LocalDate.of(2026, 11, 11), 3)
        assertEquals("Wed 11 Nov, then nothing in the pause until Fri 20 and Mon 23", listOf(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 23)), dates)
    }

    @Test
    fun `a weekday the cycle is never on never fires`() {
        // A 1-on 6-off cycle from today is on only on Fridays; Thursday alone never meets it.
        val never = RepeatPattern.DayRules(weekdays = setOf(4), cycle = RepeatPattern.CyclicActivePause(today.toEpochDay(), 1, 6))
        assertNull(next(never))
        assertEquals(emptyList<LocalDate>(), never.nextDates(today, 3))
    }

    @Test
    fun `the 31st with a weekday finds the months where they meet`() {
        // Saturday the 31st: October 2026, July 2027, March 2029.
        val rules = RepeatPattern.DayRules(weekdays = setOf(6), daysOfMonth = setOf(31))
        assertEquals(listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2027, 7, 31), LocalDate.of(2029, 3, 31)), rules.nextDates(today, 3))
    }

    @Test
    fun `the rules round-trip as JSON and an unused rule stays null`() {
        val config = ReminderConfig.FixedTime(8, 0, 1.0, RepeatPattern.DayRules(weekdays = setOf(1), daysOfMonth = setOf(1, 15)))
        val back = decodeReminderConfig(encodeReminderConfig(config)) as ReminderConfig.FixedTime
        assertEquals(config, back)
        assertNull((back.repeatPattern as RepeatPattern.DayRules).cycle)
    }
}
