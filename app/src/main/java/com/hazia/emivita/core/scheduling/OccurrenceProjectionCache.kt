package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.dao.MedicineDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_WINDOW_DAYS = 28L
private const val DEBOUNCE_MILLIS = 1_000L
private const val COMPUTE_BUDGET_MILLIS = 2_000L

/** One projected reminder firing within the cache's current window. */
data class ProjectedOccurrence(
    val reminderId: Long,
    val medicineId: Long,
    val epochMillis: Long,
    val doseAmount: Double,
)

data class ProjectionState(
    val occurrences: List<ProjectedOccurrence> = emptyList(),
    // medicineId -> the date its tracked stock is projected to run out. Only
    // present for medicines with stock tracking active (a unit set) whose
    // run-out falls inside the current window.
    val stockRunOutDates: Map<Long, LocalDate> = emptyMap(),
    val windowDays: Long = DEFAULT_WINDOW_DAYS,
    val computedAtEpochMillis: Long = 0L,
)

/**
 * Singleton forward-simulation cache (spec §2's locked decision, resolved
 * design in §9 #4) -- the Emivita equivalent of medTimer's
 * SimulatedRemindersRepository/SchedulingSimulator, confirmed directly against
 * medTimer's own CONTEXT.md glossary rather than assumed: one shared
 * projection of reminder occurrences over a requested window, computing each
 * tracked medicine's stock run-out date as a side effect of the same pass. The
 * Today screen, History/Calendar, and Widgets (§9 #5-7) all read from [state]
 * instead of each running its own simulation -- the exact mistake medTimer's
 * own ADRs document making and having to undo (§2). LowStockAlert/
 * ExpirationAlert triggering (§9 #8) rides the same recompute pass rather
 * than reading [state] itself -- see [alertEvaluator].
 *
 * **Window ownership** (medTimer's own glossary, verbatim): each consumer
 * registers its required days via [requestWindow]; the effective window is
 * the max across all current registrations, [DEFAULT_WINDOW_DAYS] if none.
 * [releaseWindow] removes a registration but does not force an immediate
 * recompute -- shrinking isn't urgent, it takes effect on the next trigger
 * that happens anyway.
 *
 * **Two invalidation channels, not one:**
 *  - *Reactive:* the `init` block collects [MedicineDao.observeAllWithReminders]
 *    (a `@Relation`-joined query built specifically for this watcher, see
 *    [com.hazia.emivita.core.data.dao.MedicineWithReminders]) -- Room's own
 *    change-tracking re-fires it on any write to `medicines` or `reminders`,
 *    so a medicine/reminder edit invalidates the cache without the editing
 *    code needing to know the cache exists.
 *  - *Explicit:* [invalidate] is called directly by `DoseEventRepository`
 *    (dose resolution, PRN logging, refill) and `RealSchedulingCoordinator`
 *    (after an alarm-fired reschedule) -- a dose resolution needs the
 *    next-alarm recompute regardless of whether the reactive Flow happens to
 *    fire from the same write.
 *
 * Both funnel into the same conflated [trigger] channel, so a burst of edits
 * collapses into one recompute rather than one per edit (medTimer: a
 * conflated channel + 1s delay), and the computation itself is capped at
 * [COMPUTE_BUDGET_MILLIS] -- a pathological reminder config stops the pass
 * and [state] publishes whatever was projected so far rather than blocking
 * indefinitely.
 */
class OccurrenceProjectionCache(
    private val medicineDao: MedicineDao,
    private val schedulingEngine: SchedulingEngine,
    private val alertEvaluator: LowStockExpirationAlertEvaluator,
    private val scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val windowRequests = ConcurrentHashMap<String, Long>()
    private val trigger = Channel<Unit>(capacity = Channel.CONFLATED)

    private val _state = MutableStateFlow(ProjectionState())
    val state: StateFlow<ProjectionState> = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.Default) {
            for (unused in trigger) {
                delay(DEBOUNCE_MILLIS)
                recompute()
            }
        }
        scope.launch(Dispatchers.Default) {
            medicineDao.observeAllWithReminders().collect { invalidate() }
        }
        invalidate()
    }

    /** Registers/updates [consumerId]'s required window in days -- growing
     *  the effective window triggers an immediate recompute. */
    fun requestWindow(consumerId: String, days: Long) {
        windowRequests[consumerId] = days
        invalidate()
    }

    /** Releases [consumerId]'s window claim. See the class doc for why this
     *  deliberately doesn't force a recompute. */
    fun releaseWindow(consumerId: String) {
        windowRequests.remove(consumerId)
    }

    /** Explicit invalidation -- see the class doc's "Two invalidation
     *  channels" section. Non-blocking: coalesces with any already-pending
     *  trigger rather than queuing a second recompute. */
    fun invalidate() {
        trigger.trySend(Unit)
    }

    private fun effectiveWindowDays(): Long = windowRequests.values.maxOrNull() ?: DEFAULT_WINDOW_DAYS

    private suspend fun recompute() {
        val start = nowEpochMillis()
        val windowDays = effectiveWindowDays()
        val toEpochMillis = start + windowDays * 24 * 60 * 60 * 1000L

        val medicines = medicineDao.observeAllWithReminders().first()
        val occurrences = mutableListOf<ProjectedOccurrence>()
        val runOutDates = mutableMapOf<Long, LocalDate>()

        for (entry in medicines) {
            // Hard time budget -- a pathological config stops the pass here
            // rather than blocking; [state] below still gets whatever was
            // projected for the medicines already visited.
            if (nowEpochMillis() - start > COMPUTE_BUDGET_MILLIS) break

            val medicineOccurrences = mutableListOf<NextOccurrence>()
            for (reminder in entry.reminders) {
                if (!reminder.enabled) continue
                val forReminder = schedulingEngine.occurrencesFor(reminder, start, toEpochMillis)
                medicineOccurrences += forReminder
                forReminder.forEach { occurrence ->
                    occurrences += ProjectedOccurrence(reminder.id, entry.medicine.id, occurrence.epochMillis, occurrence.doseAmount)
                }
            }

            if (entry.medicine.stockInfo.unit != null) {
                var remaining = entry.medicine.stockInfo.quantity
                for (occurrence in medicineOccurrences.sortedBy { it.epochMillis }) {
                    remaining -= occurrence.doseAmount
                    if (remaining <= 0.0) {
                        runOutDates[entry.medicine.id] = Instant.ofEpochMilli(occurrence.epochMillis).atZone(zone).toLocalDate()
                        break
                    }
                }
            }
        }

        _state.value = ProjectionState(
            occurrences = occurrences.sortedBy { it.epochMillis },
            stockRunOutDates = runOutDates,
            windowDays = windowDays,
            computedAtEpochMillis = start,
        )

        // LowStockAlert/ExpirationAlert triggering (spec §9 #8) -- piggybacks on
        // this same recompute pass rather than a separate periodic job; reuses
        // the medicines-with-reminders snapshot already fetched above.
        alertEvaluator.evaluate(medicines)
    }
}
