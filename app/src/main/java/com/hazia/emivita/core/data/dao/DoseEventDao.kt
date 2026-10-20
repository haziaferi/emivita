package com.hazia.emivita.core.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import kotlinx.coroutines.flow.Flow

/** DoseEvent joined with its Medicine, for screens that display more than the
 *  bare event (Today's timeline, History). Explicit column aliasing below
 *  because both tables have an `id` / `createdAtEpochMillis` column -- Room's
 *  @Embedded(prefix=) needs the result set columns to actually carry that
 *  prefix, hence the AS aliases in the query rather than `medicines.*`. */
data class DoseEventWithMedicine(
    @Embedded val doseEvent: DoseEvent,
    @Embedded(prefix = "medicine_") val medicine: Medicine,
)

private const val JOINED_SELECT = """
    SELECT
      dose_events.*,
      medicines.id AS medicine_id,
      medicines.profileId AS medicine_profileId,
      medicines.name AS medicine_name,
      medicines.iconKey AS medicine_iconKey,
      medicines.color AS medicine_color,
      medicines.notificationPriority AS medicine_notificationPriority,
      medicines.doseUnit AS medicine_doseUnit,
      medicines.remindersLockedUntilTaken AS medicine_remindersLockedUntilTaken,
      medicines.notes AS medicine_notes,
      medicines.quantity AS medicine_quantity,
      medicines.unit AS medicine_unit,
      medicines.refillQuantity AS medicine_refillQuantity,
      medicines.productionDateEpochDay AS medicine_productionDateEpochDay,
      medicines.expirationDateEpochDay AS medicine_expirationDateEpochDay,
      medicines.openedDateEpochDay AS medicine_openedDateEpochDay,
      medicines.useWithinDaysAfterOpening AS medicine_useWithinDaysAfterOpening,
      medicines.sortOrder AS medicine_sortOrder,
      medicines.barcodeValue AS medicine_barcodeValue,
      medicines.indication AS medicine_indication,
      medicines.storageNote AS medicine_storageNote,
      medicines.archived AS medicine_archived,
      medicines.prnMaxDoses AS medicine_prnMaxDoses,
      medicines.prnWindowHours AS medicine_prnWindowHours,
      medicines.strength AS medicine_strength,
      medicines.rxNumber AS medicine_rxNumber,
      medicines.createdAtEpochMillis AS medicine_createdAtEpochMillis
    FROM dose_events
    INNER JOIN medicines ON dose_events.medicineId = medicines.id
"""

data class AnsweredSlot(val reminderId: Long, val scheduledEpochMillis: Long)

@Dao
interface DoseEventDao {

    @Query(
        "$JOINED_SELECT WHERE medicines.profileId = :profileId AND dose_events.status != 'DELETED' " +
            "AND dose_events.scheduledEpochMillis BETWEEN :startEpochMillis AND :endEpochMillis " +
            "ORDER BY dose_events.scheduledEpochMillis ASC"
    )
    fun observeInRange(profileId: Long, startEpochMillis: Long, endEpochMillis: Long): Flow<List<DoseEventWithMedicine>>

    /** Scheduled doses still unanswered inside a window (§9 #25 re-raise). */
    @Query(
        "SELECT * FROM dose_events WHERE status = 'PENDING' AND reminderId IS NOT NULL " +
            "AND scheduledEpochMillis BETWEEN :startEpochMillis AND :endEpochMillis ORDER BY scheduledEpochMillis ASC"
    )
    suspend fun getPendingScheduledBetween(startEpochMillis: Long, endEpochMillis: Long): List<DoseEvent>

    /** Slots already answered, early ones included (§7 R104, census A-38): the
     *  projection drops them, as medTimer's simulation lets a real event replace one. */
    @Query(
        "SELECT reminderId, scheduledEpochMillis FROM dose_events WHERE reminderId IS NOT NULL " +
            "AND status IN ('TAKEN', 'SKIPPED') AND scheduledEpochMillis >= :fromEpochMillis AND scheduledEpochMillis <= :toEpochMillis"
    )
    suspend fun getAnsweredSlots(fromEpochMillis: Long, toEpochMillis: Long): List<AnsweredSlot>

    @Query("SELECT * FROM dose_events WHERE reminderId = :reminderId AND scheduledEpochMillis = :scheduledEpochMillis LIMIT 1")
    suspend fun findByReminderAndTime(reminderId: Long, scheduledEpochMillis: Long): DoseEvent?

    /** Most recently *resolved* (Taken or Skipped) event for a reminder, used
     *  as the ON_RESOLVED interval/window scheduling anchor -- see
     *  ReminderScheduling.kt's doc comment on why this replaces a persisted
     *  "current anchor" field. Null if the reminder has never been resolved. */
    @Query(
        "SELECT * FROM dose_events WHERE reminderId = :reminderId AND status NOT IN ('PENDING', 'DELETED') " +
            "ORDER BY resolvedAtEpochMillis DESC LIMIT 1"
    )
    suspend fun getLastResolved(reminderId: Long): DoseEvent?

    @Query("SELECT * FROM dose_events WHERE id = :id")
    suspend fun getById(id: Long): DoseEvent?

    /** Most recently *materialized* event for a reminder, any status -- the
     *  ON_REMINDED anchor for a LinkedFollowing child (spec §3/§9 #14:
     *  "the parent's most recent event time, fired-at for ON_REMINDED").
     *  Distinct from [getLastResolved], which excludes PENDING. */
    @Query("SELECT * FROM dose_events WHERE reminderId = :reminderId ORDER BY scheduledEpochMillis DESC LIMIT 1")
    suspend fun getMostRecentForReminder(reminderId: Long): DoseEvent?

