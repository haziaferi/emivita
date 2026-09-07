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
 * Pure, side-effect-free scheduling math -- no DB access, no Android
 * framework types, so these are trivially unit-testable (see the R3 audit
 * item: this file is exactly the kind of logic that should have tests before
 * anything else does). Each function answers "what's the next occurrence of
 * this reminder at or after `notBeforeEpochMillis`?" for one [ReminderConfig]
 * type. [SchedulingEngine] is what actually calls these against real data.
 *
 * [ReminderConfig.LowStockAlert] and [ReminderConfig.ExpirationAlert] are
 * covered here too, as of spec §7 R37. This comment used to say they were
 * "deliberately NOT covered" because they "need periodic re-evaluation against
 * live stock/date data rather than a fixed next occurrence" -- which was true
 * of the evaluator they were given instead, and false of the reference this
 * file ports from: medTimer schedules both as ordinary reminder types. Their
 * two calculators take the live stock quantity and expiry date as parameters,
 * which keeps them as pure as the rest of the file.
 */

// ---- FixedTime ----------------------------------------------------------

/** Finds the earliest date on/after [fromDate] that matches [pattern], or
 *  `null` if [pattern] can never match: an empty `SpecificDaysOfWeek.days`
 *  set, an `EveryNDays.n` below 1, or a `CyclicActivePause` whose
 *  `activeDays` is below 1 or whose `pauseDays` is negative. All three are UI
 *  validation bugs if they ever happen, and none is something this function
 *  should crash over.
 *
 *  The `n` case used to be a `require(n >= 1)`, which contradicted the
 *  sentence above it and mattered because of *where* it threw: the row is
 *  committed before the scheduler ever sees it, and the projection cache
 *  re-reads every reminder on a loop, so one bad row threw on every
 *  recompute rather than at the point of entry. Returning null degrades to
 *  "this reminder has no next occurrence", which every caller already
 *  handles. See docs/SPEC.md §7 R23. */
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
    // Null, not `require`, for the reason [matchingDateOnOrAfter] spells out
    // above: R23 replaced exactly this guard in the EveryNDays and
    // CyclicActivePause branches and left it standing in the two interval
    // calculators, so one file ended up answering the same question -- what
    // does an invalid config do? -- two opposite ways. The half that was fixed
    // carries the argument that settles the other half: AddReminderScreen gates
    // the field (see its own comment, which cites this require by name), but
    // BackupRepository.restoreSnapshot writes reminder rows back with no config
    // revalidation, so a corrupted backup is enough to commit a zero here.
    //
    // Where it threw is what made it matter. OccurrenceProjectionCache's
    // recompute runs inside a `for (trigger)` loop, so an exception did not
    // skip one reminder -- it cancelled the loop for the life of the process,
    // silently freezing Today, History and both widgets and stopping every
    // low-stock and expiration alert. SchedulingCoordinator.rescheduleAll()
    // walks every enabled reminder at boot, so a throw there left each one
    // after the bad row with no alarm at all. Null degrades to "this reminder
    // has no next occurrence", which every caller already handles.
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

// ---- LowStockAlert / ExpirationAlert --------------------------------------

