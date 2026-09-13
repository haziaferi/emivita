package com.hazia.emivita.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.hazia.emivita.core.data.entity.Reminder
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE medicineId = :medicineId ORDER BY createdAtEpochMillis ASC")
    fun observeForMedicine(medicineId: Long): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE groupId = :groupId")
    fun observeForGroup(groupId: Long): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE enabled = 1")
    suspend fun getAllEnabled(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): Reminder?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)

    /** LowStockAlert/ExpirationAlert trigger-mode bookkeeping (spec §9 #8) --
     *  see LowStockExpirationAlertEvaluator. */
    /** Dose-count course (§9 #26): one fewer after a TAKEN, floored at 0. */
    @Query("UPDATE reminders SET dosesRemaining = MAX(0, dosesRemaining - 1) WHERE id = :reminderId AND dosesRemaining IS NOT NULL")
    suspend fun decrementDosesRemaining(reminderId: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE reminders SET lastAlertNotifiedEpochDay = :epochDay WHERE id = :reminderId")
    suspend fun updateLastAlertNotifiedEpochDay(reminderId: Long, epochDay: Long?)

    /** LinkedFollowing's cascade-reschedule trigger points (spec §3/§9 #14) --
     *  "children of reminder X," one level down only. Used by
     *  ReminderAlarmReceiver (fire-triggered, ON_REMINDED children) and
     *  RealSchedulingCoordinator.onDoseResolved (resolve-triggered,
     *  ON_RESOLVED children). Not filtered by type here -- only a
     *  LINKED_FOLLOWING reminder ever sets parentReminderId, so every row
     *  this returns already is one; callers still decode configJson to read
     *  startBehavior. */
    @Query("SELECT * FROM reminders WHERE parentReminderId = :parentReminderId")
    suspend fun getChildrenOf(parentReminderId: Long): List<Reminder>

    /** One-shot counterpart to [observeForMedicine], for
     *  SchedulingCoordinator.onMedicineChanged: an alert reminder's next firing
     *  depends on its medicine's stock and expiry, so a change to those has to
     *  re-arm its alarm, and that path is a suspend call rather than a Flow
     *  collector (spec §7 R37). */
    @Query("SELECT * FROM reminders WHERE medicineId = :medicineId AND enabled = 1")
    suspend fun getEnabledForMedicine(medicineId: Long): List<Reminder>

    // Backup/restore (spec §9 #9).
    @Query("SELECT * FROM reminders")
    suspend fun getAllOnce(): List<Reminder>

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreAll(reminders: List<Reminder>)
}
