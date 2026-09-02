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
     *  for now, or a calculator-level "never matches" result).
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

        val base = when (val config = decodeReminderConfig(reminder.configJson)) {
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
                    // does. Returning null here means such a child keeps its
                    // row, keeps enabled = true, keeps showing in the medicine's
                    // reminder list, and never fires again -- silently. Nothing
                    // reaches this today only because ReminderRepository.delete()
                    // has no call sites (deleting a medicine cascades the whole
                    // set instead, parent and children together). Wiring a
                    // per-reminder delete makes it reachable. See docs/SPEC.md
                    // §7 R23: the FK's semantics and §3's "ON DELETE CASCADE"
                    // still contradict each other, and that is the decision to
                    // settle before this path can be reached.
                    null
                } else {
                    val anchorEpochMillis = when (config.startBehavior) {
                        IntervalStartBehavior.ON_REMINDED -> doseEventDao.getMostRecentForReminder(parentId)?.scheduledEpochMillis
                        IntervalStartBehavior.ON_RESOLVED -> doseEventDao.getLastResolved(parentId)?.resolvedAtEpochMillis
                    }
                    // Parent hasn't fired/resolved yet -- nothing to follow. Not
                    // "unbounded," just genuinely no next occurrence until it does,
                    // at which point the cascade-reschedule trigger recomputes this.
                    anchorEpochMillis?.let { NextOccurrence(it + config.delayMinutes * 60_000L, config.dose) }
                }
            }

            // Unreachable given the supportedTypes guard above; kept explicit
            // (not `else ->`) so adding a 7th ReminderConfig case forces this
            // file to be revisited instead of silently falling through.
            is ReminderConfig.LowStockAlert, is ReminderConfig.ExpirationAlert -> null
        } ?: return null

        // activeFrom/UntilEpochDay clamp (spec §3/§4) -- after the type-specific
        // calculator, before weekend mode, per §4's explicit ordering ("weekend
        // mode is applied... after the type-specific calculator and the
        // activeFrom/UntilEpochDay clamp"). Null on either side = unbounded on
        // that side (today's default behavior, unchanged for every reminder
        // that doesn't set these). A taper schedule is just several ordinary
        // FixedTime reminders with adjacent, non-overlapping ranges -- no new
        // ReminderConfig type needed, this clamp is the whole mechanism.
        val candidateEpochDay = Instant.ofEpochMilli(base.epochMillis).atZone(zone).toLocalDate().toEpochDay()
        reminder.activeFromEpochDay?.let { if (candidateEpochDay < it) return null }
        reminder.activeUntilEpochDay?.let { if (candidateEpochDay > it) return null }

        // Weekend mode (spec §3) -- the last step, applied uniformly with no
        // per-reminder opt-out. Skipped entirely (not just a no-op inside
        // applyWeekendMode) when the global toggle is off.
        return when (val mode = weekendMode ?: readWeekendMode()) {
            is WeekendMode.Off -> base
            is WeekendMode.On -> applyWeekendMode(base, mode.days, mode.start, mode.end, zone)
        }
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
