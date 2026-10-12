package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.dao.MedicineDao
import android.util.Log
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.Reminder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "OccurrenceProjectionCache"

private const val MINIMUM_WINDOW_DAYS = 28L
private const val DEBOUNCE_MILLIS = 1_000L
private const val COMPUTE_BUDGET_MILLIS = 2_000L

/** One projected reminder firing within the cache's current window. */
data class ProjectedOccurrence(
    val reminderId: Long,
    val medicineId: Long,
    val epochMillis: Long,
    val doseAmount: Double,
    /** A linked child hung off its parent's *projected* slot (§9 #31, A-24):
     *  the parent has not fired, so this is where the child will land if it
     *  does on time. Shown with "≈"; replaced by the real slot once the
     *  parent's dose exists. */
    val estimated: Boolean = false,
    /** For an estimate, the parent slot it follows. */
    val afterEpochMillis: Long? = null,
)

data class ProjectionState(
    val occurrences: List<ProjectedOccurrence> = emptyList(),
    // medicineId -> the date its tracked stock is projected to run out. Only
    // present for medicines with stock tracking active (a unit set) whose
    // run-out falls inside the current window.
    val stockRunOutDates: Map<Long, LocalDate> = emptyMap(),
    val windowDays: Long = MINIMUM_WINDOW_DAYS,
    val computedAtEpochMillis: Long = 0L,
    /**
     * True when [COMPUTE_BUDGET_MILLIS] stopped the pass before every medicine
     * was visited, so this state covers only some of them (spec §7 R58).
     *
     * Without it a medicine absent from [stockRunOutDates] could mean "will not
     * run out inside the window" or "was never reached". Consumers that draw a
     * conclusion from an *absence* check this first, while those reading only
     * what is present can ignore it.
     */
    val truncated: Boolean = false,
    /**
     * True on the states published mid-pass (§9 #31, A-45): the medicines
     * visited so far are here, the rest are not yet. Today paints the first
     * rows before the last medicine is computed; a consumer drawing a
     * conclusion from an *absence* waits for the state where this is false,
     * the same rule [truncated] already asks of it.
     */
    val inProgress: Boolean = false,
)

/**
 * Singleton forward-simulation cache (spec §2's locked decision, §9 #4;
 * medTimer's SimulatedRemindersRepository): one shared projection of reminder
 * occurrences and each tracked medicine's stock run-out date, read through
 * [state] by Today, History/Calendar and Widgets (§9 #5-7) instead of each
 * running its own simulation, while LowStockAlert/ExpirationAlert triggering
 * (§9 #8) rides the same pass via [alertEvaluator].
 *
 * The window is the max of every [requestWindow] registration and
 * [MINIMUM_WINDOW_DAYS], a floor and never a fallback, because the projection
 * is shared and one consumer's short request must not shrink it for every
 * other reader (§7 R28), and [releaseWindow] waits for the next trigger
 * rather than recomputing.
 *
 * Two invalidation channels feed one conflated [trigger], so a burst of edits
 * costs one recompute capped at [COMPUTE_BUDGET_MILLIS]: Room re-fires
 * [MedicineDao.observeAllWithReminders] on any `medicines`/`reminders` write,
 * and `DoseEventRepository` and `RealSchedulingCoordinator` call [invalidate]
 * because a dose resolution needs the next-alarm recompute whether or not
 * that Flow fires.
 */