/**
 * The next instant an alert reminder should fire, or null for "not due, and
 * nothing to arm". Spec §7 R37, ported from medTimer's `OutOfStockScheduling`
 * and `ExpirationDateScheduling`, which are ordinary [Scheduling]
 * implementations feeding the same alarm chain as every dose reminder.
 *
 * Emivita differs from the reference in one mechanical respect: medTimer finds
 * "the next day this has not already been announced on" by scanning reminder
 * *events* (`SchedulingBase.getNextNotRemindedDay` walks 31 days calling
 * `isRaisedOn`). Emivita already records the same fact as a single column,
 * [com.hazia.emivita.core.data.entity.Reminder.lastAlertNotifiedEpochDay], so
 * this takes it as a parameter instead of replaying history. Same answer,
 * one comparison instead of a scan.
 *
 * [lastNotifiedEpochDay] is null for "never announced" -- and, for low stock
 * specifically, is reset to null when stock rises back above the threshold, so
 * a restock genuinely re-arms a ONCE alert. See LowStockExpirationAlertEvaluator.
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
 * Next firing of a [ReminderConfig.LowStockAlert].
 *
 * Only `DAILY_WHEN_BELOW` is scheduled, which is exactly medTimer's rule --
 * `OutOfStockScheduling.getNextScheduledTime` returns null unless the type is
 * DAILY. `ONCE_WHEN_BELOW` and `ALWAYS_WHEN_BELOW` are announced at the moment
 * stock actually crosses the threshold, the way medTimer's
 * `StockHandlingProcessor.checkForThreshold` does it inline on every stock
 * decrement; an alarm cannot express "the instant this changes", and waiting
 * until tomorrow morning to mention that a medicine just ran out would be
 * worse than the event-driven path those two already have.
 *
 * [stockQuantity] is null when the medicine has stock tracking off (no unit
 * set), which is the app-wide meaning of that state and is not an alert.
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
 * Next firing of a [ReminderConfig.ExpirationAlert]. Unlike low stock, *both*
 * modes are scheduled -- matching medTimer, where `ExpirationDateScheduling`
 * handles ONCE and DAILY alike. There is no event to hang an expiry on: a
 * medicine sitting in a cupboard produces no writes, which is precisely why
 * this type could not survive as an evaluator riding data changes.
 *
 * [expirationEpochDay] null means the medicine has no expiry recorded, which
 * is not an alert. The alert opens on `expiration - leadDays` and, for DAILY,
 * keeps firing past the expiry date -- an expired medicine is more worth
 * mentioning than one about to expire, not less.
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
        // Once really means once: announced already, never again for this
        // reminder. Unlike low stock there is no "resolved" state to re-arm
        // from -- an expiry date does not un-pass.
        ExpirationTriggerFrequency.ONCE -> {
            if (lastNotifiedEpochDay != null) return null
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
    // Same reasoning as nextRepeatingIntervalOccurrence's guard, and the same
    // policy this function already applies one level down: windowSlotOnDay
    // returns null for an end-before-start window rather than throwing.
    if (config.intervalMinutes < 1) return null
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
 * WeekendModeSchedulingDecorator per spec §5) -- applied as the *last* step,
 * after the type-specific calculator and the activeFrom/activeUntil clamp. If
 * [occurrence]'s day-of-week is in [weekendDays] and its time-of-day falls in
 * `[weekendStart, weekendEnd)`, pushes it to [weekendEnd] on that same day;
 * otherwise leaves it untouched. An empty [weekendDays] (the default, nothing
 * configured yet) is a no-op regardless of the enabled flag -- callers only
 * need to check "is weekend mode enabled" before calling this if they want to
 * skip the work entirely, not to preserve correctness.
 *
 * This comment used to say the adjustment applied "for every reminder type
 * unconditionally, no per-reminder opt-out", which stopped being true at §7
 * R36: [SchedulingEngine.nextOccurrenceFor] now gates on
 * `Reminder.weekendModeOptIn` *before* reading the global setting, so both
 * must be on. The behaviour changed and this file kept stating the old
 * contract, which is how one file ends up answering a question two opposite
 * ways (§7 R58).
 *
 * **What this does on a sub-daily reminder** (§7 R58, measured rather than
 * reasoned). "Delays a dose" describes the FixedTime case exactly: one slot a
 * day, pushed to the end of the window. For a RepeatingInterval or DailyWindow
 * it does something stronger, because deferral is not queueing. Every raw slot
 * inside the window is pushed to the same instant, and both the alarm chain
 * (`ReminderAlarmReceiver` reschedules from `fired + 60s`) and the projection
 * walk ([SchedulingEngine.occurrencesFor], the same arithmetic) then resume
 * *past* the window -- so the intermediate slots are never armed and never
 * materialize a DoseEvent. A 2-hourly reminder across an 08:00-20:00 weekend
 * window yields 7 doses that day where an ordinary day yields 13; the six in
 * between are not stacked at 20:00, they do not happen. The projection is
 * therefore accurate, not lossy -- it reports exactly what the device will do
 * -- but anyone opting a sub-daily reminder into weekend mode is choosing to
 * skip those doses, not to postpone them.
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
