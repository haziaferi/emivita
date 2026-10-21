package com.hazia.emivita.core.data.repository

import com.hazia.emivita.core.data.entity.isRecordOnly
import com.hazia.emivita.core.data.entity.isAlertSeen
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
import com.hazia.emivita.core.data.dao.ReminderDao

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
    private val reminderDao: ReminderDao,
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
     *  [resolvedAtEpochMillis] defaults to now for live paths and is passed
     *  explicitly only when correcting the record after the fact, so History
     *  says when the dose really happened and `DoseEventDao.getLastResolved`
     *  (ORDER BY resolvedAtEpochMillis DESC) never re-anchors a live interval
     *  reminder to the moment someone tidied their history (spec §7 R46).
     *  A second call for an already-resolved event is a no-op, because the
     *  PENDING check is read inside the same transaction as the write, so two
     *  near-simultaneous calls from different surfaces cannot both apply the
     *  stock change. */
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
            // A dose-count course counts down on Taken only (§9 #26, MT-07);
            // the engine stops at 0. Before the coordinator recomputes.
            if (status == DoseStatus.TAKEN) reminderDao.decrementDosesRemaining(it)
            schedulingCoordinator.onDoseResolved(it)
            // Nagging stops the moment a dose is genuinely answered (spec
            // §3/§4 repeatOnUnanswered) -- the other way it stops is hitting
            // maxRepeats, handled entirely on the nag-fire side
            // (ReminderAlarmReceiver.handleNagFire). Not called from
            // switchStatus() -- by the time that runs the event is already
            // resolved, so nagging already stopped here.
            schedulingCoordinator.cancelNag(it, doseEventId)
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
     *  from the sentinel pair [resolve] recorded at resolution time, never as
     *  an absolute `setStock`, which would discard every stock change made
     *  since (spec §7 R27).
     *
     *  To Skipped, the recorded pair is handed back, so a dose taken against
     *  insufficient stock returns only what it consumed; to Taken, the pair is
     *  recomputed against today's stock and rewritten (§7 R103), so switches
     *  stay exactly reversible. A no-op for an untracked event
     *  (stockBefore == stockAfter: the -1.0 sentinel pair or a zero dose). */
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
                if (newStatus == DoseStatus.TAKEN) {
                    // A skipped dose consumed nothing, so taking it consumes from
                    // the stock there is now, and the pair records that (§7 R103):
                    // the pair kept from the skip was floored against the stock of
                    // that moment, and a refill since made it short by the floor.
                    hypotheticalStockSentinels(event.medicineId, event.doseAmount)?.let { (before, after) ->
                        doseEventDao.updateStockSentinels(doseEventId, before, after)
                        medicineDao.decrementStock(event.medicineId, before - after)
                    }
                } else {
                    medicineDao.incrementStock(event.medicineId, event.stockBefore - event.stockAfter)
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

    /** Event editing (spec §3/§9 #3) -- corrects the logged dose amount, and
     *  since §9 #26 the stock with it (Appendix F PT-F1). A Taken dose whose
     *  amount is edited from 1 to 2 used to leave the bottle short by one
     *  forever; now the difference is applied and the stock pair re-written
     *  so a later status switch reverses the right amount. A Skipped dose
     *  only re-writes its hypothetical pair; a Pending or untracked one
     *  changes the number alone. */
    suspend fun editDoseAmount(doseEventId: Long, newDoseAmount: Double) {
        var medicineId = -1L
        database.withTransaction {
            val event = doseEventDao.getById(doseEventId) ?: error("No DoseEvent with id=$doseEventId")
            medicineId = event.medicineId
            doseEventDao.updateDoseAmount(doseEventId, newDoseAmount)
            val tracked = event.stockBefore != event.stockAfter || (event.stockBefore >= 0.0 && event.stockAfter >= 0.0)
            if (!tracked || event.stockBefore < 0.0) return@withTransaction
            val oldConsumed = event.stockBefore - event.stockAfter
            val newAfter = (event.stockBefore - newDoseAmount).coerceAtLeast(0.0)
            val newConsumed = event.stockBefore - newAfter
            doseEventDao.updateStockSentinels(doseEventId, event.stockBefore, newAfter)
            if (event.status == DoseStatus.TAKEN) {
                val delta = newConsumed - oldConsumed
                if (delta > 0) medicineDao.decrementStock(event.medicineId, delta)
                if (delta < 0) medicineDao.incrementStock(event.medicineId, -delta)
            }
        }
        if (medicineId >= 0) schedulingCoordinator.onMedicineChanged(medicineId)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    suspend fun setNotes(doseEventId: Long, notes: String?) {
        doseEventDao.updateNotes(doseEventId, notes?.trim()?.ifBlank { null })
        autoSyncManager.notifyChanged()
    }

    /** Reopen a Taken or Skipped dose (§9 #26; medTimer's RERAISE): back to
     *  Pending, the stock a Taken one consumed given back, the sentinel pair
     *  cleared. Returns the reopened event so the caller can re-post its
     *  notification -- this layer has no access to the builder. */
    suspend fun reopen(doseEventId: Long): DoseEvent? {
        val reopened = database.withTransaction {
            val event = doseEventDao.getById(doseEventId) ?: return@withTransaction null
            if (event.status != DoseStatus.TAKEN && event.status != DoseStatus.SKIPPED) return@withTransaction null
            if (event.status == DoseStatus.TAKEN && event.stockBefore != event.stockAfter) {
                medicineDao.incrementStock(event.medicineId, event.stockBefore - event.stockAfter)
            }
            doseEventDao.reopen(doseEventId)
            doseEventDao.getById(doseEventId)
        } ?: return null
        reopened.reminderId?.let { schedulingCoordinator.onReminderChanged(it, System.currentTimeMillis()) }
        schedulingCoordinator.onMedicineChanged(reopened.medicineId)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
        return reopened
    }

    /** A stock or expiry alert swiped away, recorded as seen in History (§7 R105,
     *  census B-44, as medTimer records it). Tied to the medicine, not the alert
     *  reminder, whose deletion would cascade the record away; no stock effect. */
    suspend fun recordAlertSeen(medicineId: Long, status: DoseStatus, atEpochMillis: Long): Long {
        require(status.isAlertSeen) { "recordAlertSeen is for the seen-alert statuses, was $status" }
        val id = doseEventDao.insert(
            DoseEvent(
                reminderId = null,
                medicineId = medicineId,
                scheduledEpochMillis = atEpochMillis,
                doseAmount = 0.0,
                status = status,
                resolvedAtEpochMillis = atEpochMillis,
                createdAtEpochMillis = atEpochMillis,
            ),
        )
        autoSyncManager.notifyChanged()
        return id
    }

    /** Soft delete (§9 #26): the row stays so its slot cannot come back and
     *  History's exclusion is a status, not an absence; a Taken dose gives its
     *  stock back. Not for refills -- a refill's stock is undone by its own
     *  removal path when one exists; until then it is not deletable. */
    suspend fun softDelete(doseEventId: Long) {
        var medicineId = -1L
        val done = database.withTransaction {
            val event = doseEventDao.getById(doseEventId) ?: return@withTransaction false
            if (event.status.isRecordOnly || event.status == DoseStatus.DELETED) return@withTransaction false
            medicineId = event.medicineId
            if (event.status == DoseStatus.TAKEN && event.stockBefore != event.stockAfter) {
                medicineDao.incrementStock(event.medicineId, event.stockBefore - event.stockAfter)
            }
            doseEventDao.updateStatus(doseEventId, DoseStatus.DELETED, event.resolvedAtEpochMillis ?: System.currentTimeMillis())
            true
        }
        if (!done) return
        schedulingCoordinator.onMedicineChanged(medicineId)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
    }

    /** Move a pending dose to a chosen instant (§9 #26; medTimer's RESCHEDULE,
     *  Medisafe's "reschedule"): the row's slot moves and a one-off alarm is
     *  armed there. Its old notification is the caller's to cancel. */
    suspend fun reschedule(doseEventId: Long, newScheduledEpochMillis: Long): Boolean {
        val event = doseEventDao.getById(doseEventId) ?: return false
        if (event.status != DoseStatus.PENDING) return false
        val reminderId = event.reminderId ?: return false
        doseEventDao.updateScheduledAt(doseEventId, newScheduledEpochMillis)
        schedulingCoordinator.onDoseRescheduled(reminderId, doseEventId, newScheduledEpochMillis)
        occurrenceProjectionCache.invalidate()
        autoSyncManager.notifyChanged()
        return true
    }

    /** Doses taken inside the rolling window ending now (§9 #26 PRN ceiling). */
    suspend fun takenSince(medicineId: Long, sinceEpochMillis: Long): List<DoseEvent> =
        doseEventDao.getTakenSince(medicineId, sinceEpochMillis)

    suspend fun getInRangeOnce(profileId: Long, startEpochMillis: Long, endEpochMillis: Long, medicineId: Long?): List<DoseEventWithMedicine> =
        doseEventDao.getInRangeOnce(profileId, startEpochMillis, endEpochMillis, medicineId)

    /** Source for the "suggest the medicine's last-used amount" default on
     *  the Log dose entry point (spec §3), simpler than medTimer's
     *  inactive-reminder-as-preset indirection with the same outcome. Null if
     *  the medicine has never had a dose logged against it. */
    suspend fun getLastUsedDoseAmount(medicineId: Long): Double? =
        doseEventDao.getMostRecentForMedicine(medicineId)?.doseAmount

    /** The most recent dose row (any status but a refill or a deletion) --
     *  the "last taken 3 h ago" an as-needed medicine shows (§9 #26; PT-02). */
    suspend fun lastDose(medicineId: Long): DoseEvent? = doseEventDao.getMostRecentForMedicine(medicineId)

    /**
     * "Take now" on a slot the alarm has not reached yet (§9 #26): the slot
     * is materialised through the same idempotent path the alarm uses, then
     * answered Taken at [nowEpochMillis]. When the alarm fires later it finds
     * a resolved row and stays quiet (ReminderAlarmReceiver checks PENDING).
     */
    suspend fun takeProjectedNow(reminderId: Long, medicineId: Long, scheduledEpochMillis: Long, doseAmount: Double, nowEpochMillis: Long = System.currentTimeMillis()) {
        answerProjected(reminderId, medicineId, scheduledEpochMillis, doseAmount, DoseStatus.TAKEN, nowEpochMillis)
    }

    /** [takeProjectedNow] with the answer chosen: the upcoming dose's sheet
     *  on Today (§7 R107, census D-55) can also skip a slot ahead of time. */
    suspend fun answerProjected(reminderId: Long, medicineId: Long, scheduledEpochMillis: Long, doseAmount: Double, status: DoseStatus, atEpochMillis: Long = System.currentTimeMillis()) {
        val id = materializeIfAbsent(reminderId, medicineId, scheduledEpochMillis, doseAmount)
            ?: doseEventDao.findByReminderAndTime(reminderId, scheduledEpochMillis)?.id
            ?: return
        resolve(id, status, atEpochMillis)
    }

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
    suspend fun logRefill(medicineId: Long, refillAmount: Double, atEpochMillis: Long): Long? {
        // Refuse rather than record a no-op (§7 R60 C F-2). withStockSentinels
        // returns early for an untracked medicine (no unit), so this used to
        // insert a REFILLED row with -1/-1 sentinels and change nothing --
        // History said "refilled", the bottle count did not move. medTimer's
        // RefillProcessor adds the size unconditionally; here tracking is
        // explicit (§3), so the honest answer is null and the screen gates
        // the button on a saved unit.
        val medicine = medicineDao.getById(medicineId) ?: return null
        if (medicine.stockInfo.unit == null) return null
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
