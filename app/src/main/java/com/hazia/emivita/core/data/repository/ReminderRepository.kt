package com.hazia.emivita.core.data.repository

import androidx.room.withTransaction
import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Every mutation here calls [schedulingCoordinator] afterward, the same
 * "single entry point" discipline DoseEventRepository already follows for
 * dose resolution -- creating, editing, enabling/disabling, or deleting a
 * reminder can all move its next alarm, and nothing should be able to do
 * one of those without the alarm catching up. Also notifies [autoSyncManager]
 * (spec §9 #9(b): "medicine/reminder edits" is one of its trigger events).
 */
class ReminderRepository(
    private val database: EmivitaDatabase,
    private val reminderDao: ReminderDao,
    private val schedulingCoordinator: SchedulingCoordinator,
    private val autoSyncManager: AutoSyncManager,
) {
    fun observeForMedicine(medicineId: Long): Flow<List<Reminder>> =
        reminderDao.observeForMedicine(medicineId)

    fun observeForGroup(groupId: Long): Flow<List<Reminder>> =
        reminderDao.observeForGroup(groupId)

    suspend fun getById(id: Long): Reminder? = reminderDao.getById(id)
    suspend fun getAllEnabled(): List<Reminder> = reminderDao.getAllEnabled()

    /** LinkedFollowing's cascade-reschedule trigger points (spec §3/§9 #14) --
     *  see ReminderAlarmReceiver and RealSchedulingCoordinator.onDoseResolved. */
    suspend fun getChildrenOf(parentReminderId: Long): List<Reminder> = reminderDao.getChildrenOf(parentReminderId)

    /** Decodes configJson into the strongly-typed config matching `type` --
     *  callers (edit screens, etc.) should always go through this rather than
     *  parsing configJson themselves. Delegates to the standalone codec so
     *  the scheduling engine can decode the same way without depending on
     *  this class (see ReminderConfigCodec.kt for why that matters). */
    fun decodeConfig(reminder: Reminder): ReminderConfig = decodeReminderConfig(reminder.configJson)

    /** [autoResolve]/[activeFromEpochDay]/[activeUntilEpochDay]/[repeatOnUnanswered]/
     *  [weekendModeOptIn] (spec §3/§4's "five schema-driven gap resolutions",
     *  plus §7 R36's weekend opt-in) are cross-cutting --
     *  independent of `type`/`config`, every dose-producing reminder type can
     *  opt into them. Defaulted so every pre-existing call site (LowStockAlert/
     *  ExpirationAlert, which don't produce DoseEvents and so can't
     *  meaningfully use any of the four) keeps compiling unchanged. */
    suspend fun create(
        medicineId: Long,
        groupId: Long?,
        type: ReminderType,
        config: ReminderConfig,
        parentReminderId: Long? = null,
        autoResolve: Boolean = false,
        activeFromEpochDay: Long? = null,
        activeUntilEpochDay: Long? = null,
        repeatOnUnanswered: RepeatConfig? = null,
        weekendModeOptIn: Boolean = false,
        preAlarmMinutes: Int? = null,
        instructions: String? = null,
        dosesRemaining: Int? = null,
        stickyUntilAnswered: Boolean = false,
        variableAmount: Boolean = false,
    ): Long {
        val id = reminderDao.insert(
            Reminder(
                medicineId = medicineId,
                groupId = groupId,
                parentReminderId = parentReminderId,
                type = type,
                configJson = encodeReminderConfig(config),
                autoResolve = autoResolve,
                activeFromEpochDay = activeFromEpochDay,
                activeUntilEpochDay = activeUntilEpochDay,
                repeatOnUnanswered = repeatOnUnanswered,
                weekendModeOptIn = weekendModeOptIn,
                preAlarmMinutes = preAlarmMinutes,
                instructions = instructions,
                dosesRemaining = dosesRemaining,
                stickyUntilAnswered = stickyUntilAnswered,
                variableAmount = variableAmount,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        )
        schedulingCoordinator.onReminderChanged(id, System.currentTimeMillis())
        autoSyncManager.notifyChanged()
        return id
    }

    /** The whole of an edit-form save, in ONE write, because
     *  `ReminderDao.update` is a full-row `@Update` and two writes built from
     *  the same pre-edit `Reminder` revert each other (docs/SPEC.md §7 R23).
     *
     *  [parentReminderId] defaults to leaving the existing link alone and is
     *  passed explicitly only by the `LinkedFollowing` form, the one config
     *  whose edit is also a relationship change (see
     *  [updateLinkedFollowingParent]). */
    suspend fun updateConfigAndAdvancedFields(
        reminder: Reminder,
        config: ReminderConfig,
        autoResolve: Boolean,
        activeFromEpochDay: Long?,
        activeUntilEpochDay: Long?,
        repeatOnUnanswered: RepeatConfig?,
        weekendModeOptIn: Boolean,
        parentReminderId: Long? = reminder.parentReminderId,
        preAlarmMinutes: Int? = reminder.preAlarmMinutes,
        instructions: String? = reminder.instructions,
        dosesRemaining: Int? = reminder.dosesRemaining,
        stickyUntilAnswered: Boolean = reminder.stickyUntilAnswered,
        variableAmount: Boolean = reminder.variableAmount,
    ) {
        reminderDao.update(
            reminder.copy(
                configJson = encodeReminderConfig(config),
                parentReminderId = parentReminderId,
                autoResolve = autoResolve,
                activeFromEpochDay = activeFromEpochDay,
                activeUntilEpochDay = activeUntilEpochDay,
                repeatOnUnanswered = repeatOnUnanswered,
                weekendModeOptIn = weekendModeOptIn,
                preAlarmMinutes = preAlarmMinutes,
                instructions = instructions,
                dosesRemaining = dosesRemaining,
                stickyUntilAnswered = stickyUntilAnswered,
                variableAmount = variableAmount,
            )
        )
        schedulingCoordinator.onReminderChanged(reminder.id, System.currentTimeMillis())
        autoSyncManager.notifyChanged()
    }

    /** Edit-side counterpart to the four cross-cutting fields [create] takes --
     *  a separate method from [updateConfig] since these live directly on the
     *  `Reminder` row, not inside `configJson`, so editing them shouldn't
     *  force a caller to also have a decoded `ReminderConfig` on hand.
     *
     *  Safe on its own; do not pair it with a second full-row write derived
     *  from the same pre-edit object -- use [updateConfigAndAdvancedFields]
     *  when an edit changes both halves. */
    suspend fun updateAdvancedFields(
        reminder: Reminder,
        autoResolve: Boolean,
        activeFromEpochDay: Long?,
        activeUntilEpochDay: Long?,
        repeatOnUnanswered: RepeatConfig?,
        weekendModeOptIn: Boolean,
        preAlarmMinutes: Int? = reminder.preAlarmMinutes,
        instructions: String? = reminder.instructions,
        dosesRemaining: Int? = reminder.dosesRemaining,
        stickyUntilAnswered: Boolean = reminder.stickyUntilAnswered,
        variableAmount: Boolean = reminder.variableAmount,
    ) {
        reminderDao.update(
            reminder.copy(
                autoResolve = autoResolve,
                activeFromEpochDay = activeFromEpochDay,
                activeUntilEpochDay = activeUntilEpochDay,
                repeatOnUnanswered = repeatOnUnanswered,
                weekendModeOptIn = weekendModeOptIn,
                preAlarmMinutes = preAlarmMinutes,
                instructions = instructions,
                dosesRemaining = dosesRemaining,
                stickyUntilAnswered = stickyUntilAnswered,
                variableAmount = variableAmount,
            )
        )
        schedulingCoordinator.onReminderChanged(reminder.id, System.currentTimeMillis())
        autoSyncManager.notifyChanged()
    }

    /** LinkedFollowing's own parentReminderId can't be changed via
     *  [updateConfig] (a plain configJson swap) -- editing which reminder a
     *  following dose follows is a real relationship change, not just a
     *  field in the JSON blob, so it gets its own explicit method. */
    suspend fun updateLinkedFollowingParent(reminder: Reminder, config: ReminderConfig.LinkedFollowing, parentReminderId: Long) {
        reminderDao.update(reminder.copy(configJson = encodeReminderConfig(config), parentReminderId = parentReminderId))
        schedulingCoordinator.onReminderChanged(reminder.id, System.currentTimeMillis())
        autoSyncManager.notifyChanged()
    }

    suspend fun updateConfig(reminder: Reminder, config: ReminderConfig) {
        reminderDao.update(reminder.copy(configJson = encodeReminderConfig(config)))
        schedulingCoordinator.onReminderChanged(reminder.id, System.currentTimeMillis())
        autoSyncManager.notifyChanged()
    }

    /** Records that an alert reminder announced itself today (spec §7 R37).
     *
     *  Deliberately NOT routed through [schedulingCoordinator] or
     *  [autoSyncManager] the way every other mutation here is. This column is
     *  the alert calculators' own bookkeeping, not user data: the caller
     *  (ReminderAlarmReceiver.handleAlertFire) re-arms the alarm itself
     *  immediately afterwards, so notifying the coordinator here would
     *  schedule twice, and a notification having been shown is not a change
     *  worth waking the backup writer for. */
    suspend fun markAlertNotified(reminderId: Long, epochDay: Long) =
        reminderDao.updateLastAlertNotifiedEpochDay(reminderId, epochDay)

    suspend fun setEnabled(reminder: Reminder, enabled: Boolean) {
        reminderDao.update(reminder.copy(enabled = enabled))
        schedulingCoordinator.onReminderChanged(reminder.id, System.currentTimeMillis())
        autoSyncManager.notifyChanged()
    }

    /** Pause or resume every dosing reminder of a medicine (§9 #24; medTimer's
     *  "activate/deactivate all"). Alert reminders are left alone: pausing the
     *  doses does not mean the pack stops expiring. */
    suspend fun setAllDosingEnabled(medicineId: Long, enabled: Boolean) {
        for (r in reminderDao.observeForMedicine(medicineId).first()) {
            if (r.type == ReminderType.LOW_STOCK_ALERT || r.type == ReminderType.EXPIRATION_ALERT) continue
            if (r.enabled != enabled) setEnabled(r, enabled)
        }
    }

    /** Deleting a reminder switches its direct LinkedFollowing children off
     *  first (spec §3, settled in §7 R25), because the `ON DELETE SET_NULL`
     *  FK would otherwise leave each one enabled, listed and never firing,
     *  and CASCADE would delete rows the user never selected.
     *
     *  Children are read BEFORE the delete, since the FK nulls
     *  `parentReminderId` as part of it. One level down only, matching
     *  [getChildrenOf] and [setEnabled]: a grandchild is not orphaned, just
     *  following a parent that no longer fires. */
    suspend fun delete(reminder: Reminder) {
        val children = reminderDao.getChildrenOf(reminder.id)

        database.withTransaction {
            for (child in children) {
                reminderDao.update(child.copy(enabled = false))
            }
            reminderDao.delete(reminder)
        }

        // Outside the transaction -- OS-level alarm cancellation, not a DB
        // write, the same reasoning MedicineRepository.delete() documents.
        // Each disabled child routes through onReminderChanged rather than
        // onReminderRemoved: its row still exists, and scheduleOrCancel's
        // "no next occurrence" branch is what cancels the alarm.
        schedulingCoordinator.onReminderRemoved(reminder.id)
        val now = System.currentTimeMillis()
        for (child in children) {
            schedulingCoordinator.onReminderChanged(child.id, now)
        }
        autoSyncManager.notifyChanged()
    }
}
