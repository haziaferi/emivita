package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.isActiveOn
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure, side-effect-free scheduling math -- no DB access, no Android
 * framework types, so these are trivially unit-testable (see the R3 audit
 * item: this file is exactly the kind of logic that should have tests before
 * anything else does). Each function answers "what's the next occurrence of
 * this reminder at or after `notBeforeEpochMillis`?" for one [ReminderConfig]
 * type. [SchedulingEngine] is what actually calls these against real data.
 *
 * Deliberately NOT covered here: [ReminderConfig.LowStockAlert] and
 * [ReminderConfig.ExpirationAlert]
 * need periodic re-evaluation against live stock/date data rather than a
 * fixed "next occurrence", so they don't fit this per-dose-chain shape --
 * they're a separate follow-up, not an oversight.
 */

// ---- FixedTime ----------------------------------------------------------

/** Finds the earliest date on/after [fromDate] that matches [pattern], or
 *  `null` if [pattern] can never match (only possible for an empty
 *  `SpecificDaysOfWeek.days` set -- a UI validation bug if it ever happens,
 *  not something this function should crash over). */
private fun matchingDateOnOrAfter(pattern: RepeatPattern, fromDate: LocalDate): LocalDate? = when (pattern) {
    is RepeatPattern.Daily -> fromDate
    is RepeatPattern.SpecificDaysOfWeek -> {
        if (pattern.days.isEmpty()) {
            null
        } else {
            (0..6).asSequence()
                .map { fromDate.plusDays(it.toLong()) }
                .firstOrNull { pattern.days.contains(it.dayOfWeek.value) } // DayOfWeek.value: Mon=1..Sun=7, matches our convention
        }
    }
    is RepeatPattern.EveryNDays -> {
        require(pattern.n >= 1) { "EveryNDays.n must be >= 1, was ${pattern.n}" }
        val anchor = LocalDate.ofEpochDay(pattern.startEpochDay)
        if (fromDate.isBefore(anchor)) {
            anchor
        } else {
            val daysSinceAnchor = fromDate.toEpochDay() - pattern.startEpochDay
            val remainder = daysSinceAnchor % pattern.n
            if (remainder == 0L) fromDate else fromDate.plusDays(pattern.n - remainder)
        }
    }
    is RepeatPattern.CyclicActivePause -> {
        // Unlike EveryNDays, truly periodic in both directions (spec §3) --
        // isActiveOn's floorMod already handles fromDate being before the
        // anchor correctly, so no separate "before anchor" branch is needed
        // here the way EveryNDays above has one.
        val cycleLength = pattern.activeDays + pattern.pauseDays
        (0 until cycleLength).asSequence()
            .map { fromDate.plusDays(it.toLong()) }
            .firstOrNull { pattern.isActiveOn(it.toEpochDay()) }
    }
}

/** Next occurrence of a [ReminderConfig.FixedTime] reminder at/after
 *  [notBeforeEpochMillis]. To find "the one after an alarm that just fired",
 *  pass `firedEpochMillis + 60_000` (one minute past it) so the same slot
 *  isn't returned again. */
fun nextFixedTimeOccurrence(
    config: ReminderConfig.FixedTime,
    notBeforeEpochMillis: Long,
    zone: ZoneId,
): NextOccurrence? {
    val notBefore = Instant.ofEpochMilli(notBeforeEpochMillis).atZone(zone)
    var date = matchingDateOnOrAfter(config.repeatPattern, notBefore.toLocalDate()) ?: return null
    var candidate = date.atTime(config.hour, config.minute).atZone(zone)
    if (candidate.isBefore(notBefore)) {
        // Today's (or the anchor date's) matching slot already passed --
        // advance to the next matching date and rebuild.
        date = matchingDateOnOrAfter(config.repeatPattern, date.plusDays(1)) ?: return null
        candidate = date.atTime(config.hour, config.minute).atZone(zone)
    }
    return NextOccurrence(candidate.toInstant().toEpochMilli(), config.dose)
}

// ---- RepeatingInterval ---------------------------------------------------

/**
 * Next occurrence of a [ReminderConfig.RepeatingInterval] reminder.
 *
 * For [IntervalStartBehavior.ON_REMINDED], the schedule is a fixed grid
 * anchored at `config.anchorEpochMillis` (never mutated) -- this is
 * self-correcting rather than drift-prone: even if a specific alarm fires a
 * little late, the *next* one still lands on the original grid instead of
 * chaining off the late firing.
 *
 * For [IntervalStartBehavior.ON_RESOLVED], `config.anchorEpochMillis` is
 * NOT used -- the real anchor is "when the dose was last actually taken or
 * skipped", which only [SchedulingEngine] knows (it reads dose-event
 * history), so it's passed in as [effectiveAnchorEpochMillis] instead.
 */
