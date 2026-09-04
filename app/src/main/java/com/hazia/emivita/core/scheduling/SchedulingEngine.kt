package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bridges the pure math in ReminderScheduling.kt to real reminder/dose-event
 * data. This is the ONLY place that should call the per-type calculators --
 * everything else (AlarmScheduler, RealSchedulingCoordinator, the boot
 * receiver) should go through [nextOccurrenceFor].
 *
 * Depends on [DoseEventDao] directly rather than on ReminderRepository --
 * ReminderRepository depends on SchedulingCoordinator (to notify it of
 * changes), so routing the engine through the repository as well would
 * create a cycle. Reminder config decoding uses the standalone
 * [decodeReminderConfig] function for the same reason.
 */
class SchedulingEngine(
    private val doseEventDao: DoseEventDao,
    private val settingsRepository: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    /** Reminder types that have a next-occurrence chain at all.
     *  LOW_STOCK_ALERT and EXPIRATION_ALERT are excluded on purpose -- they
     *  need periodic re-evaluation against live stock/date data rather than a
     *  fixed next occurrence, and are handled by
     *  [LowStockExpirationAlertEvaluator] on the projection cache's own
     *  recompute pass instead. */
    val supportedTypes: Set<ReminderType> = setOf(
        ReminderType.FIXED_TIME,
        ReminderType.REPEATING_INTERVAL,
        ReminderType.DAILY_WINDOW,
        ReminderType.LINKED_FOLLOWING,
    )

    /** Reads the weekend-mode preferences once, so a projection walk doesn't
     *  re-read DataStore per occurrence. Each `.first()` on `dataStore.data`
     *  is a fresh cold-flow collection, not a cached value read, and
     *  [occurrencesFor] can call [nextOccurrenceFor] up to `maxOccurrences`
     *  times per reminder per recompute.
     *
     *  The three window preferences are only read when the toggle is on --
     *  same "a disabled feature costs one Flow read" property the inline
     *  version had. */
    suspend fun readWeekendMode(): WeekendMode {
        if (!settingsRepository.weekendModeEnabled.first()) return WeekendMode.Off
        return WeekendMode.On(
            days = settingsRepository.weekendDays.first(),
            start = settingsRepository.weekendStartTime.first(),
            end = settingsRepository.weekendEndTime.first(),
        )
    }

    /** Next time [reminder] should fire at/after [notBeforeEpochMillis], or
     *  `null` if it has no more occurrences (disabled, an unsupported type
     *  for now, a calculator-level "never matches" result, or a candidate past
     *  [Reminder.activeUntilEpochDay]).
     *
     *  "At/after [notBeforeEpochMillis]" is a guarantee every branch of
     *  [computeBase] honours, LinkedFollowing included -- see the note there
     *  for what went wrong while it did not. A reminder whose
     *  [Reminder.activeFromEpochDay] has not arrived yet is *deferred*, not
     *  refused: the answer is the first occurrence on or after the day the
     *  range opens.
     *
     *  A null [weekendMode] means "read it yourself" -- convenient for a
     *  one-off caller, and the read still happens only after the clamps
     *  below, so a reminder that returns early costs nothing. Pass a
     *  snapshot when calling repeatedly. (Null rather than a default of
     *  `readWeekendMode()`: Kotlin does not allow a suspend call in a
     *  default parameter value.) */
    suspend fun nextOccurrenceFor(
        reminder: Reminder,
        notBeforeEpochMillis: Long = nowEpochMillis(),
        weekendMode: WeekendMode? = null,
    ): NextOccurrence? {
        if (!reminder.enabled) return null
        if (reminder.type !in supportedTypes) return null

        var base = computeBase(reminder, notBeforeEpochMillis) ?: return null

        // activeFrom/activeUntil clamp (spec §3/§4) -- after the type-specific
        // calculator, before weekend mode, per §4's explicit ordering ("weekend
        // mode is applied... after the type-specific calculator and the
        // activeFrom/UntilEpochDay clamp"). Null on either side = unbounded on
        // that side (today's default behavior, unchanged for every reminder
        // that doesn't set these).
        //
        // The two bounds are NOT symmetric, which is what this used to get
        // wrong -- both read "candidate outside the range -> return null". For
        // activeUntil that is right: past the end there genuinely is no next
        // occurrence, ever. For activeFrom it is not. A range that opens in the
        // future means "not yet", not "never", and nothing in this app re-arms
        // a reminder on the passage of time alone: rescheduleAll() runs at boot
        // and app-update, and OccurrenceProjectionCache recomputes on data
        // changes, not on a clock. So a reminder whose range opened even a day
        // out had its alarm cancelled the moment it was saved and was never
        // scheduled again, and projected zero occurrences into Today, History
        // and both widgets.
        //
        // That silently broke the one mechanism §4 names for courses and
        // tapers -- "several ordinary FixedTime reminders with adjacent,
        // non-overlapping ranges -- no new ReminderConfig type needed, this
        // clamp is the whole mechanism" -- since only the segment already in
        // range could ever fire. Deferring re-runs the calculator from the day
        // the range opens instead of giving up.
        val activeFrom = reminder.activeFromEpochDay
        if (activeFrom != null && base.epochDayIn(zone) < activeFrom) {
            val opensAtEpochMillis = LocalDate.ofEpochDay(activeFrom).atStartOfDay(zone).toInstant().toEpochMilli()
            // Only worth a second pass if the range actually opens later than
            // the floor already asked for. When it doesn't, the calculator has
            // been given a floor at or past the opening and still answered
            // earlier -- true of LinkedFollowing, whose occurrence is anchored
            // to its parent rather than to notBefore -- so there is nothing
            // further forward to find, and re-running would only repeat itself.
            if (opensAtEpochMillis <= notBeforeEpochMillis) return null
            base = computeBase(reminder, opensAtEpochMillis) ?: return null
            if (base.epochDayIn(zone) < activeFrom) return null
        }

        reminder.activeUntilEpochDay?.let { if (base.epochDayIn(zone) > it) return null }

        // Weekend mode (spec §3) -- the last step, and opt-in per reminder.
        //
        // This gate is checked before the global one on purpose. It used to be
        // applied to every reminder whenever the global toggle was on, which
        // is how medTimer's SchedulingFactory does it (every scheduler wrapped
        // in WeekendModeSchedulingDecorator, no per-reminder gate) -- and it
        // meant one setting could not distinguish a supplement that is happy
        // to wait until 11:00 on a Sunday from a drug that is not. Both must
        // now be on: SettingsRepository still owns which days and which window,
        // Reminder.weekendModeOptIn owns whether this reminder is subject to
        // it. See docs/SPEC.md §7 R36.
        //
        // Returning here rather than falling through also means a reminder
        // that has not opted in never triggers readWeekendMode() at all, which
        // preserves the "a disabled feature costs one Flow read" property that
        // readWeekendMode's own doc comment describes -- and for the common
        // case (nothing opted in) now costs zero.
        if (!reminder.weekendModeOptIn) return base
        return when (val mode = weekendMode ?: readWeekendMode()) {
            is WeekendMode.Off -> base
            is WeekendMode.On -> applyWeekendMode(base, mode.days, mode.start, mode.end, zone)
        }
    }

    private fun NextOccurrence.epochDayIn(zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()

    /** The type-specific calculators alone, before any of [nextOccurrenceFor]'s
     *  cross-cutting clamps. Split out of it specifically so the activeFrom
     *  clamp can re-run the calculation from the day the active range opens
     *  rather than having to answer "never". */
    private suspend fun computeBase(reminder: Reminder, notBeforeEpochMillis: Long): NextOccurrence? =
        when (val config = decodeReminderConfig(reminder.configJson)) {
            is ReminderConfig.FixedTime ->
                nextFixedTimeOccurrence(config, notBeforeEpochMillis, zone)

            is ReminderConfig.RepeatingInterval -> {
                val effectiveAnchor = when (config.startBehavior) {
                    IntervalStartBehavior.ON_REMINDED -> config.anchorEpochMillis
                    IntervalStartBehavior.ON_RESOLVED ->
                        doseEventDao.getLastResolved(reminder.id)?.resolvedAtEpochMillis
                            ?: reminder.createdAtEpochMillis
                }
                nextRepeatingIntervalOccurrence(config, notBeforeEpochMillis, effectiveAnchor)
            }

            is ReminderConfig.DailyWindow -> {
                val lastResolvedEpochMillis = if (config.startBehavior == IntervalStartBehavior.ON_RESOLVED) {
                    doseEventDao.getLastResolved(reminder.id)?.resolvedAtEpochMillis
                } else {
                    null
                }
                nextDailyWindowOccurrence(config, notBeforeEpochMillis, lastResolvedEpochMillis, zone)
            }

            is ReminderConfig.LinkedFollowing -> {
                val parentId = reminder.parentReminderId
                if (parentId == null) {
                    // Orphaned child. Not a "shouldn't happen" case, which is
                    // what this comment used to claim: the self-referencing FK
                    // on Reminder.parentReminderId is ON DELETE SET_NULL, so
                    // detaching a child is exactly what deleting its parent
                    // does.
                    //
                    // R23 left the FK's semantics contradicting §3's "ON DELETE
                    // CASCADE" as an open decision; §7 R25 settled it in favour
                    // of SET_NULL and corrected §3. The silent-dead-row problem
                    // this branch used to describe is handled at the source:
                    // ReminderRepository.delete() switches every detached child
                    // off in the same transaction, so a child that reaches this
                    // branch by that route is already disabled and returns null
                    // one gate earlier.
                    //
                    // This guard is still load-bearing, because delete() is not
                    // the only way to get here: BackupRepository.restoreAll()
                    // writes reminder rows back verbatim, so a backup whose
                    // parent row is missing restores an enabled child with a
                    // null parent, as would any row predating R25.
                    null
                } else {
                    val anchorEpochMillis = when (config.startBehavior) {
                        IntervalStartBehavior.ON_REMINDED -> doseEventDao.getMostRecentForReminder(parentId)?.scheduledEpochMillis
                        IntervalStartBehavior.ON_RESOLVED -> doseEventDao.getLastResolved(parentId)?.resolvedAtEpochMillis
                    }
                    // Parent hasn't fired/resolved yet -- nothing to follow. Not
                    // "unbounded," just genuinely no next occurrence until it does,
                    // at which point the cascade-reschedule trigger recomputes this.
                    //
                    // The takeIf is what makes this branch honour
                    // notBeforeEpochMillis the way every other one does, and the
                    // way nextOccurrenceFor's own KDoc already promised. The
                    // anchor is the parent's *last* event, so once delayMinutes
                    // has elapsed the expression below is an instant in the
                    // *past*, and handing that back as "the next occurrence" had
                    // two consequences: RealSchedulingCoordinator armed an alarm
                    // at a past instant -- which AlarmManager fires immediately --
                    // on every reschedule after the child's own dose was
                    // answered; and the shared projection carried a stale past
                    // occurrence until the parent fired again, which is the
                    // reason NextRemindersWidget has to filter `epochMillis >=
                    // now` at all.
                    //
                    // A slot that has already passed is not a next occurrence.
                    // There isn't one until the parent fires or resolves again,
                    // and the cascade-reschedule trigger points recompute it then.
                    // No catch-up behaviour is lost that the other types offer:
                    // FixedTime rolls a missed slot to its next matching day, and
                    // both interval calculators step forward onto a future grid
                    // instant rather than replaying a past one.
                    anchorEpochMillis
                        ?.let { it + config.delayMinutes * 60_000L }
                        ?.takeIf { it >= notBeforeEpochMillis }
                        ?.let { NextOccurrence(it, config.dose) }
                }
            }

            // Unreachable given nextOccurrenceFor's supportedTypes guard; kept
            // explicit (not `else ->`) so adding a 7th ReminderConfig case
            // forces this file to be revisited instead of silently falling
            // through.
            is ReminderConfig.LowStockAlert, is ReminderConfig.ExpirationAlert -> null
        }

    /** Projects every occurrence of [reminder] in `[fromEpochMillis,
     *  toEpochMillis]`, by repeatedly calling [nextOccurrenceFor] and walking
     *  forward -- the same pure per-type calculators next-occurrence already
     *  uses, no separate range-aware math (spec §2/§9 #4: "call them
     *  repeatedly, walking forward, the way medTimer's SchedulingSimulator
     *  does"). [maxOccurrences] is a hard backstop against a pathological
     *  config (e.g. a 1-minute RepeatingInterval over a month-long window)
     *  looping far longer than any consumer actually needs. */
    suspend fun occurrencesFor(
        reminder: Reminder,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        maxOccurrences: Int = 500,
        weekendMode: WeekendMode? = null,
    ): List<NextOccurrence> {
        // Resolved once for the whole walk -- this is the call that made the
        // per-occurrence read expensive in the first place.
        val mode = weekendMode ?: readWeekendMode()
        val result = mutableListOf<NextOccurrence>()
        var notBefore = fromEpochMillis
        while (result.size < maxOccurrences) {
            val next = nextOccurrenceFor(reminder, notBefore, mode) ?: break
            if (next.epochMillis > toEpochMillis) break
            result.add(next)
            // LinkedFollowing has no "next after this one" within a single walk
            // -- its occurrence is anchored entirely to the parent's last event,
            // not to notBeforeEpochMillis, so advancing notBefore would just
            // return this exact same occurrence again up to maxOccurrences
            // times. One projected occurrence per walk is all this type can
            // honestly produce; the real next one only exists once this dose
            // fires/resolves and the cascade-reschedule trigger recomputes it.
            if (reminder.type == ReminderType.LINKED_FOLLOWING) break
            // +1 minute, same idiom ReminderAlarmReceiver uses -- guarantees
            // the next call can't return this exact slot again.
            notBefore = next.epochMillis + 60_000L
        }
        return result
    }
}
