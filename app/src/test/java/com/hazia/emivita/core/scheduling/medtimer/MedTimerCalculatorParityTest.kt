package com.hazia.emivita.core.scheduling.medtimer

import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.scheduling.NextOccurrence
import com.hazia.emivita.core.scheduling.applyWeekendMode
import com.hazia.emivita.core.scheduling.nextDailyWindowOccurrence
import com.hazia.emivita.core.scheduling.nextExpirationAlertOccurrence
import com.hazia.emivita.core.scheduling.nextFixedTimeOccurrence
import com.hazia.emivita.core.scheduling.nextLowStockAlertOccurrence
import com.hazia.emivita.core.scheduling.nextRepeatingIntervalOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * medTimer's scheduler tests, transliterated onto Emivita's pure calculators
 * (spec §7 R60, Appendix E). medTimer derives "next" from the event history;
 * Emivita derives it from `notBefore` plus an anchor, so each case below is
 * ported by *meaning* -- "the last event was at X, it is now Y, the next
 * reminder is Z" -- with the medTimer test named so the two can be read side
 * by side. Same calendar as medTimer's `TestHelper`: day 1 is 1970-01-01, a
 * Thursday, and `on(day, minutes)` is that day's wall clock in UTC.
 *
 * Three outcomes, all recorded in Appendix E's matrix: a case that passes is
 * parity; a case marked DESIGN asserts Emivita's documented behaviour where
 * it deliberately differs (with the row that decided it); a case that
 * failed on first run was a defect (R60) and now pins the fix. Nothing here
 * is `@Ignore`d.
 */
class MedTimerCalculatorParityTest {

    private val zone: ZoneId = ZoneId.of("Z")

    /** medTimer `TestHelper.on(day, minutes)`. */
    private fun on(day: Long, minutes: Long): Long = ((day - 1) * 86_400 + minutes * 60) * 1_000
    private fun onTZ(day: Long, minutes: Long, zoneId: String): Long =
        LocalDate.ofEpochDay(day - 1).atTime(LocalTime.of((minutes / 60).toInt(), (minutes % 60).toInt()))
            .atZone(ZoneId.of(zoneId)).toInstant().toEpochMilli()