fun nextRepeatingIntervalOccurrence(
    config: ReminderConfig.RepeatingInterval,
    notBeforeEpochMillis: Long,
    effectiveAnchorEpochMillis: Long,
): NextOccurrence {
    require(config.intervalMinutes >= 1) { "RepeatingInterval.intervalMinutes must be >= 1" }
    val intervalMillis = config.intervalMinutes * 60_000L
    val elapsed = notBeforeEpochMillis - effectiveAnchorEpochMillis
    val stepsElapsed = if (elapsed <= 0) 0L else (elapsed + intervalMillis - 1) / intervalMillis
    val next = effectiveAnchorEpochMillis + stepsElapsed * intervalMillis
    return NextOccurrence(next, config.dose)
}

// ---- DailyWindow ----------------------------------------------------------

private const val DAILY_WINDOW_LOOKAHEAD_DAYS = 3 // a daily window recurs every day, so 2 would already be provably enough; 3 is margin, not a load-bearing constant

private fun windowSlotOnDay(
    day: LocalDate,
    config: ReminderConfig.DailyWindow,
    notBefore: ZonedDateTime,
    lastResolved: ZonedDateTime?,
    zone: ZoneId,
): ZonedDateTime? {
    val windowStart = day.atTime(config.windowStartHour, config.windowStartMinute).atZone(zone)
    val windowEnd = day.atTime(config.windowEndHour, config.windowEndMinute).atZone(zone)
    if (windowEnd.isBefore(windowStart)) return null // misconfigured (end before start) -- skip this day rather than crash

    val gridStart = if (
        config.startBehavior == IntervalStartBehavior.ON_RESOLVED &&
        lastResolved != null &&
        !lastResolved.isBefore(windowStart) &&
        lastResolved.isBefore(windowEnd)
    ) {
        lastResolved.plusMinutes(config.intervalMinutes.toLong())
    } else {
        windowStart
    }

    var candidate = gridStart
    if (candidate.isBefore(notBefore)) {
        val intervalMillis = config.intervalMinutes * 60_000L
        val elapsedMillis = Duration.between(candidate, notBefore).toMillis()
        val steps = (elapsedMillis + intervalMillis - 1) / intervalMillis
        candidate = candidate.plus(Duration.ofMillis(steps * intervalMillis))
    }
    return if (!candidate.isBefore(windowStart) && !candidate.isAfter(windowEnd)) candidate else null
}

// ---- Shared helpers for the alarm-fired path ------------------------------

/** The dose amount to materialize a DoseEvent with. `null` for
 *  LowStockAlert/ExpirationAlert, which don't represent a "dose" at all. */
fun ReminderConfig.doseAmountOrNull(): Double? = when (this) {
    is ReminderConfig.FixedTime -> dose
    is ReminderConfig.RepeatingInterval -> dose
    is ReminderConfig.DailyWindow -> dose
    is ReminderConfig.LinkedFollowing -> dose
    is ReminderConfig.LowStockAlert, is ReminderConfig.ExpirationAlert -> null
}

/**
 * Whether the *next* occurrence should be computed and scheduled the moment
 * this one fires (true), or only once the user actually resolves this dose
 * (false). FixedTime's next slot never depends on resolution. An
 * ON_REMINDED interval/window is a fixed grid, also independent of
 * resolution. An ON_RESOLVED interval/window must NOT be re-armed here --
 * doing so would schedule the following dose before the current one has
 * even been answered. LinkedFollowing is always false here regardless of its
 * own startBehavior -- it has no "next slot" of its own to re-arm at all,
 * its next occurrence is entirely defined by the parent's next event (spec
 * §3/§9 #14); see ReminderAlarmReceiver/RealSchedulingCoordinator's cascade-
 * reschedule trigger points for how a LinkedFollowing child actually gets
 * rescheduled.
 */
fun ReminderConfig.reschedulesImmediatelyOnFire(): Boolean = when (this) {
    is ReminderConfig.FixedTime -> true
    is ReminderConfig.RepeatingInterval -> startBehavior == IntervalStartBehavior.ON_REMINDED
    is ReminderConfig.DailyWindow -> startBehavior == IntervalStartBehavior.ON_REMINDED
    is ReminderConfig.LinkedFollowing -> false
    is ReminderConfig.LowStockAlert, is ReminderConfig.ExpirationAlert -> false
}

