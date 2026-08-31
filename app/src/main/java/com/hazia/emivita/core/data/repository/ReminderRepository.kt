package com.hazia.emivita.core.data.repository

import com.hazia.emivita.core.data.backup.AutoSyncManager
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.core.scheduling.SchedulingCoordinator
import kotlinx.coroutines.flow.Flow

/**
 * Every mutation here calls [schedulingCoordinator] afterward, the same
 * "single entry point" discipline DoseEventRepository already follows for
 * dose resolution -- creating, editing, enabling/disabling, or deleting a
 * reminder can all move its next alarm, and nothing should be able to do
 * one of those without the alarm catching up. Also notifies [autoSyncManager]
 * (spec §9 #9(b): "medicine/reminder edits" is one of its trigger events).
 */
class ReminderRepository(
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

    /** [autoResolve]/[activeFromEpochDay]/[activeUntilEpochDay]/[repeatOnUnanswered]
     *  (spec §3/§4's "five schema-driven gap resolutions") are cross-cutting --
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
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        )
        schedulingCoordinator.onReminderChanged(id, System.currentTimeMillis())
        autoSyncManager.notifyChanged()
        return id
    }

    /** Edit-side counterpart to the four cross-cutting fields [create] takes --
     *  a separate method from [updateConfig] since these live directly on the
     *  `Reminder` row, not inside `configJson`, so editing them shouldn't
     *  force a caller to also have a decoded `ReminderConfig` on hand. */
    suspend fun updateAdvancedFields(
        reminder: Reminder,
        autoResolve: Boolean,
        activeFromEpochDay: Long?,
        activeUntilEpochDay: Long?,
        repeatOnUnanswered: RepeatConfig?,
    ) {
        reminderDao.update(
            reminder.copy(
                autoResolve = autoResolve,
                activeFromEpochDay = activeFromEpochDay,
                activeUntilEpochDay = activeUntilEpochDay,
                repeatOnUnanswered = repeatOnUnanswered,
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

    suspend fun setEnabled(reminder: Reminder, enabled: Boolean) {
        reminderDao.update(reminder.copy(enabled = enabled))
        schedulingCoordinator.onReminderChanged(reminder.id, System.currentTimeMillis())
        autoSyncManager.notifyChanged()
    }

    suspend fun delete(reminder: Reminder) {
        reminderDao.delete(reminder)
        schedulingCoordinator.onReminderRemoved(reminder.id)
        autoSyncManager.notifyChanged()
    }
}