    private fun assertAt(expected: Long, actual: NextOccurrence?) {
        assertEquals(
            Instant.ofEpochMilli(expected).atZone(zone).toLocalDateTime(),
            actual?.epochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() },
        )
    }

    private fun fixed(minutes: Int, pattern: RepeatPattern = RepeatPattern.Daily) =
        ReminderConfig.FixedTime(hour = minutes / 60, minute = minutes % 60, dose = 1.0, repeatPattern = pattern)

    private fun interval(minutes: Int, anchor: Long, start: IntervalStartBehavior) =
        ReminderConfig.RepeatingInterval(intervalMinutes = minutes, dose = 1.0, startBehavior = start, anchorEpochMillis = anchor)

    private fun window(minutes: Int, start: LocalTime, end: LocalTime, behavior: IntervalStartBehavior) =
        ReminderConfig.DailyWindow(
            intervalMinutes = minutes, dose = 1.0,
            windowStartHour = start.hour, windowStartMinute = start.minute,
            windowEndHour = end.hour, windowEndMinute = end.minute,
            startBehavior = behavior,
        )

    // ---- ReminderSchedulerUnitTest -----------------------------------------

    @Test
    fun `scheduleSameDayReminder -- created after today's slot rolls to tomorrow`() {
        // medTimer: reminder 08:00 created 08:20 -> on(2, 480)
        assertAt(on(2, 480), nextFixedTimeOccurrence(fixed(480), on(1, 500), zone))
    }

    @Test
    fun `scheduleReminderWithOneDayPause -- every second day after a raise`() {
        // medTimer: pauseDays = 1, raised day 1 -> day 3
        val everyOther = fixed(480, RepeatPattern.EveryNDays(n = 2, startEpochDay = 0))
        assertAt(on(1, 480), nextFixedTimeOccurrence(everyOther, on(1, 0), zone))
        assertAt(on(3, 480), nextFixedTimeOccurrence(everyOther, on(1, 481), zone))
    }

    @Test
    fun `scheduleCycleInFuture -- on and after the cycle start, parity`() {
        // medTimer: 1 on / 2 off, cycleStartDay epochDay 4 (= day 5): at day 5 -> day 5, at day 6 -> day 8,
        // and with day 8 already raised -> day 11.
        val cycle = fixed(480, RepeatPattern.CyclicActivePause(firstCycleStartEpochDay = 4, activeDays = 1, pauseDays = 2))
        assertAt(on(5, 480), nextFixedTimeOccurrence(cycle, on(5, 0), zone))
        assertAt(on(8, 480), nextFixedTimeOccurrence(cycle, on(6, 0), zone))
        assertAt(on(11, 480), nextFixedTimeOccurrence(cycle, on(8, 481), zone))
    }

    @Test
    fun `scheduleCycleInFuture -- before the cycle start, DESIGN divergence`() {
        // medTimer: nothing before cycleStartDay (StandardScheduling.kt:46 `dayInCycle >= 0`),
        // so at day 1 the first reminder is day 5. Emivita's CyclicActivePause is
        // periodic in both directions (ReminderConfig.kt, isActiveOn), so the
        // pause stretch of the "previous" cycle is projected backwards and the
        // first active day before the anchor is day 2. Appendix E row A-09.
        val cycle = fixed(480, RepeatPattern.CyclicActivePause(firstCycleStartEpochDay = 4, activeDays = 1, pauseDays = 2))
        assertAt(on(2, 480), nextFixedTimeOccurrence(cycle, on(1, 0), zone))
    }

    @Test
    fun `scheduleLongCycleInFuture -- 90 on, 20 off from day 5`() {
        // medTimer: at day 1 -> day 5, at day 5 -> day 5, at day 6 -> day 6.
        // Parity here is arithmetic, not design: days 1..4 fall in the backwards-
        // projected pause stretch (offsets 106..109 of a 110-day cycle).
        val cycle = fixed(480, RepeatPattern.CyclicActivePause(firstCycleStartEpochDay = 4, activeDays = 90, pauseDays = 20))
        assertAt(on(5, 480), nextFixedTimeOccurrence(cycle, on(1, 0), zone))
        assertAt(on(5, 480), nextFixedTimeOccurrence(cycle, on(5, 0), zone))
        assertAt(on(6, 480), nextFixedTimeOccurrence(cycle, on(6, 0), zone))
    }

    @Test
    fun `reminderOverMidnight -- a 23-45 dose taken after midnight still counts for its day`() {
        // medTimer: event raised day 1 23:46, processed day 2 00:01 -> next is day 2 23:45
        assertAt(on(2, 23 * 60 + 45), nextFixedTimeOccurrence(fixed(23 * 60 + 45), on(2, 1), zone))
    }

    @Test
    fun `reminderTomorrow -- events today and tomorrow push to the day after`() {
        assertAt(on(3, 16), nextFixedTimeOccurrence(fixed(16), on(2, 17), zone))
    }

    // ---- ReminderSchedulerDaysUnitTest --------------------------------------

    @Test
    fun `scheduleSkipWeekdays -- Friday and Saturday excluded, asked on Friday, answers Sunday`() {
        // 1970-01-01 is a Thursday; medTimer asks at day 2 (Friday) and expects day 4 (Sunday).
        val days = (DayOfWeek.entries - DayOfWeek.FRIDAY - DayOfWeek.SATURDAY).map { it.value }.toSet()
        assertAt(on(4, 480), nextFixedTimeOccurrence(fixed(480, RepeatPattern.SpecificDaysOfWeek(days)), on(2, 0), zone))
    }

    // ---- ReminderSchedulerIntervalUnitTest ----------------------------------

    @Test
    fun `scheduleIntervalReminder -- on the grid, then from the resolution`() {
        // medTimer: 8 h from day 1 02:00; after an event at day 2 02:00 -> day 2 10:00;
        // "starts from processed" with processed at day 2 02:01 -> day 2 10:01.
        val onReminded = interval(480, on(1, 120), IntervalStartBehavior.ON_REMINDED)
        assertAt(on(1, 120), nextRepeatingIntervalOccurrence(onReminded, on(1, 0), onReminded.anchorEpochMillis))
        assertAt(on(2, 600), nextRepeatingIntervalOccurrence(onReminded, on(2, 121), onReminded.anchorEpochMillis))
        val onResolved = interval(480, on(1, 120), IntervalStartBehavior.ON_RESOLVED)
        assertAt(on(2, 601), nextRepeatingIntervalOccurrence(onResolved, on(2, 122), on(2, 121)))
    }

    @Test
    fun `scheduleIntervalReminderPause -- three days later, today's first slot, not a burst`() {
        // medTimer `adjustToToday`: last event day 1 10:00, now day 4 -> day 4 02:00.
        val onReminded = interval(480, on(1, 120), IntervalStartBehavior.ON_REMINDED)
        assertAt(on(4, 120), nextRepeatingIntervalOccurrence(onReminded, on(4, 0), onReminded.anchorEpochMillis))
        val onResolved = interval(480, on(1, 120), IntervalStartBehavior.ON_RESOLVED)
        assertAt(on(4, 120), nextRepeatingIntervalOccurrence(onResolved, on(4, 0), on(1, 600)))
    }

    @Test
    fun `scheduleIntervalReminderNotTaken -- 23 h from a 01-00 resolution crosses midnight`() {
        val onResolved = interval(23 * 60, on(1, 120), IntervalStartBehavior.ON_RESOLVED)
        assertAt(on(3, 0), nextRepeatingIntervalOccurrence(onResolved, on(2, 61), on(2, 60)))
    }

    // ---- ReminderSchedulerWindowedIntervalUnitTest --------------------------

    private val twoToElevenForty = LocalTime.of(2, 0) to LocalTime.of(11, 40)

    @Test
    fun `scheduleWindowedIntervalReminder -- 02-00 to 11-40 every 8 h`() {
        val (start, end) = twoToElevenForty
        val onReminded = window(480, start, end, IntervalStartBehavior.ON_REMINDED)
        assertAt(on(1, 120), nextDailyWindowOccurrence(onReminded, on(1, 0), null, zone))
        assertAt(on(1, 600), nextDailyWindowOccurrence(onReminded, on(1, 121), null, zone))
        assertAt(on(2, 120), nextDailyWindowOccurrence(onReminded, on(1, 601), null, zone))
        val onResolved = window(480, start, end, IntervalStartBehavior.ON_RESOLVED)
        assertAt(on(2, 601), nextDailyWindowOccurrence(onResolved, on(2, 122), on(2, 121), zone))
        // 10:01 + 8 h is past the window -> next day's start
        assertAt(on(3, 120), nextDailyWindowOccurrence(onResolved, on(2, 602), on(2, 601), zone))
    }

    @Test
    fun `scheduleWindowedIntervalReminderPause -- three days later, today's window start`() {
        val (start, end) = twoToElevenForty
        val onReminded = window(480, start, end, IntervalStartBehavior.ON_REMINDED)
        assertAt(on(4, 120), nextDailyWindowOccurrence(onReminded, on(4, 0), on(1, 130), zone))
    }

    @Test
    fun `scheduleWindowedIntervalReminderCreated -- created inside the window, DESIGN divergence`() {
        // medTimer: created day 1 02:10 -> first reminder tomorrow 02:00 (its
        // scheduler is event-driven and would otherwise fire the passed slot at
        // once). Emivita keys on notBefore and fires the rest of today's grid:
        // 10:00. Appendix E row A-06; Emivita's is the behaviour a user who
        // adds a reminder at 02:10 expects.
        val (start, end) = twoToElevenForty
        val onReminded = window(480, start, end, IntervalStartBehavior.ON_REMINDED)
        assertAt(on(1, 600), nextDailyWindowOccurrence(onReminded, on(1, 130), null, zone))
    }

    @Test
    fun `scheduleWindowedIntervalReminderNextDay -- overnight window, DESIGN divergence`() {
        // medTimer chains 16:40 -> 02:40 -> 16:40 across midnight. Emivita
        // treats end < start as a misconfiguration and returns null rather than
        // crash (ReminderSchedulingTest pins it). Appendix E row A-20 marks this
        // medtimer-better; cost M; the owner's call.
        val overnight = window(600, LocalTime.of(16, 40), LocalTime.of(3, 20), IntervalStartBehavior.ON_REMINDED)
        assertNull(nextDailyWindowOccurrence(overnight, on(1, 0), null, zone))
    }

    // ---- ReminderSchedulerTimezoneUnitTest ----------------------------------

    @Test
    fun `scheduleWithEvents -- the same 00-01 reminder lands on local midnight in three zones`() {
        for (tz in listOf("CET", "America/New_York", "Asia/Kolkata")) {
            val z = ZoneId.of(tz)
            val next = nextFixedTimeOccurrence(fixed(1), onTZ(1, 2, tz), z)
            assertEquals(tz, onTZ(2, 1, tz), next?.epochMillis)
        }
    }

    // ---- ReminderSchedulerWeekendModeTest -----------------------------------

    @Test
    fun `weekendModeTimeRange -- 04-00 to 12-00 on Saturday, 01-00 untouched, 08-00 to 12-00`() {
        // day 3 (1970-01-03) is a Saturday
        val weekend = setOf(DayOfWeek.SATURDAY.value, DayOfWeek.SUNDAY.value)
        val lateNight = NextOccurrence(on(3, 60), 1.0)
        val morning = NextOccurrence(on(3, 480), 1.0)
        assertAt(on(3, 60), applyWeekendMode(lateNight, weekend, LocalTime.of(4, 0), LocalTime.of(12, 0), zone))
        assertAt(on(3, 720), applyWeekendMode(morning, weekend, LocalTime.of(4, 0), LocalTime.of(12, 0), zone))
    }

    // ---- ReminderSchedulerOutOfStockTest ------------------------------------

    @Test
    fun `scheduleOutOfStockReminderDaily -- above threshold nothing, below today, after today tomorrow`() {
        val daily = ReminderConfig.LowStockAlert(threshold = 5.0, triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW, hour = 8, minute = 0)
        assertNull(nextLowStockAlertOccurrence(daily, on(1, 0), 6.0, null, zone))
        assertAt(on(1, 480), nextLowStockAlertOccurrence(daily, on(1, 0), 5.0, null, zone))
        assertAt(on(2, 480), nextLowStockAlertOccurrence(daily, on(1, 481), 5.0, 0L, zone))
    }

    @Test
    fun `scheduleOutOfStockReminderNotDaily -- once and always are never scheduled`() {
        for (mode in listOf(LowStockTriggerMode.ONCE_WHEN_BELOW, LowStockTriggerMode.ALWAYS_WHEN_BELOW)) {
            val cfg = ReminderConfig.LowStockAlert(threshold = 5.0, triggerMode = mode)
            assertNull(mode.name, nextLowStockAlertOccurrence(cfg, on(1, 0), 1.0, null, zone))
        }
    }

    // ---- ReminderSchedulerExpirationTest ------------------------------------

    @Test
    fun `scheduleExpirationReminderOnce -- fires when the lead window opens, then not again`() {
        // medTimer: expiry day 3 (epochDay 2). 1 day before -> not today (day 1); 2 days before -> today.
        val expiry = 2L
        val lead1 = ReminderConfig.ExpirationAlert(leadDays = 1, frequency = ExpirationTriggerFrequency.ONCE, hour = 8, minute = 0)
        val lead2 = ReminderConfig.ExpirationAlert(leadDays = 2, frequency = ExpirationTriggerFrequency.ONCE, hour = 8, minute = 0)
        // Emivita arms the alarm ahead of time where medTimer answers "not yet"; both fire on day 2.
        assertAt(on(2, 480), nextExpirationAlertOccurrence(lead1, on(1, 0), expiry, null, zone))
        assertAt(on(1, 480), nextExpirationAlertOccurrence(lead2, on(1, 0), expiry, null, zone))
        // announced on day 1 -> nothing more for this pack
        assertNull(nextExpirationAlertOccurrence(lead2, on(1, 481), expiry, 0L, zone))
    }

    @Test
    fun `scheduleExpirationReminderOnce -- a new pack with a later expiry re-arms the alert`() {
        // medTimer scans events relative to the *current* expiry date, so a new
        // expiration date with no event inside its lead window fires again.
        // Emivita's flag never reset (R60 finding): a ONCE alert was dead after
        // its first firing for the lifetime of the reminder. Now: the flag only
        // blocks while it lies inside the current lead window.
        val lead2 = ReminderConfig.ExpirationAlert(leadDays = 2, frequency = ExpirationTriggerFrequency.ONCE, hour = 8, minute = 0)
        // announced day 1 for a pack expiring day 3; the next pack expires day 10 (epochDay 9)
        assertAt(on(8, 480), nextExpirationAlertOccurrence(lead2, on(1, 481), 9L, 0L, zone))
    }

    @Test
    fun `scheduleExpirationReminderDaily -- window opens, next day after an event, expiry moves the window`() {
        val daily1 = ReminderConfig.ExpirationAlert(leadDays = 1, frequency = ExpirationTriggerFrequency.DAILY, hour = 8, minute = 0)
        val daily2 = ReminderConfig.ExpirationAlert(leadDays = 2, frequency = ExpirationTriggerFrequency.DAILY, hour = 8, minute = 0)
        assertAt(on(2, 480), nextExpirationAlertOccurrence(daily1, on(1, 0), 2L, null, zone))
        assertAt(on(1, 480), nextExpirationAlertOccurrence(daily2, on(1, 0), 2L, null, zone))
        assertAt(on(2, 480), nextExpirationAlertOccurrence(daily2, on(1, 481), 2L, 0L, zone))
        // expiry moved to day 5 (epochDay 4): window opens day 3
        assertAt(on(3, 480), nextExpirationAlertOccurrence(daily2, on(1, 481), 4L, 0L, zone))
        // a lead longer than the remaining days -> today
        val daily14 = ReminderConfig.ExpirationAlert(leadDays = 14, frequency = ExpirationTriggerFrequency.DAILY, hour = 8, minute = 0)
        assertAt(on(2, 480), nextExpirationAlertOccurrence(daily14, on(1, 481), 4L, 0L, zone))
    }
}
