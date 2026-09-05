package com.hazia.emivita.core.data.repository

import androidx.room.withTransaction
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import kotlinx.coroutines.flow.Flow

/**
 * The ONLY place a DoseEvent's status should ever be written from -- the
 * architecture review flagged that the notification action, the full-screen
 * alarm screen, and the Today screen's inline buttons all need to resolve a
 * dose, and if each reimplemented "mark taken" independently they'd drift
 * (one forgets to decrement stock, another forgets to reschedule). Every one
 * of those call sites should call [resolve] here and nothing else.
 */
class DoseEventRepository(
    private val database: EmivitaDatabase,
    private val doseEventDao: DoseEventDao,
    private val medicineDao: MedicineDao,
    private val schedulingCoordinator: SchedulingCoordinator,
    private val occurrenceProjectionCache: OccurrenceProjectionCache,
    private val autoSyncManager: AutoSyncManager,
) {
    fun observeInRange(profileId: Long, startEpochMillis: Long, endEpochMillis: Long): Flow<List<DoseEventWithMedicine>> =
        doseEventDao.observeInRange(profileId, startEpochMillis, endEpochMillis)

    suspend fun getById(id: Long): DoseEvent? = doseEventDao.getById(id)

    /** Looks up an already-materialized event for a reminder+time slot --
     *  used by the alarm receiver when [materializeIfAbsent] returns null
     *  (a duplicate alarm delivery for a slot that already exists), so it
     *  can still re-check the existing event's status. */
    suspend fun findByReminderAndTime(reminderId: Long, scheduledEpochMillis: Long): DoseEvent? =
        doseEventDao.findByReminderAndTime(reminderId, scheduledEpochMillis)

    /** Nagging (spec §3/§4 `repeatOnUnanswered`) -- called by
     *  ReminderAlarmReceiver.handleNagFire once per nag re-fire, before it
     *  decides whether to schedule another one. */
    suspend fun incrementRepeatCount(doseEventId: Long) = doseEventDao.incrementRepeatCount(doseEventId)

    /** Idempotent -- used by the scheduling engine's materialization pass.
     *  Null return means "already materialized," covering both the ordinary
     *  pre-check above AND a losing race on the unique index: `@Insert(onConflict
     *  = IGNORE)` returns `-1` (Room's documented sentinel for a suppressed
     *  conflict on a non-null Long return type), not null and not a throw, so
     *  that value has to be translated here rather than handed to callers as
     *  if it were a real row id -- ReminderAlarmReceiver.handleAlarm's
     *  `doseEventId != null` branch would otherwise call getById(-1), get
     *  null back, and fall into its "could not materialize or find" error
     *  path instead of the intended "duplicate delivery, re-show existing
     *  notification" one. */
    suspend fun materializeIfAbsent(reminderId: Long, medicineId: Long, scheduledEpochMillis: Long, doseAmount: Double): Long? {
        val existing = doseEventDao.findByReminderAndTime(reminderId, scheduledEpochMillis)
        if (existing != null) return null
        val insertedId = doseEventDao.insert(
            DoseEvent(
                reminderId = reminderId,
                medicineId = medicineId,
                scheduledEpochMillis = scheduledEpochMillis,
                doseAmount = doseAmount,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        )
        return insertedId.takeIf { it != -1L }
    }

    /** The single entry point for Taken / Skipped from any screen or receiver.
     *
     *  [resolvedAtEpochMillis] defaults to now, which is right for every live
     *  path -- a notification action, the alarm screen, Today's chips -- where
     *  answering and acting are the same moment. It is passed explicitly only
     *  when correcting the record after the fact, where they are not: a dose
     *  taken on Tuesday and only recorded on Friday happened on Tuesday, and
     *  the History this app exists to hand a clinician should say so (spec §7
     *  R46).
     *
     *  That parameter is also what makes retroactive resolution safe for
     *  scheduling, which is the reason it is a parameter rather than a second
     *  method that stamps now. `DoseEventDao.getLastResolved` picks a
     *  reminder's ON_RESOLVED anchor with `ORDER BY resolvedAtEpochMillis
     *  DESC`, so a truthful backdated resolution cannot displace a genuinely
     *  newer one -- and where it *is* the newest, it is also the truth about
     *  when the medicine was last taken, which is exactly what that anchor
     *  means. Stamping now instead would silently re-anchor a live interval
     *  reminder to the moment someone tidied up their history.
     *  Resolvable from three independent surfaces (notification action,
     *  full-screen alarm, Today's inline buttons), so a second call for an
     *  event that's already been resolved has to be a safe no-op rather than
     *  re-applying the stock change a second time for one real dose -- the
     *  PENDING check below is read and enforced inside the same transaction
     *  as the write, not against a snapshot taken before it opens, so two
     *  near-simultaneous calls can't both pass it. */
    suspend fun resolve(
        doseEventId: Long,
        status: DoseStatus,
        resolvedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        require(status != DoseStatus.PENDING) { "resolve() is for Taken/Skipped, not re-setting Pending" }

        val resolvedEvent = database.withTransaction {
            val event = doseEventDao.getById(doseEventId)
                ?: error("No DoseEvent with id=$doseEventId")
            if (event.status != DoseStatus.PENDING) return@withTransaction null

            doseEventDao.updateStatus(doseEventId, status, resolvedAtEpochMillis)
            // Denormalized medicineId (spec §3) -- works for both scheduled
            // events (kept in sync with reminder.medicineId at materialization
            // time) and PRN events, which have no reminder to derive it from.
            val sentinels = hypotheticalStockSentinels(event.medicineId, event.doseAmount)
            if (sentinels != null) {
                doseEventDao.updateStockSentinels(doseEventId, sentinels.first, sentinels.second)
                // Only actually apply the quantity change for TAKEN -- SKIPPED
                // still records "what it would have been" so a later
                // switchStatus() (spec §9 #3) has a real value to reapply.
                if (status == DoseStatus.TAKEN) {
                    medicineDao.setStock(event.medicineId, sentinels.second)
                }
            }
            event
        } ?: return

        // Outside the DB transaction on purpose -- scheduling the next alarm is
        // an OS-level side effect, not a database write, and shouldn't roll back
        // the (already-committed) status change if it fails. PRN events
        // (reminderId == null) aren't tied to any scheduled occurrence, so
        // there's nothing to reschedule.
        resolvedEvent.reminderId?.let {
            schedulingCoordinator.onDoseResolved(it)
            // Nagging stops the moment a dose is genuinely answered (spec
            // §3/§4 repeatOnUnanswered) -- the other way it stops is hitting
            // maxRepeats, handled entirely on the nag-fire side
            // (ReminderAlarmReceiver.handleNagFire). Not called from
            // switchStatus() -- by the time that runs the event is already
            // resolved, so nagging already stopped here.
            schedulingCoordinator.cancelNag(it)
        }

        // An alert reminder's next firing is computed from this medicine's
        // stock and expiry, so a change to either can move, cancel or create
        // its alarm with nothing about the reminder row itself having
        // changed -- and the projection cache, which is what invalidate()
        // below drives, does not touch AlarmManager. Spec §7 R37.
        schedulingCoordinator.onMedicineChanged(resolvedEvent.medicineId)

        // Explicit invalidation channel (spec §9 #4) -- a dose resolution needs
        // the projection cache's stock run-out date recomputed regardless of
        // whether the stock write above happens to also trip the cache's
        // reactive Flow watcher.
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    /** Taken<->Skipped switch (spec §3/§9 #3), stock-correct: applies the
     *  quantity this dose actually consumed as a *relative* adjustment, taken
     *  from the sentinel pair [resolve] recorded at resolution time.
     *
     *  It used to write that pair back as an absolute quantity --
     *  `setStock(stockAfter)` for Taken, `setStock(stockBefore)` for Skipped --
     *  which silently discarded every stock change made since the dose was
     *  resolved. Correcting this morning's dose after the evening one had been
     *  taken reverted the evening one; correcting a dose logged before a refill
     *  destroyed the refill. See docs/SPEC.md §7 R27.
     *
     *  The rationale that absolute write carried -- that relative math "could
     *  compound error across repeated switches" -- does not hold for a delta
     *  read from the recorded pair. Taken->Skipped gives back exactly what
     *  Skipped->Taken takes away, so any sequence of switches is reversible,
     *  and the same-status guard below stops one direction being applied
     *  twice. Compounding would only be a risk if the delta were recomputed
     *  from the event's *current* doseAmount, which [editDoseAmount] can change
     *  independently of the sentinels -- which is precisely why the delta comes
     *  from stockBefore/stockAfter rather than from doseAmount.
     *
     *  The recorded pair is clamp-aware for free: [hypotheticalStockSentinels]
     *  floors stockAfter at 0, so a dose taken against insufficient stock
     *  records what was really consumed, and handing back exactly that is
     *  right. A REFILLED event can never reach here -- the require below
     *  admits only an already-resolved Taken/Skipped one -- so the difference
     *  is never negative.
     *
     *  A no-op for an event that was never tracked (stockBefore == stockAfter,
     *  the -1.0 sentinel pair or a genuinely zero-dose event -- both correctly
     *  mean "nothing to restore"). */
    suspend fun switchStatus(doseEventId: Long, newStatus: DoseStatus) {
        require(newStatus == DoseStatus.TAKEN || newStatus == DoseStatus.SKIPPED) {
            "switchStatus is for Taken<->Skipped only, was $newStatus"
        }

        // Read, validate, and write inside one transaction -- not a snapshot
        // taken before it opens -- for the same reason as resolve() above: a
        // second switchStatus()/resolve() call that commits in the gap
        // between an outside read and this write would apply a target
        // quantity computed from an already-stale stockBefore/stockAfter pair.
        var switchedMedicineId = -1L
        val switched = database.withTransaction {
            val event = doseEventDao.getById(doseEventId) ?: error("No DoseEvent with id=$doseEventId")
            switchedMedicineId = event.medicineId
            require(event.status == DoseStatus.TAKEN || event.status == DoseStatus.SKIPPED) {
                "switchStatus needs an already-resolved event, was ${event.status}"
            }
            if (event.status == newStatus) return@withTransaction false

            // resolvedAtEpochMillis intentionally left as-is -- a status switch
            // doesn't change *when* it was acted on, and re-stamping it would
            // wrongly shift an ON_RESOLVED interval reminder's anchor.
            doseEventDao.updateStatus(doseEventId, newStatus, event.resolvedAtEpochMillis ?: System.currentTimeMillis())
            if (event.stockBefore != event.stockAfter) {
                val consumed = event.stockBefore - event.stockAfter
                if (newStatus == DoseStatus.TAKEN) {
                    medicineDao.decrementStock(event.medicineId, consumed)
                } else {
                    medicineDao.incrementStock(event.medicineId, consumed)
                }
            }
            true
        }
        if (!switched) return

        // An alert reminder's next firing is computed from this medicine's
        // stock and expiry, so a change to either can move, cancel or create
        // its alarm with nothing about the reminder row itself having
        // changed -- and the projection cache, which is what invalidate()
        // below drives, does not touch AlarmManager. Spec §7 R37.
        schedulingCoordinator.onMedicineChanged(switchedMedicineId)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    /** Event editing (spec §3/§9 #3) -- corrects the actual take/skip time.
     *  Doesn't retroactively touch stock, matching medTimer's scope: only a
     *  status switch does that. */
    suspend fun editRecordedTime(doseEventId: Long, newResolvedAtEpochMillis: Long) {
        doseEventDao.updateResolvedAt(doseEventId, newResolvedAtEpochMillis)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    /** Event editing (spec §3/§9 #3) -- corrects the logged dose amount.
     *  Same "doesn't touch stock" scope as [editRecordedTime]. */
    suspend fun editDoseAmount(doseEventId: Long, newDoseAmount: Double) {
        doseEventDao.updateDoseAmount(doseEventId, newDoseAmount)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    /** Source for the "suggest the medicine's last-used amount" default on
     *  the Log dose entry point (spec §3) -- simpler than medTimer's
     *  inactive-reminder-as-preset indirection, same outcome. Null if the
     *  medicine has never had a dose logged against it. (Refill was named
     *  here too; StockExpirationScreen's refill field has always been its
     *  own input and has never called this.) */
    suspend fun getLastUsedDoseAmount(medicineId: Long): Double? =
        doseEventDao.getMostRecentForMedicine(medicineId)?.doseAmount

    /** PRN / ad-hoc dose logging (spec §3, §9 #2) -- a manually-logged dose not
     *  tied to any scheduled occurrence: reminderId = null, logged retrospectively
     *  so it goes straight to TAKEN rather than through PENDING. */
    suspend fun logAdhocDose(medicineId: Long, doseAmount: Double, atEpochMillis: Long): Long {
        val id = database.withTransaction {
            doseEventDao.insert(
                DoseEvent(
                    reminderId = null,
                    medicineId = medicineId,
                    scheduledEpochMillis = atEpochMillis,
                    doseAmount = doseAmount,
                    status = DoseStatus.TAKEN,
                    resolvedAtEpochMillis = atEpochMillis,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ).withStockSentinels(medicineId, decrease = doseAmount),
            )
        }
        // An alert reminder's next firing is computed from this medicine's
        // stock and expiry, so a change to either can move, cancel or create
        // its alarm with nothing about the reminder row itself having
        // changed -- and the projection cache, which is what invalidate()
        // below drives, does not touch AlarmManager. Spec §7 R37.
        schedulingCoordinator.onMedicineChanged(medicineId)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
        return id
    }

    /** One-tap restock (spec §3, §9 #2 -- medTimer's RefillProcessor, ported).
     *  Same stockBefore/stockAfter sentinel mechanism as [logAdhocDose], just
     *  the other direction (After > Before instead of <) -- one mechanism for
     *  both directions of stock change, not two. Shows up in History as an
     *  ordinary DoseEvent row distinguished by DoseStatus.REFILLED. */
    suspend fun logRefill(medicineId: Long, refillAmount: Double, atEpochMillis: Long): Long {
        val id = database.withTransaction {
            doseEventDao.insert(
                DoseEvent(
                    reminderId = null,
                    medicineId = medicineId,
                    scheduledEpochMillis = atEpochMillis,
                    doseAmount = refillAmount,
                    status = DoseStatus.REFILLED,
                    resolvedAtEpochMillis = atEpochMillis,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ).withStockSentinels(medicineId, increase = refillAmount),
            )
        }
        // An alert reminder's next firing is computed from this medicine's
        // stock and expiry, so a change to either can move, cancel or create
        // its alarm with nothing about the reminder row itself having
        // changed -- and the projection cache, which is what invalidate()
        // below drives, does not touch AlarmManager. Spec §7 R37.
        schedulingCoordinator.onMedicineChanged(medicineId)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
        return id
    }

    /** Records stockBefore/stockAfter (spec §3's ADR-0004-derived sentinel
     *  pair) and applies the corresponding DB update, but only when the
     *  medicine actually has stock tracking active (a unit set) -- an
     *  untracked medicine keeps both fields at their -1.0 "not tracked"
     *  sentinel rather than a misleading 0.0-vs-0.0 no-op. Exactly one of
     *  [decrease]/[increase] should be non-null. */
    private suspend fun DoseEvent.withStockSentinels(medicineId: Long, decrease: Double? = null, increase: Double? = null): DoseEvent {
        val medicine = medicineDao.getById(medicineId) ?: return this
        if (medicine.stockInfo.unit == null) return this
        val before = medicine.stockInfo.quantity
        val after = when {
            decrease != null -> (before - decrease).coerceAtLeast(0.0)
            increase != null -> before + increase
            else -> before
        }
        if (decrease != null) medicineDao.decrementStock(medicineId, decrease)
        if (increase != null) medicineDao.incrementStock(medicineId, increase)
        return copy(stockBefore = before, stockAfter = after)
    }

    /** [resolve]'s variant of the sentinel computation above: computes but does
     *  NOT apply the quantity change, and computes it regardless of whether the
     *  dose ends up Taken or Skipped -- "what quantity would this dose leave
     *  behind if taken" is exactly the value [switchStatus] needs on hand for
     *  either type of resolution, and recording it for Skipped too (rather
     *  than only Taken) is what makes a later Skipped->Taken switch possible
     *  without a second lookup. Null if the medicine has no stock tracking
     *  active (unit == null) -- the -1.0 sentinel defaults are left as-is. */
    private suspend fun hypotheticalStockSentinels(medicineId: Long, doseAmount: Double): Pair<Double, Double>? {
        val medicine = medicineDao.getById(medicineId) ?: return null
        if (medicine.stockInfo.unit == null) return null
        val before = medicine.stockInfo.quantity
        val after = (before - doseAmount).coerceAtLeast(0.0)
        return before to after
    }
}