/** Next occurrence of a [ReminderConfig.DailyWindow] reminder. [lastResolvedEpochMillis]
 *  is only consulted when `startBehavior == ON_RESOLVED`, and only if it falls
 *  inside the window being evaluated -- a resolution from a previous day
 *  never leaks into today's grid. */
fun nextDailyWindowOccurrence(
    config: ReminderConfig.DailyWindow,
    notBeforeEpochMillis: Long,
    lastResolvedEpochMillis: Long?,
    zone: ZoneId,
): NextOccurrence? {
    require(config.intervalMinutes >= 1) { "DailyWindow.intervalMinutes must be >= 1" }
    val notBefore = Instant.ofEpochMilli(notBeforeEpochMillis).atZone(zone)
    val lastResolved = lastResolvedEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
    var day = notBefore.toLocalDate()
    repeat(DAILY_WINDOW_LOOKAHEAD_DAYS) {
        windowSlotOnDay(day, config, notBefore, lastResolved, zone)?.let {
            return NextOccurrence(it.toInstant().toEpochMilli(), config.dose)
        }
        day = day.plusDays(1)
    }
    return null
}

// ---- Weekend mode (global preference, not per-reminder) -------------------

/**
 * Weekend-mode adjustment (spec §3, ported faithfully from medTimer's
 * WeekendModeSchedulingDecorator per spec §5) -- applied as the *last* step
 * for every reminder type unconditionally, no per-reminder opt-out. If
 * [occurrence]'s day-of-week is in [weekendDays] and its time-of-day falls in
 * `[weekendStart, weekendEnd)`, pushes it to [weekendEnd] on that same day;
 * otherwise leaves it untouched. An empty [weekendDays] (the default, nothing
 * configured yet) is a no-op regardless of the enabled flag -- callers only
 * need to check "is weekend mode enabled" before calling this if they want to
 * skip the work entirely, not to preserve correctness.
 */
fun applyWeekendMode(
    occurrence: NextOccurrence,
    weekendDays: Set<Int>,
    weekendStart: LocalTime,
    weekendEnd: LocalTime,
    zone: ZoneId,
): NextOccurrence {
    if (weekendDays.isEmpty()) return occurrence
    val zoned = Instant.ofEpochMilli(occurrence.epochMillis).atZone(zone)
    val timeOfDay = zoned.toLocalTime()

    // DayOfWeek.value: Mon=1..Sun=7, matches our convention throughout.
    // weekendStart/weekendEnd are plain LocalTime pickers (SettingsRepository)
    // with nothing stopping a user from configuring an overnight window (e.g.
    // 22:00-06:00) where start > end -- that window spans two calendar dates,
    // so both which day's weekendDays membership applies and which day to
    // push to depend on which side of midnight timeOfDay falls on.
    val pushDate: LocalDate = if (weekendStart <= weekendEnd) {
        if (zoned.dayOfWeek.value !in weekendDays) return occurrence
        if (timeOfDay.isBefore(weekendStart) || !timeOfDay.isBefore(weekendEnd)) return occurrence
        zoned.toLocalDate()
    } else if (!timeOfDay.isBefore(weekendStart)) {
        // e.g. 23:00 on a 22:00-06:00 window -- the window that started today.
        if (zoned.dayOfWeek.value !in weekendDays) return occurrence
        zoned.toLocalDate().plusDays(1)
    } else if (timeOfDay.isBefore(weekendEnd)) {
        // e.g. 02:00 on a 22:00-06:00 window -- the window that started
        // yesterday evening, so yesterday's membership governs, not today's.
        if (zoned.toLocalDate().minusDays(1).dayOfWeek.value !in weekendDays) return occurrence
        zoned.toLocalDate()
    } else {
        return occurrence
    }
    val pushed = pushDate.atTime(weekendEnd).atZone(zone)
    return occurrence.copy(epochMillis = pushed.toInstant().toEpochMilli())
}

/**
 * A snapshot of the global weekend-mode preferences, read once per
 * scheduling pass by [SchedulingEngine.readWeekendMode].
 *
 * Two states rather than a nullable set of fields, so "the toggle is off"
 * and "the toggle is on but no days are chosen" stay distinguishable --
 * [applyWeekendMode] treats an empty day set as a no-op anyway, but the
 * engine skips the call entirely for Off.
 */
sealed interface WeekendMode {
    data object Off : WeekendMode

    data class On(
        val days: Set<Int>,
        val start: LocalTime,
        val end: LocalTime,
    ) : WeekendMode
}
