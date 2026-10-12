package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.hazia.emivita.core.data.entity.DoseStatus

/**
 * Bridges the pure math in ReminderScheduling.kt to real reminder/dose-event
 * data, and is the ONLY place that should call the per-type calculators --
 * everything else (AlarmScheduler, RealSchedulingCoordinator, the boot
 * receiver) goes through [nextOccurrenceFor].
 *
 * Depends on [DoseEventDao] and the standalone [decodeReminderConfig] rather
 * than on ReminderRepository, which depends on SchedulingCoordinator and so
 * would create a cycle.
 */
class SchedulingEngine(
    private val doseEventDao: DoseEventDao,
    // LowStockAlert/ExpirationAlert schedule against live stock and expiry
    // (spec §7 R37), the way medTimer's OutOfStockScheduling and
    // ExpirationDateScheduling take the Medicine they belong to. A DAO, not
    // MedicineRepository -- the repository depends on SchedulingCoordinator,
    // which depends on this class, so the same cycle DoseEventDao is here to
    // avoid applies unchanged.
    private val medicineDao: MedicineDao,
    private val settingsRepository: SettingsRepository,
    private val fixedZone: ZoneId? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    // Read on every use, never captured (§7 R60 B F-4). These are lazy
    // singletons in AppContainer; a `ZoneId.systemDefault()` default parameter
    // evaluated once at construction meant every computation after a zone
    // change used the old zone until the process died. Tests pin a zone by
    // passing [fixedZone]; production leaves it null.
    private val zone: ZoneId get() = fixedZone ?: ZoneId.systemDefault()

    /** When a dose actually reached the person, by (reminder, dose event); see
     *  [DeliveryLedger]. AppContainer wires it; left unset, a follow-up counts
     *  from its parent's slot, as it did before §7 R83 (c). */
    @Volatile
    var deliveredAt: (reminderId: Long, doseEventId: Long) -> Long? = { _, _ -> null }

    /** The types that materialize a DoseEvent when they fire. These are the
     *  ones [OccurrenceProjectionCache] projects onto Today, History and the
     *  widgets, and the only ones whose occurrences carry a real dose amount. */
    val doseProducingTypes: Set<ReminderType> = setOf(
        ReminderType.FIXED_TIME,
        ReminderType.REPEATING_INTERVAL,
        ReminderType.DAILY_WINDOW,
        ReminderType.LINKED_FOLLOWING,
    )

    /** Reminder types that have a next-occurrence chain at all -- every type
     *  that can be handed to [AlarmScheduler], the two alert types included,
     *  as medTimer schedules them (§7 R37). Without a clock driving them, an
     *  expiry alert on a medicine with no dosing reminder (a box in a cupboard,
     *  which writes nothing) would only fire at boot or when the app opens. */
    val supportedTypes: Set<ReminderType> = doseProducingTypes + setOf(
        ReminderType.LOW_STOCK_ALERT,
        ReminderType.EXPIRATION_ALERT,
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
     *  [Reminder.activeUntilEpochDay]), a guarantee every branch of
     *  [computeBase] honours, LinkedFollowing included.
     *
     *  A reminder whose [Reminder.activeFromEpochDay] has not arrived yet is
     *  deferred to the first occurrence on or after the day the range opens.
     *  A null [weekendMode] means "read it yourself" after the clamps (a
     *  suspend call cannot be a default value), so pass a snapshot when
     *  calling repeatedly. */
    suspend fun nextOccurrenceFor(
        reminder: Reminder,
        notBeforeEpochMillis: Long = nowEpochMillis(),
        weekendMode: WeekendMode? = null,
        assumeOutstandingResolved: Boolean = false,
    ): NextOccurrence? {
        if (!reminder.enabled) return null
        if (reminder.type !in supportedTypes) return null
        // A dose-count course that has run out (§9 #26, MT-07) is over the way
        // a passed activeUntil is over: nothing more to arm, nothing projected.
        if (reminder.dosesRemaining == 0) return null

        var base = computeBase(reminder, notBeforeEpochMillis, assumeOutstandingResolved) ?: return null

        // activeFrom/activeUntil clamp (spec §3/§4): after the type-specific
        // calculator, before weekend mode, with null meaning unbounded on that
        // side. The bounds are not symmetric: past activeUntil there is never a
        // next occurrence, but a future activeFrom means "not yet", and nothing
        // re-arms a reminder on the passage of time alone (rescheduleAll() runs
        // at boot and app-update, the projection on data changes). So the
        // calculator is re-run from the day the range opens, which is what
        // keeps §4's courses and tapers (adjacent FixedTime ranges) working.
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
            base = computeBase(reminder, opensAtEpochMillis, assumeOutstandingResolved) ?: return null
            if (base.epochDayIn(zone) < activeFrom) return null
        }

        reminder.activeUntilEpochDay?.let { if (base.epochDayIn(zone) > it) return null }

        // Weekend mode (spec §3, §7 R36) is the last step and opt-in per
        // reminder, unlike medTimer's global decorator, so a supplement that can
        // wait until 11:00 on a Sunday is told apart from a drug that cannot:
        // SettingsRepository owns the days and window, weekendModeOptIn whether
        // this reminder is subject to them. The per-reminder gate comes first
        // so a reminder that has not opted in never reads DataStore at all.
        if (!reminder.weekendModeOptIn) return base
        return when (val mode = weekendMode ?: readWeekendMode()) {
            is WeekendMode.Off -> base
            is WeekendMode.On -> applyWeekendMode(base, mode.days, mode.start, mode.end, zone)
        }
    }

    private fun NextOccurrence.epochDayIn(zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay()

    /** The anchor an ON_RESOLVED interval or window continues from, or
     *  [Outstanding] when a dose is still unanswered (§7 R60), because reading
     *  [DoseEventDao.getLastResolved] alone skips PENDING and any reschedule
     *  would then arm a second alarm and a second PENDING dose, the state in
     *  which medTimer's schedulers also return null.
     *
     *  The projection walk instead passes [assumeOutstandingResolved], treating
     *  the unanswered dose as taken at its slot as medTimer's simulator does,
     *  so the stock run-out date does not jump while a dose waits. */
    private sealed interface ResolvedAnchor {
        data class At(val epochMillis: Long) : ResolvedAnchor
        data object Outstanding : ResolvedAnchor
    }

    private suspend fun resolvedAnchorFor(reminder: Reminder, assumeOutstandingResolved: Boolean): ResolvedAnchor {
        val mostRecent = doseEventDao.getMostRecentForReminder(reminder.id)
        if (mostRecent != null && mostRecent.status == DoseStatus.PENDING) {
            return if (assumeOutstandingResolved) ResolvedAnchor.At(mostRecent.scheduledEpochMillis) else ResolvedAnchor.Outstanding
        }
        return ResolvedAnchor.At(
            doseEventDao.getLastResolved(reminder.id)?.resolvedAtEpochMillis ?: reminder.createdAtEpochMillis,
        )
    }

    /** The type-specific calculators alone, before any of [nextOccurrenceFor]'s
     *  cross-cutting clamps. Split out of it specifically so the activeFrom
     *  clamp can re-run the calculation from the day the active range opens
     *  rather than having to answer "never". */
    private suspend fun computeBase(
        reminder: Reminder,
        notBeforeEpochMillis: Long,
        assumeOutstandingResolved: Boolean,
    ): NextOccurrence? =
        when (val config = decodeReminderConfig(reminder.configJson)) {
            is ReminderConfig.FixedTime ->
                nextFixedTimeOccurrence(config, notBeforeEpochMillis, zone)

            is ReminderConfig.RepeatingInterval -> {
                val effectiveAnchor = when (config.startBehavior) {
                    IntervalStartBehavior.ON_REMINDED -> config.anchorEpochMillis
                    IntervalStartBehavior.ON_RESOLVED ->
                        when (val anchor = resolvedAnchorFor(reminder, assumeOutstandingResolved)) {
                            is ResolvedAnchor.At -> anchor.epochMillis
                            ResolvedAnchor.Outstanding -> return null
                        }
                }
                nextRepeatingIntervalOccurrence(config, notBeforeEpochMillis, effectiveAnchor)
            }

            is ReminderConfig.DailyWindow -> {
                val lastResolvedEpochMillis = if (config.startBehavior == IntervalStartBehavior.ON_RESOLVED) {
                    when (val anchor = resolvedAnchorFor(reminder, assumeOutstandingResolved)) {
                        is ResolvedAnchor.At -> anchor.epochMillis
                        ResolvedAnchor.Outstanding -> return null
                    }
                } else {
                    null
                }
                nextDailyWindowOccurrence(config, notBeforeEpochMillis, lastResolvedEpochMillis, zone)
            }

            is ReminderConfig.LinkedFollowing -> {
                val parentId = reminder.parentReminderId
                if (parentId == null) {
                    // Orphaned child: the FK on Reminder.parentReminderId is ON
                    // DELETE SET_NULL (§7 R25), and ReminderRepository.delete()
                    // disables every child it detaches. This guard is still
                    // load-bearing because BackupRepository.restoreAll() writes
                    // rows back verbatim, so a backup missing the parent row (or
                    // any row predating R25) restores an enabled, parentless child.
                    null
                } else {
                    val anchorEpochMillis = when (config.startBehavior) {
                        // From when the parent really rang, if the phone held it
                        // back past the slot (§7 R83 (c)); on time, the slot.
                        IntervalStartBehavior.ON_REMINDED -> doseEventDao.getMostRecentForReminder(parentId)?.let { parent ->
                            deliveredAt(parentId, parent.id)?.takeIf { it > parent.scheduledEpochMillis + DeliveryLedger.LATE_MILLIS }
                                ?: parent.scheduledEpochMillis
                        }
                        IntervalStartBehavior.ON_RESOLVED -> doseEventDao.getLastResolved(parentId)?.resolvedAtEpochMillis
                    }
                    // No anchor means the parent hasn't fired/resolved yet, so
                    // there is no next occurrence until the cascade-reschedule
                    // trigger recomputes this. The takeIf keeps the at/after
                    // notBeforeEpochMillis guarantee: the anchor is the parent's
                    // *last* event, so a passed slot would arm an alarm in the
                    // past (AlarmManager fires it at once) and leave a stale
                    // occurrence in the projection. A passed slot is simply not
                    // a next occurrence, just as FixedTime and both interval
                    // calculators step forward rather than replay one.
                    anchorEpochMillis
                        ?.let { it + config.delayMinutes * 60_000L }
                        ?.takeIf { it >= notBeforeEpochMillis }
                        ?.let { NextOccurrence(it, config.dose) }
                }
            }

            // Both alert types read the medicine they belong to rather than
            // dose history -- their condition is "is stock below the line" and
            // "is the expiry date within lead time", neither of which any
            // DoseEvent records. A missing medicine yields null rather than
            // throwing: the FK makes it unreachable in a consistent database,
            // and a restored backup is not guaranteed to be one.
            is ReminderConfig.LowStockAlert -> nextLowStockAlertOccurrence(
                config = config,
                notBeforeEpochMillis = notBeforeEpochMillis,
                stockQuantity = medicineDao.getById(reminder.medicineId)
                    ?.stockInfo
                    // unit == null is the app-wide "stock tracking is off for
                    // this medicine" flag (see Medicine.doseUnit's comment on
                    // why that nullability is load-bearing), and an untracked
                    // medicine has no threshold to cross.
                    ?.let { if (it.unit == null) null else it.quantity },
                lastNotifiedEpochDay = reminder.lastAlertNotifiedEpochDay,
                zone = zone,
            )

            is ReminderConfig.ExpirationAlert -> nextExpirationAlertOccurrence(
                config = config,
                notBeforeEpochMillis = notBeforeEpochMillis,
                expirationEpochDay = medicineDao.getById(reminder.medicineId)?.stockInfo?.effectiveExpirationEpochDay,
                lastNotifiedEpochDay = reminder.lastAlertNotifiedEpochDay,
                zone = zone,
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
        weekendMode: WeekendMode? = null,
    ): List<NextOccurrence> {
        // Resolved once for the whole walk -- this is the call that made the
        // per-occurrence read expensive in the first place.
        val mode = weekendMode ?: readWeekendMode()
        val result = mutableListOf<NextOccurrence>()
        var notBefore = fromEpochMillis
        while (result.size < maxOccurrences) {
            val next = nextOccurrenceFor(reminder, notBefore, mode, assumeOutstandingResolved = true) ?: break
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