    /** Most recently *logged dose* for a medicine, scheduled or PRN --
     *  source for the "suggest the medicine's last-used amount" default on
     *  the Log dose entry point (spec §3). createdAtEpochMillis, not
     *  scheduledEpochMillis, so a retroactively-logged PRN dose still ranks
     *  by when it was actually entered.
     *
     *  REFILLED rows are excluded, and that is load-bearing rather than
     *  tidiness: logRefill() writes the restock quantity into the same
     *  doseAmount column, and a restock is by construction the newest row
     *  for that medicine, so without this filter the first Log dose after
     *  any refill suggested the pack size (30) instead of the dose (1). */
    @Query(
        "SELECT * FROM dose_events WHERE medicineId = :medicineId AND status NOT IN ('REFILLED', 'DELETED') " +
            "ORDER BY createdAtEpochMillis DESC LIMIT 1"
    )
    suspend fun getMostRecentForMedicine(medicineId: Long): DoseEvent?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: DoseEvent): Long

    @Query("UPDATE dose_events SET status = :status, resolvedAtEpochMillis = :resolvedAtEpochMillis WHERE id = :id")
    suspend fun updateStatus(id: Long, status: DoseStatus, resolvedAtEpochMillis: Long)

    /** Written by resolve() alongside updateStatus (spec §3's ADR-0004
     *  sentinel pair) -- recorded for both TAKEN and SKIPPED so a later
     *  switchStatus() has a real "what would stock have been" value to
     *  restore/reapply regardless of which way the switch goes. */
    @Query("UPDATE dose_events SET stockBefore = :stockBefore, stockAfter = :stockAfter WHERE id = :id")
    suspend fun updateStockSentinels(id: Long, stockBefore: Double, stockAfter: Double)

    /** Event editing (spec §3/§9 #3) -- corrects the actual take/skip time
     *  without touching stock (only a status switch does that, per spec). */
    @Query("UPDATE dose_events SET resolvedAtEpochMillis = :resolvedAtEpochMillis WHERE id = :id")
    suspend fun updateResolvedAt(id: Long, resolvedAtEpochMillis: Long)

    /** Event editing (spec §3/§9 #3) -- corrects the logged dose amount
     *  without retroactively touching stock, same reasoning as
     *  [updateResolvedAt]. */
    @Query("UPDATE dose_events SET doseAmount = :doseAmount WHERE id = :id")
    suspend fun updateDoseAmount(id: Long, doseAmount: Double)

    @Query("UPDATE dose_events SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String?)

    /** Reopen (§9 #26): back to PENDING with the resolution and the stock
     *  pair cleared; the caller has already given the stock back. */
    @Query("UPDATE dose_events SET status = 'PENDING', resolvedAtEpochMillis = NULL, stockBefore = -1.0, stockAfter = -1.0 WHERE id = :id")
    suspend fun reopen(id: Long)

    @Query("UPDATE dose_events SET scheduledEpochMillis = :scheduledEpochMillis WHERE id = :id")
    suspend fun updateScheduledAt(id: Long, scheduledEpochMillis: Long)

    /** A ranged read for the export (§9 #26; Appendix F MK-03), independent
     *  of History's display window; DELETED excluded like every read. */
    @Query(
        "$JOINED_SELECT WHERE medicines.profileId = :profileId AND dose_events.status != 'DELETED' " +
            "AND dose_events.scheduledEpochMillis BETWEEN :startEpochMillis AND :endEpochMillis " +
            "AND (:medicineId IS NULL OR dose_events.medicineId = :medicineId) " +
            "ORDER BY dose_events.scheduledEpochMillis DESC"
    )
    suspend fun getInRangeOnce(profileId: Long, startEpochMillis: Long, endEpochMillis: Long, medicineId: Long?): List<DoseEventWithMedicine>

    /** As-needed doses of one medicine inside a rolling window (§9 #26 PRN
     *  ceiling and headroom): TAKEN only, any reminder or none. */
    @Query(
        "SELECT * FROM dose_events WHERE medicineId = :medicineId AND status = 'TAKEN' " +
            "AND resolvedAtEpochMillis >= :sinceEpochMillis ORDER BY resolvedAtEpochMillis ASC"
    )
    suspend fun getTakenSince(medicineId: Long, sinceEpochMillis: Long): List<DoseEvent>

    /** Nagging (spec §3/§4 `repeatOnUnanswered`) -- one nag fire, one
     *  increment, matching the field's own doc comment ("Incremented per
     *  nag fire, capped at repeatOnUnanswered.maxRepeats"). */
    @Query("UPDATE dose_events SET repeatCount = repeatCount + 1 WHERE id = :id")
    suspend fun incrementRepeatCount(id: Long)

    /** Cascade-delete support for "Delete for good" on the archived list
     *  (spec §9 #1, §9 #26 / §7 R65; plain Delete only archives). Reminder-linked
     *  DoseEvents go transitively through the reminders' CASCADE FKs, but
     *  PRN/refill events (reminderId = null) are linked only by the
     *  denormalized medicineId column, deliberately without an FK, so this
     *  query is what catches them, called by MedicineRepository.delete()
     *  before the Medicine row goes. */
    @Query("DELETE FROM dose_events WHERE medicineId = :medicineId")
    suspend fun deleteAllForMedicine(medicineId: Long)

    // Backup/restore (spec §9 #9) -- restoreAll is REPLACE (preserves original
    // ids, unlike insert()'s IGNORE above, which exists for a different
    // purpose: idempotent materialization, not import).
    @Query("SELECT * FROM dose_events")
    suspend fun getAllOnce(): List<DoseEvent>

    @Query("DELETE FROM dose_events")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(events: List<DoseEvent>)
}