class OccurrenceProjectionCache(
    private val medicineDao: MedicineDao,
    private val schedulingEngine: SchedulingEngine,
    private val alertEvaluator: LowStockExpirationAlertEvaluator,
    private val scope: CoroutineScope,
    private val fixedZone: ZoneId? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    // Injectable for the same reason [fixedZone] and [nowEpochMillis] already are:
    // the debounce below is real wall-clock time on Dispatchers.Default, which
    // a test cannot advance. Production never passes this.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    // Read on every use, never captured (§7 R60 B F-4). These are lazy
    // singletons in AppContainer; a `ZoneId.systemDefault()` default parameter
    // evaluated once at construction meant every computation after a zone
    // change used the old zone until the process died. Tests pin a zone by
    // passing [fixedZone]; production leaves it null.
    private val zone: ZoneId get() = fixedZone ?: ZoneId.systemDefault()

    private val windowRequests = ConcurrentHashMap<String, Long>()
    private val trigger = Channel<Unit>(capacity = Channel.CONFLATED)

    private val _state = MutableStateFlow(ProjectionState())
    val state: StateFlow<ProjectionState> = _state.asStateFlow()

    init {
        scope.launch(dispatcher) {
            for (unused in trigger) {
                delay(DEBOUNCE_MILLIS)
                // A failed pass must cost one pass, not the loop. An escaping
                // exception cancels this coroutine for good (AppContainer's
                // SupervisorJob does not restart it), so [state] would freeze
                // Today, History/Calendar, both widgets and the low-stock and
                // expiration alerts with no visible failure. Nothing is expected
                // to throw (the calculators return null for invalid configs), but
                // every projection consumer depends on this one loop.
                runCatching { recompute() }
                    .onFailure { Log.e(TAG, "Projection recompute failed -- keeping the last good state until the next trigger.", it) }
            }
        }
        scope.launch(dispatcher) {
            medicineDao.observeAllWithReminders().collect { invalidate() }
        }
        invalidate()
    }

    /** Registers/updates [consumerId]'s required window in days. Can only
     *  ever grow the effective window, never shrink it below the floor --
     *  see [effectiveWindowDays]. Triggers a recompute either way, since a
     *  registration that does grow the window needs one immediately and the
     *  conflated channel makes a redundant trigger nearly free. */
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

    /** The max of every current registration and [MINIMUM_WINDOW_DAYS]. The
     *  constant is a floor, not an empty-map fallback: a consumer asking for
     *  fewer days than the floor is stating a minimum that is already met,
     *  and must never drag the shared projection down to it. */
    private fun effectiveWindowDays(): Long =
        maxOf(windowRequests.values.maxOrNull() ?: 0L, MINIMUM_WINDOW_DAYS)

    private suspend fun recompute() {
        val start = nowEpochMillis()
        val windowDays = effectiveWindowDays()
        val toEpochMillis = start + windowDays * 24 * 60 * 60 * 1000L

        val medicines = medicineDao.getAllWithReminders()
        // Read once for the whole pass rather than per projected occurrence:
        // SchedulingEngine.occurrencesFor walks up to maxOccurrences per
        // reminder, and each weekend-mode lookup is a fresh DataStore read.
        val weekendMode = schedulingEngine.readWeekendMode()
        val occurrences = mutableListOf<ProjectedOccurrence>()
        val runOutDates = mutableMapOf<Long, LocalDate>()

        var visited = 0
        var truncated = false
        // Hard time budget, checked before every reminder's walk (§9 #31,
        // A-41; it used to be once per medicine, which let one pathological
        // reminder drag its medicine's remaining reminders past the budget
        // with it). A trip stops the pass; [state] still gets everything
        // projected so far and says so via ProjectionState.truncated rather
        // than passing a partial answer off as a whole one.
        fun overBudget(reminder: Reminder): Boolean {
            if (nowEpochMillis() - start <= COMPUTE_BUDGET_MILLIS) return false
            Log.w(TAG, "Projection hit the ${COMPUTE_BUDGET_MILLIS}ms budget at reminder ${reminder.id} after $visited reminders -- publishing a truncated state.")
            truncated = true
            return true
        }
        fun publish(inProgress: Boolean) {
            _state.value = ProjectionState(
                occurrences = occurrences.sortedBy { it.epochMillis },
                stockRunOutDates = runOutDates.toMap(),
                windowDays = windowDays,
                computedAtEpochMillis = start,
                truncated = truncated,
                inProgress = inProgress,
            )
        }
        for (entry in medicines) {

            // Two passes per medicine (§9 #31, A-24): first every dose reminder
            // that computes its own slots, then linked children, whose one real
            // slot is anchored to the parent's most recent real event, plus an
            // *estimate* after every later projected parent slot so Today and
            // the stock run-out see the follow-up dose before the parent fires.
            // Parents are same-medicine and one level deep (A-23): a child of a
            // child estimates off nothing.
            val medicineOccurrences = mutableListOf<ProjectedOccurrence>()
            val projectedByReminder = mutableMapOf<Long, List<Long>>()
            val (linked, own) = entry.reminders.filter { it.enabled }.partition { it.type == ReminderType.LINKED_FOLLOWING }
            for (reminder in own) {
                // Dose-producing types only. Since spec §7 R37 the alert types
                // are schedulable too, so occurrencesFor would happily return
                // their next firing -- but an alert is not a dose: it would
                // appear on Today's timeline as a zero-amount entry and be
                // subtracted from the stock run-out walk below. They reach
                // AlarmManager through SchedulingCoordinator like everything
                // else; they just don't belong in a *dose* projection.
                if (reminder.type !in schedulingEngine.doseProducingTypes) continue
                if (overBudget(reminder)) break
                visited++
                val forReminder = schedulingEngine.occurrencesFor(reminder, start, toEpochMillis, weekendMode = weekendMode)
                projectedByReminder[reminder.id] = forReminder.map { it.epochMillis }
                forReminder.forEach { occurrence ->
                    medicineOccurrences += ProjectedOccurrence(reminder.id, entry.medicine.id, occurrence.epochMillis, occurrence.doseAmount)
                }
            }
            for (child in linked) {
                if (truncated) break
                val config = decodeReminderConfig(child.configJson) as? ReminderConfig.LinkedFollowing ?: continue
                if (overBudget(child)) break
                visited++
                val parentSlots = child.parentReminderId?.let { projectedByReminder[it] } ?: emptyList()
                val real = schedulingEngine.occurrencesFor(child, start, toEpochMillis, weekendMode = weekendMode).firstOrNull()
                if (real != null) medicineOccurrences += ProjectedOccurrence(child.id, entry.medicine.id, real.epochMillis, real.doseAmount)
                val delayMillis = config.delayMinutes * 60_000L
                parentSlots
                    .filter { slot -> real == null || slot + delayMillis > real.epochMillis }
                    .forEach { slot ->
                        val at = slot + delayMillis
                        if (at in start..toEpochMillis) {
                            medicineOccurrences += ProjectedOccurrence(child.id, entry.medicine.id, at, config.dose, estimated = true, afterEpochMillis = slot)
                        }
                    }
            }
            occurrences += medicineOccurrences

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
            // Progressive emission (§9 #31, A-45; medTimer publishes at each
            // day boundary): what is projected so far, flagged as such. A
            // budget trip keeps this medicine's walked reminders -- the whole
            // point of the check is a partial answer, not a lost one.
            if (truncated) break
            publish(inProgress = true)
        }

        publish(inProgress = false)

        // LowStockAlert/ExpirationAlert triggering (spec §9 #8) -- piggybacks on
        // this same recompute pass rather than a separate periodic job; reuses
        // the medicines-with-reminders snapshot already fetched above.
        alertEvaluator.evaluate(medicines)
    }
}
