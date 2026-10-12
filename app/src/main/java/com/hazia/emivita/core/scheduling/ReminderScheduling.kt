package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.LowStockTriggerMode
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
 * Pure scheduling math -- no DB access, no Android framework types -- so it
 * is unit-testable (R3); [SchedulingEngine] calls it against real data. Each
 * function answers "what's the next occurrence of this reminder at or after
 * `notBeforeEpochMillis`?" for one [ReminderConfig] type. The two alert types
 * are ordinary reminder types here, as in medTimer (spec §7 R37), and take the
 * live stock quantity and expiry date as parameters to stay pure.
 */

// ---- FixedTime ----------------------------------------------------------

/** Finds the earliest date on/after [fromDate] that matches [pattern], or
 *  `null` if [pattern] can never match: an empty `SpecificDaysOfWeek.days`
 *  set, an `EveryNDays.n` below 1, or a `CyclicActivePause` whose
 *  `activeDays` is below 1 or whose `pauseDays` is negative. Never throw here:
 *  the row is already committed and the projection cache re-reads every
 *  reminder on a loop, so one bad row would throw on every recompute, while
 *  null degrades to "no next occurrence" (docs/SPEC.md §7 R23). */
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
    is RepeatPattern.EveryNDays -> if (pattern.n < 1) {
        null
    } else {
        val anchor = LocalDate.ofEpochDay(pattern.startEpochDay)
        if (fromDate.isBefore(anchor)) {
            anchor
        } else {
            val daysSinceAnchor = fromDate.toEpochDay() - pattern.startEpochDay
            val remainder = daysSinceAnchor % pattern.n
            if (remainder == 0L) fromDate else fromDate.plusDays(pattern.n - remainder)
        }
    }
    is RepeatPattern.CyclicActivePause -> if (pattern.activeDays < 1 || pattern.pauseDays < 0) {
        // Same reasoning as the EveryNDays guard above, and the same defect
        // shape R23 fixed there: isActiveOn carries a require(activeDays >= 1
        // && pauseDays >= 0), and reaching it with a degenerate cycle threw
        // from inside the projection cache's recompute loop rather than at
        // the point of entry. AddReminderScreen gates both fields, but
        // BackupRepository.restoreAll() writes reminder rows back with no
        // config revalidation, so a corrupted backup is enough to commit one.
        // Guarded here rather than by loosening isActiveOn, which keeps its
        // require as the contract for direct callers.
        null
    } else {
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
): NextOccurrence? {
    // Null, not `require` (§7 R23, as in [matchingDateOnOrAfter]): the UI gates
    // the field, but BackupRepository.restoreSnapshot writes rows back without
    // revalidating, so a corrupted backup can commit a zero here. A throw would
    // cancel OccurrenceProjectionCache's `for (trigger)` loop for the life of the
    // process (freezing Today, History, both widgets and every stock/expiry
    // alert) and leave every reminder after the bad row unarmed at boot, while
    // null means "no next occurrence", which every caller already handles.
    if (config.intervalMinutes < 1) return null
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
    var windowEnd = day.atTime(config.windowEndHour, config.windowEndMinute).atZone(zone)
    // An end before the start means the window runs past midnight (§9 #31,
    // A-20; medTimer's `calcInstant(end).plus(1, DAYS)`): 22:00 to 06:00 is
    // the night of [day], ending the next morning. Until R72 this returned
    // null -- an overnight window silently never fired.
    if (windowEnd.isBefore(windowStart)) windowEnd = windowEnd.plusDays(1)

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

// ---- LowStockAlert / ExpirationAlert --------------------------------------

/**
 * The next instant an alert reminder should fire, or null for "not due, and
 * nothing to arm" (spec §7 R37, ported from medTimer's `OutOfStockScheduling`
 * and `ExpirationDateScheduling`). Where medTimer scans 31 days of events
 * (`SchedulingBase.getNextNotRemindedDay`), this reads the single column
 * [com.hazia.emivita.core.data.entity.Reminder.lastAlertNotifiedEpochDay]
 * passed in as `lastNotifiedEpochDay`: same answer, one comparison. That value
 * is null for "never announced", and for low stock a restock above the
 * threshold resets it to null so a ONCE alert re-arms (see
 * LowStockExpirationAlertEvaluator).
 */
private fun nextAlertSlot(
    earliestEpochDay: Long,
    hour: Int,
    minute: Int,
    notBeforeEpochMillis: Long,
    zone: ZoneId,
): NextOccurrence? {
    if (hour !in 0..23 || minute !in 0..59) return null // same degrade-don't-throw policy as the calculators above
    val notBefore = Instant.ofEpochMilli(notBeforeEpochMillis).atZone(zone)
    var day = maxOf(earliestEpochDay, notBefore.toLocalDate().toEpochDay())
    var slot = LocalDate.ofEpochDay(day).atTime(hour, minute).atZone(zone)
    if (slot.isBefore(notBefore)) {
        // Today's slot has already passed -- the next one is tomorrow's. Only
        // ever one step: the slot recurs daily, so day+1 cannot also be behind
        // a floor that day was only just behind.
        day += 1
        slot = LocalDate.ofEpochDay(day).atTime(hour, minute).atZone(zone)
    }
    // doseAmount 0.0: an alert is not a dose. Nothing materializes a DoseEvent
    // from it (ReminderAlarmReceiver routes alert fires to their own branch),
    // and OccurrenceProjectionCache projects only dose-producing types, so this
    // value is never summed against stock or rendered on a timeline.
    return NextOccurrence(slot.toInstant().toEpochMilli(), 0.0)
}

/**
 * Next firing of a [ReminderConfig.LowStockAlert]. Only `DAILY_WHEN_BELOW` is
 * scheduled (medTimer's `OutOfStockScheduling` rule): `ONCE_WHEN_BELOW` and
 * `ALWAYS_WHEN_BELOW` are announced the instant stock crosses the threshold,
 * as `StockHandlingProcessor.checkForThreshold` does, which no alarm can express.
 * A null [stockQuantity] means stock tracking is off, which is not an alert.
 */
fun nextLowStockAlertOccurrence(
    config: ReminderConfig.LowStockAlert,
    notBeforeEpochMillis: Long,
    stockQuantity: Double?,
    lastNotifiedEpochDay: Long?,
    zone: ZoneId,
): NextOccurrence? {
    if (config.triggerMode != LowStockTriggerMode.DAILY_WHEN_BELOW) return null
    if (stockQuantity == null || stockQuantity > config.threshold) return null
    // Already announced today -- the next one is tomorrow at the earliest.
    val earliest = lastNotifiedEpochDay?.plus(1) ?: Long.MIN_VALUE
    return nextAlertSlot(earliest, config.hour, config.minute, notBeforeEpochMillis, zone)
}

/**
 * Next firing of a [ReminderConfig.ExpirationAlert]. Unlike low stock, both
 * ONCE and DAILY are scheduled (as in medTimer's `ExpirationDateScheduling`),
 * because a medicine sitting in a cupboard produces no write to hang an expiry
 * on. A null [expirationEpochDay] is not an alert; otherwise the alert opens on
 * `expiration - leadDays` and DAILY keeps firing past the expiry date, since an
 * expired medicine is more worth mentioning, not less.
 */
fun nextExpirationAlertOccurrence(
    config: ReminderConfig.ExpirationAlert,
    notBeforeEpochMillis: Long,
    expirationEpochDay: Long?,
    lastNotifiedEpochDay: Long?,
    zone: ZoneId,
): NextOccurrence? {
    if (expirationEpochDay == null) return null
    if (config.leadDays < 0) return null // degrade rather than schedule nonsense; UI gates this too
    val opensOnEpochDay = expirationEpochDay - config.leadDays

    val earliest = when (config.frequency) {
        // Once per pack, not once per reminder (§7 R60). This used to return
        // null whenever the alert had ever fired, on the reasoning that "an
        // expiry date does not un-pass" -- true of a date, not of a medicine:
        // the user opens a new pack, types its expiry into the stock screen,
        // and the alert they configured was dead for good, with nothing on
        // screen to say so. medTimer scans events relative to the *current*
        // expiry date, so a moved date re-arms it; here the recorded day
        // blocks only while it lies inside the current lead window.
        ExpirationTriggerFrequency.ONCE -> {
            if (lastNotifiedEpochDay != null && lastNotifiedEpochDay >= opensOnEpochDay) return null
            opensOnEpochDay
        }
        ExpirationTriggerFrequency.DAILY ->
            maxOf(opensOnEpochDay, lastNotifiedEpochDay?.plus(1) ?: Long.MIN_VALUE)
    }
    return nextAlertSlot(earliest, config.hour, config.minute, notBeforeEpochMillis, zone)
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
 * (false). FixedTime and ON_REMINDED grids never depend on resolution, while
 * re-arming an ON_RESOLVED interval/window here would schedule the following
 * dose before the current one is answered. LinkedFollowing has no slot of its
 * own: its next occurrence follows the parent's next event (spec §3/§9 #14) and
 * is rescheduled by the cascade in ReminderAlarmReceiver/RealSchedulingCoordinator.
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
    // Same reasoning as nextRepeatingIntervalOccurrence's guard, and the same
    // policy this function already applies one level down: windowSlotOnDay
    // returns null for an end-before-start window rather than throwing.
    if (config.intervalMinutes < 1) return null
    val notBefore = Instant.ofEpochMilli(notBeforeEpochMillis).atZone(zone)
    val lastResolved = lastResolvedEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
    // An overnight window that started yesterday may still be running now:
    // 02:00 belongs to yesterday's 22:00-06:00, not to today's. Start a day
    // back for those; the bounds check inside windowSlotOnDay keeps a slot
    // from being reported for a window that has already ended.
    var day = notBefore.toLocalDate()
    if (config.isOvernight) day = day.minusDays(1)
    repeat(DAILY_WINDOW_LOOKAHEAD_DAYS + 1) {
        windowSlotOnDay(day, config, notBefore, lastResolved, zone)?.let {
            return NextOccurrence(it.toInstant().toEpochMilli(), config.dose)
        }
        day = day.plusDays(1)
    }
    return null
}

// ---- Weekend mode (global preference, not per-reminder) -------------------

/**
 * Weekend-mode adjustment (spec §3, medTimer's WeekendModeSchedulingDecorator
 * per §5), applied last: an [occurrence] on one of [weekendDays] inside
 * `[weekendStart, weekendEnd)` moves to [weekendEnd] that day, and an empty
 * [weekendDays] is a no-op; [SchedulingEngine.nextOccurrenceFor] also requires
 * `Reminder.weekendModeOptIn` (§7 R36). On a sub-daily reminder every slot in
 * the window collapses to [weekendEnd] and both the alarm chain and
 * [SchedulingEngine.occurrencesFor] resume past it, so the intermediate doses
 * are skipped, not postponed (§7 R58). Measured: a 2-hourly reminder across an
 * 08:00-20:00 weekend window yields 7 doses that day instead of 13.
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
