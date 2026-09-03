package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.isActiveOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Coverage for the pure scheduling math in ReminderScheduling.kt -- the R3/R23
 * follow-up the file's own header asks for ("this file is exactly the kind of
 * logic that should have tests before anything else does"). No Android
 * dependencies, so plain JUnit: no Robolectric runner, no mocking.
 *
 * Everything runs in a fixed non-UTC zone on purpose. A UTC-only suite cannot
 * catch an offset bug, and two of the cases below (the DST pair) only exist
 * because local wall-clock time and elapsed absolute time diverge.
 *
 * Dates are chosen so the weekday is readable from the constant: 2026-06-01 is
 * a Monday, so 06-06 is a Saturday and 06-07 a Sunday. 2026-03-29 is the
 * European spring-forward date (02:00 -> 03:00).
 */
class ReminderSchedulingTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")

    /** "2026-06-01T08:00" -> epoch millis in [zone]. */
    private fun at(isoLocal: String): Long =
        LocalDateTime.parse(isoLocal).atZone(zone).toInstant().toEpochMilli()

    /** Inverse of [at], so a failure message reads as a wall clock rather than
     *  as a 13-digit number. */
    private fun Long.asLocal(): LocalDateTime =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

    private fun assertOccursAt(expectedIsoLocal: String, actual: NextOccurrence?) {
        assertEquals(LocalDateTime.parse(expectedIsoLocal), actual?.epochMillis?.asLocal())
    }

    private fun fixedTime(
        hour: Int = 8,
        minute: Int = 0,
        dose: Double = 1.0,
        repeatPattern: RepeatPattern = RepeatPattern.Daily,
    ) = ReminderConfig.FixedTime(hour = hour, minute = minute, dose = dose, repeatPattern = repeatPattern)

    private fun dailyWindow(
        intervalMinutes: Int = 240,
        dose: Double = 1.0,
        startHour: Int = 8,
        startMinute: Int = 0,
        endHour: Int = 20,
        endMinute: Int = 0,
        startBehavior: IntervalStartBehavior = IntervalStartBehavior.ON_REMINDED,
    ) = ReminderConfig.DailyWindow(
        intervalMinutes = intervalMinutes,
        dose = dose,
        windowStartHour = startHour,
        windowStartMinute = startMinute,
        windowEndHour = endHour,
        windowEndMinute = endMinute,
        startBehavior = startBehavior,
    )

    // ---- FixedTime: Daily ------------------------------------------------

    @Test
    fun `daily returns the slot later the same day when it is still ahead`() {
        val next = nextFixedTimeOccurrence(fixedTime(), at("2026-06-01T06:00"), zone)
        assertOccursAt("2026-06-01T08:00", next)
    }

    @Test
    fun `daily rolls to the next day once the slot has passed`() {
        val next = nextFixedTimeOccurrence(fixedTime(), at("2026-06-01T09:00"), zone)
        assertOccursAt("2026-06-02T08:00", next)
    }

    @Test
    fun `daily treats notBefore exactly on the slot as still due`() {
        // "at or after", not "strictly after" -- the boundary the
        // fired-alarm path depends on when it passes firedAt + 60_000.
        val next = nextFixedTimeOccurrence(fixedTime(), at("2026-06-01T08:00"), zone)
        assertOccursAt("2026-06-01T08:00", next)
    }

    @Test
    fun `fixed time carries the configured dose through`() {
        val next = nextFixedTimeOccurrence(fixedTime(dose = 2.5), at("2026-06-01T06:00"), zone)
        assertEquals(2.5, next!!.doseAmount, 0.0)
    }

    // ---- FixedTime: SpecificDaysOfWeek -----------------------------------

    @Test
    fun `specific days picks the next listed weekday`() {
        // Wed + Fri, asked on a Monday.
        val config = fixedTime(repeatPattern = RepeatPattern.SpecificDaysOfWeek(setOf(3, 5)))
        assertOccursAt("2026-06-03T08:00", nextFixedTimeOccurrence(config, at("2026-06-01T09:00"), zone))
    }

    @Test
    fun `specific days wraps a full week when the only slot has passed`() {
        // Monday-only, asked Monday evening -- next is the following Monday.
        val config = fixedTime(repeatPattern = RepeatPattern.SpecificDaysOfWeek(setOf(1)))
        assertOccursAt("2026-06-08T08:00", nextFixedTimeOccurrence(config, at("2026-06-01T20:00"), zone))
    }

    @Test
    fun `specific days with an empty day set returns null instead of looping`() {
        val config = fixedTime(repeatPattern = RepeatPattern.SpecificDaysOfWeek(emptySet()))
        assertNull(nextFixedTimeOccurrence(config, at("2026-06-01T06:00"), zone))
    }

    // ---- FixedTime: EveryNDays -------------------------------------------

    @Test
    fun `every n days snaps forward onto the grid`() {
        // Anchored 06-01, every 3 days => 06-01, 06-04, 06-07. Asked on 06-03.
        val config = fixedTime(
            repeatPattern = RepeatPattern.EveryNDays(n = 3, startEpochDay = LocalDate.parse("2026-06-01").toEpochDay()),
        )
        assertOccursAt("2026-06-04T08:00", nextFixedTimeOccurrence(config, at("2026-06-03T09:00"), zone))
    }

    @Test
    fun `every n days before the anchor returns the anchor date itself`() {
        val config = fixedTime(
            repeatPattern = RepeatPattern.EveryNDays(n = 3, startEpochDay = LocalDate.parse("2026-06-10").toEpochDay()),
        )
        assertOccursAt("2026-06-10T08:00", nextFixedTimeOccurrence(config, at("2026-06-01T09:00"), zone))
    }

    @Test
    fun `every n days with n below 1 returns null rather than throwing`() {
        // R23 regression guard. This used to be a require(n >= 1), which threw
        // from inside the projection cache's recompute loop -- on every pass,
        // for a row that was already committed. Degrading to "no next
        // occurrence" is what every caller already handles.
        val startEpochDay = LocalDate.parse("2026-06-01").toEpochDay()
        val notBefore = at("2026-06-01T06:00")
        assertNull(nextFixedTimeOccurrence(fixedTime(repeatPattern = RepeatPattern.EveryNDays(0, startEpochDay)), notBefore, zone))
        assertNull(nextFixedTimeOccurrence(fixedTime(repeatPattern = RepeatPattern.EveryNDays(-1, startEpochDay)), notBefore, zone))
    }

    // ---- FixedTime: CyclicActivePause ------------------------------------

    @Test
    fun `cyclic pattern skips the pause stretch`() {
        // 21 active from 06-01 (06-01..06-21), 7 paused (06-22..06-28),
        // next cycle opens 06-29. Asked mid-pause.
        val config = fixedTime(
            repeatPattern = RepeatPattern.CyclicActivePause(
                firstCycleStartEpochDay = LocalDate.parse("2026-06-01").toEpochDay(),
                activeDays = 21,
                pauseDays = 7,
            ),
        )
        assertOccursAt("2026-06-29T08:00", nextFixedTimeOccurrence(config, at("2026-06-23T09:00"), zone))
    }

    @Test
    fun `cyclic pattern is periodic before the first cycle start`() {
        // Documented floorMod behavior: the cycle runs backwards from the
        // anchor too. 2026-05-01 is 31 days before the anchor; floorMod(-31,
        // 28) = 25, inside the pause, and the next active day back there is
        // 05-04 (offset -28, i.e. exactly one whole cycle before the anchor).
        val config = fixedTime(
            repeatPattern = RepeatPattern.CyclicActivePause(
                firstCycleStartEpochDay = LocalDate.parse("2026-06-01").toEpochDay(),
                activeDays = 21,
                pauseDays = 7,
            ),
        )
        assertOccursAt("2026-05-04T08:00", nextFixedTimeOccurrence(config, at("2026-05-01T09:00"), zone))
    }

    @Test
    fun `cyclic pattern with a degenerate cycle returns null rather than throwing`() {
        // Same defect shape as the EveryNDays case above, and reachable the
        // same way: AddReminderScreen gates activeDays >= 1 / pauseDays >= 0,
        // but BackupRepository.restoreAll() writes reminder rows straight back
        // with no config revalidation, so a hand-edited or corrupted backup
        // lands a bad row that the projection loop then re-reads every pass.
        val anchor = LocalDate.parse("2026-06-01").toEpochDay()
        val notBefore = at("2026-06-01T06:00")
        // activeDays = 0 with a non-zero cycle length: isActiveOn's
        // require(activeDays >= 1) is what used to blow up here.
        assertNull(nextFixedTimeOccurrence(fixedTime(repeatPattern = RepeatPattern.CyclicActivePause(anchor, 0, 7)), notBefore, zone))
        // Negative pause days, cycle length still >= 1.
        assertNull(nextFixedTimeOccurrence(fixedTime(repeatPattern = RepeatPattern.CyclicActivePause(anchor, 2, -1)), notBefore, zone))
        // Cycle length exactly zero -- already returned null before the fix,
        // via the empty (0 until 0) range. Pinned so it stays that way.
        assertNull(nextFixedTimeOccurrence(fixedTime(repeatPattern = RepeatPattern.CyclicActivePause(anchor, 0, 0)), notBefore, zone))
    }

    @Test
    fun `isActiveOn floor-mods dates before the anchor`() {
        val pattern = RepeatPattern.CyclicActivePause(firstCycleStartEpochDay = 100, activeDays = 21, pauseDays = 7)
        assertTrue("exactly one cycle before the anchor is active", pattern.isActiveOn(100 - 28))
        assertFalse("three days before the anchor falls in the pause stretch", pattern.isActiveOn(100 - 3))
        assertTrue(pattern.isActiveOn(100))
        assertTrue(pattern.isActiveOn(120))
        assertFalse(pattern.isActiveOn(121))
    }

    // ---- FixedTime: daylight saving --------------------------------------

    @Test
    fun `fixed time inside the spring-forward gap shifts to the first real instant`() {
        // 2026-03-29 02:30 does not exist in Europe/Rome. ZonedDateTime
        // resolves the gap forward, so the reminder fires at 03:30 rather
        // than being skipped for the day.
        val next = nextFixedTimeOccurrence(fixedTime(hour = 2, minute = 30), at("2026-03-29T00:00"), zone)
        assertOccursAt("2026-03-29T03:30", next)
    }

    // ---- RepeatingInterval ------------------------------------------------

    private fun repeatingInterval(
        intervalMinutes: Int = 480,
        dose: Double = 1.0,
        startBehavior: IntervalStartBehavior = IntervalStartBehavior.ON_REMINDED,
        anchorEpochMillis: Long,
    ) = ReminderConfig.RepeatingInterval(
        intervalMinutes = intervalMinutes,
        dose = dose,
        startBehavior = startBehavior,
        anchorEpochMillis = anchorEpochMillis,
    )

    @Test
    fun `repeating interval stays on the anchored grid when an alarm fires late`() {
        // The self-correcting property the KDoc claims: a 5-minute-late firing
        // must not drag the next slot to 00:05.
        val config = repeatingInterval(anchorEpochMillis = at("2026-06-01T08:00"))
        val next = nextRepeatingIntervalOccurrence(config, at("2026-06-01T16:05"), config.anchorEpochMillis)
        assertOccursAt("2026-06-02T00:00", next)
    }

    @Test
    fun `repeating interval returns a grid instant asked for exactly`() {
        val config = repeatingInterval(anchorEpochMillis = at("2026-06-01T08:00"))
        assertOccursAt(
            "2026-06-01T16:00",
            nextRepeatingIntervalOccurrence(config, at("2026-06-01T16:00"), config.anchorEpochMillis),
        )
    }

    @Test
    fun `repeating interval before the anchor returns the anchor`() {
        val config = repeatingInterval(anchorEpochMillis = at("2026-06-01T08:00"))
        assertOccursAt(
            "2026-06-01T08:00",
            nextRepeatingIntervalOccurrence(config, at("2026-06-01T06:00"), config.anchorEpochMillis),
        )
    }

    @Test
    fun `repeating interval is absolute time, so a 24h grid shifts an hour across DST`() {
        // Deliberate and documented: the interval grid is elapsed-time math,
        // not wall-clock math. A 24h interval anchored the day before
        // spring-forward lands an hour later on the clock afterwards. Pinned
        // so that changing it to wall-clock stepping is a conscious decision.
        val config = repeatingInterval(intervalMinutes = 1440, anchorEpochMillis = at("2026-03-28T12:00"))
        assertOccursAt(
            "2026-03-29T13:00",
            nextRepeatingIntervalOccurrence(config, at("2026-03-29T00:00"), config.anchorEpochMillis),
        )
    }

    @Test
    fun `repeating interval below one minute is rejected at the calculator`() {
        val config = repeatingInterval(intervalMinutes = 0, anchorEpochMillis = at("2026-06-01T08:00"))
        assertThrows(IllegalArgumentException::class.java) {
            nextRepeatingIntervalOccurrence(config, at("2026-06-01T09:00"), config.anchorEpochMillis)
        }
    }

    // ---- DailyWindow ------------------------------------------------------

    @Test
    fun `daily window returns the window start when asked before it opens`() {
        assertOccursAt("2026-06-01T08:00", nextDailyWindowOccurrence(dailyWindow(), at("2026-06-01T06:00"), null, zone))
    }

    @Test
    fun `daily window steps onto the interval grid inside the window`() {
        // 08:00 + 4h grid => 08:00, 12:00, 16:00, 20:00. Asked at 13:00.
        assertOccursAt("2026-06-01T16:00", nextDailyWindowOccurrence(dailyWindow(), at("2026-06-01T13:00"), null, zone))
    }

    @Test
    fun `daily window rolls to the next day once the window is exhausted`() {
        assertOccursAt("2026-06-02T08:00", nextDailyWindowOccurrence(dailyWindow(), at("2026-06-01T21:00"), null, zone))
    }

    @Test
    fun `daily window includes the closing instant of the window`() {
        assertOccursAt("2026-06-01T20:00", nextDailyWindowOccurrence(dailyWindow(), at("2026-06-01T19:30"), null, zone))
    }

    @Test
    fun `on-resolved daily window restarts the grid from the resolution`() {
        val config = dailyWindow(startBehavior = IntervalStartBehavior.ON_RESOLVED)
        val next = nextDailyWindowOccurrence(config, at("2026-06-01T09:30"), at("2026-06-01T09:30"), zone)
        assertOccursAt("2026-06-01T13:30", next)
    }

    @Test
    fun `a resolution from a previous day does not leak into the grid for today`() {
        // The KDoc's explicit promise. Yesterday's 09:30 resolution must not
        // put today's first slot at 13:30.
        val config = dailyWindow(startBehavior = IntervalStartBehavior.ON_RESOLVED)
        val next = nextDailyWindowOccurrence(config, at("2026-06-01T06:00"), at("2026-05-31T09:30"), zone)
        assertOccursAt("2026-06-01T08:00", next)
    }

    @Test
    fun `on-reminded daily window ignores the last resolution entirely`() {
        val config = dailyWindow(startBehavior = IntervalStartBehavior.ON_REMINDED)
        val next = nextDailyWindowOccurrence(config, at("2026-06-01T09:30"), at("2026-06-01T09:30"), zone)
        assertOccursAt("2026-06-01T12:00", next)
    }

    @Test
    fun `daily window with the end before the start returns null instead of crashing`() {
        val config = dailyWindow(startHour = 20, endHour = 8)
        assertNull(nextDailyWindowOccurrence(config, at("2026-06-01T06:00"), null, zone))
    }

    @Test
    fun `daily window below one minute of interval is rejected at the calculator`() {
        assertThrows(IllegalArgumentException::class.java) {
            nextDailyWindowOccurrence(dailyWindow(intervalMinutes = 0), at("2026-06-01T06:00"), null, zone)
        }
    }

    // ---- Weekend mode -----------------------------------------------------

    private val weekendDays = setOf(6, 7) // Sat, Sun

    @Test
    fun `weekend mode pushes an in-window occurrence to the window end`() {
        val occurrence = NextOccurrence(at("2026-06-06T09:00"), 1.0)
        val pushed = applyWeekendMode(occurrence, weekendDays, LocalTime.of(8, 0), LocalTime.of(10, 0), zone)
        assertEquals(LocalDateTime.parse("2026-06-06T10:00"), pushed.epochMillis.asLocal())
        assertEquals("the dose must survive the push", 1.0, pushed.doseAmount, 0.0)
    }

    @Test
    fun `weekend mode treats the window start as inclusive and the end as exclusive`() {
        val start = LocalTime.of(8, 0)
        val end = LocalTime.of(10, 0)
        val onStart = applyWeekendMode(NextOccurrence(at("2026-06-06T08:00"), 1.0), weekendDays, start, end, zone)
        assertEquals(LocalDateTime.parse("2026-06-06T10:00"), onStart.epochMillis.asLocal())
        val onEnd = applyWeekendMode(NextOccurrence(at("2026-06-06T10:00"), 1.0), weekendDays, start, end, zone)
        assertEquals(LocalDateTime.parse("2026-06-06T10:00"), onEnd.epochMillis.asLocal())
    }

    @Test
    fun `weekend mode leaves an occurrence outside the window untouched`() {
        val occurrence = NextOccurrence(at("2026-06-06T11:00"), 1.0)
        assertEquals(occurrence, applyWeekendMode(occurrence, weekendDays, LocalTime.of(8, 0), LocalTime.of(10, 0), zone))
    }

    @Test
    fun `weekend mode leaves a non-weekend day untouched`() {
        val occurrence = NextOccurrence(at("2026-06-01T09:00"), 1.0) // Monday
        assertEquals(occurrence, applyWeekendMode(occurrence, weekendDays, LocalTime.of(8, 0), LocalTime.of(10, 0), zone))
    }

    @Test
    fun `an empty weekend day set is a no-op regardless of the times`() {
        val occurrence = NextOccurrence(at("2026-06-06T09:00"), 1.0)
        assertEquals(occurrence, applyWeekendMode(occurrence, emptySet(), LocalTime.of(8, 0), LocalTime.of(10, 0), zone))
    }

    @Test
    fun `an overnight window pushes the evening side to the next morning`() {
        // 22:00-06:00, Saturday 23:00 -> Sunday 06:00.
        val occurrence = NextOccurrence(at("2026-06-06T23:00"), 1.0)
        val pushed = applyWeekendMode(occurrence, weekendDays, LocalTime.of(22, 0), LocalTime.of(6, 0), zone)
        assertEquals(LocalDateTime.parse("2026-06-07T06:00"), pushed.epochMillis.asLocal())
    }

    @Test
    fun `an overnight window judges the after-midnight side by the previous day`() {
        // Sunday 02:00 belongs to the window that opened Saturday 22:00, so
        // Saturday's membership governs and the push lands the same morning.
        val occurrence = NextOccurrence(at("2026-06-07T02:00"), 1.0)
        val pushed = applyWeekendMode(occurrence, weekendDays, LocalTime.of(22, 0), LocalTime.of(6, 0), zone)
        assertEquals(LocalDateTime.parse("2026-06-07T06:00"), pushed.epochMillis.asLocal())
    }

    @Test
    fun `an overnight window spares an after-midnight occurrence following a weekday`() {
        // Tuesday 02:00 -- the window that opened Monday 22:00, and Monday is
        // not a weekend day. Reading today's membership instead would have
        // wrongly pushed this.
        val occurrence = NextOccurrence(at("2026-06-02T02:00"), 1.0)
        assertEquals(occurrence, applyWeekendMode(occurrence, weekendDays, LocalTime.of(22, 0), LocalTime.of(6, 0), zone))
    }

    @Test
    fun `an overnight window leaves the middle of the day untouched`() {
        val occurrence = NextOccurrence(at("2026-06-06T12:00"), 1.0)
        assertEquals(occurrence, applyWeekendMode(occurrence, weekendDays, LocalTime.of(22, 0), LocalTime.of(6, 0), zone))
    }

    @Test
    fun `a zero-length weekend window is a no-op`() {
        val occurrence = NextOccurrence(at("2026-06-06T09:00"), 1.0)
        assertEquals(occurrence, applyWeekendMode(occurrence, weekendDays, LocalTime.of(9, 0), LocalTime.of(9, 0), zone))
    }

    // ---- Shared config helpers --------------------------------------------

    @Test
    fun `doseAmountOrNull is null exactly for the two alert types`() {
        assertEquals(1.5, fixedTime(dose = 1.5).doseAmountOrNull()!!, 0.0)
        assertEquals(
            2.0,
            ReminderConfig.RepeatingInterval(60, 2.0, IntervalStartBehavior.ON_REMINDED, 0L).doseAmountOrNull()!!,
            0.0,
        )
        assertEquals(3.0, dailyWindow(dose = 3.0).doseAmountOrNull()!!, 0.0)
        assertEquals(
            4.0,
            ReminderConfig.LinkedFollowing(30, 4.0, IntervalStartBehavior.ON_REMINDED).doseAmountOrNull()!!,
            0.0,
        )
        assertNull(ReminderConfig.LowStockAlert(5.0, LowStockTriggerMode.ONCE_WHEN_BELOW).doseAmountOrNull())
        assertNull(ReminderConfig.ExpirationAlert(7, ExpirationTriggerFrequency.ONCE).doseAmountOrNull())
    }

    @Test
    fun `only fixed-time and on-reminded grids re-arm the moment they fire`() {
        assertTrue(fixedTime().reschedulesImmediatelyOnFire())
        assertTrue(
            ReminderConfig.RepeatingInterval(60, 1.0, IntervalStartBehavior.ON_REMINDED, 0L).reschedulesImmediatelyOnFire(),
        )
        assertFalse(
            "re-arming an ON_RESOLVED interval would schedule the next dose before this one is answered",
            ReminderConfig.RepeatingInterval(60, 1.0, IntervalStartBehavior.ON_RESOLVED, 0L).reschedulesImmediatelyOnFire(),
        )
        assertTrue(dailyWindow(startBehavior = IntervalStartBehavior.ON_REMINDED).reschedulesImmediatelyOnFire())
        assertFalse(dailyWindow(startBehavior = IntervalStartBehavior.ON_RESOLVED).reschedulesImmediatelyOnFire())
        // LinkedFollowing is false for BOTH start behaviors -- it has no slot
        // of its own to re-arm, the cascade trigger reschedules it instead.
        assertFalse(
            ReminderConfig.LinkedFollowing(30, 1.0, IntervalStartBehavior.ON_REMINDED).reschedulesImmediatelyOnFire(),
        )
        assertFalse(
            ReminderConfig.LinkedFollowing(30, 1.0, IntervalStartBehavior.ON_RESOLVED).reschedulesImmediatelyOnFire(),
        )
        assertFalse(ReminderConfig.LowStockAlert(5.0, LowStockTriggerMode.ONCE_WHEN_BELOW).reschedulesImmediatelyOnFire())
        assertFalse(ReminderConfig.ExpirationAlert(7, ExpirationTriggerFrequency.ONCE).reschedulesImmediatelyOnFire())
    }
}
