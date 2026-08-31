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
    /** Reminder types this batch can actually schedule. LOW_STOCK_ALERT and
     *  EXPIRATION_ALERT are excluded on purpose -- they need periodic
     *  re-evaluation against live stock/date data, not a fixed next-occurrence
     *  chain, and are a deliberate follow-up rather than a gap in this logic. */
    val supportedTypes: Set<ReminderType> = setOf(
        ReminderType.FIXED_TIME,
        ReminderType.REPEATING_INTERVAL,
        ReminderType.DAILY_WINDOW,
        ReminderType.LINKED_FOLLOWING,
    )

    /** Next time [reminder] should fire at/after [notBeforeEpochMillis], or
     *  `null` if it has no more occurrences (disabled, an unsupported type
     *  for now, or a calculator-level "never matches" result). */
    suspend fun nextOccurrenceFor(
        reminder: Reminder,
        notBeforeEpochMillis: Long = nowEpochMillis(),
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
                    null // orphaned config -- shouldn't happen, but not this function's job to crash over
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
        // applyWeekendMode) when the global toggle is off, so a disabled
        // feature costs nothing beyond one Flow read.
        if (!settingsRepository.weekendModeEnabled.first()) return base
        return applyWeekendMode(
            base,
            settingsRepository.weekendDays.first(),
            settingsRepository.weekendStartTime.first(),
            settingsRepository.weekendEndTime.first(),
            zone,
        )
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
    ): List<NextOccurrence> {
        val result = mutableListOf<NextOccurrence>()
        var notBefore = fromEpochMillis
        while (result.size < maxOccurrences) {
            val next = nextOccurrenceFor(reminder, notBefore) ?: break
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
