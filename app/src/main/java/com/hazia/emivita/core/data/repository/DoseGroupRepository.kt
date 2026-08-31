package com.hazia.emivita.core.data.repository

import androidx.room.withTransaction
import com.hazia.emivita.core.data.dao.DoseGroupDao
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.db.EmivitaDatabase
import com.hazia.emivita.core.data.entity.DoseGroup
import com.hazia.emivita.core.data.entity.NotificationPriority
import kotlinx.coroutines.flow.Flow

class DoseGroupRepository(
    private val database: EmivitaDatabase,
    private val doseGroupDao: DoseGroupDao,
    private val reminderDao: ReminderDao,
) {
    fun observeForProfile(profileId: Long): Flow<List<DoseGroup>> =
        doseGroupDao.observeForProfile(profileId)

    /** Confirms a suggested group (see the hybrid auto-suggest design) into a
     *  real DoseGroup, defaulting to Alarm-tier priority, and reassigns the
     *  given reminders to it in one step -- wrapped in one transaction so a
     *  process death mid-loop can't leave the group inserted with only some
     *  of its intended members actually reassigned to it. */
    suspend fun confirmGroup(profileId: Long, name: String, reminderIds: List<Long>): Long =
        database.withTransaction {
            val groupId = doseGroupDao.insert(
                DoseGroup(
                    profileId = profileId,
                    name = name,
                    priority = NotificationPriority.ALARM,
                    createdAtEpochMillis = System.currentTimeMillis(),
                )
            )
            reminderIds.forEach { reminderId ->
                reminderDao.getById(reminderId)?.let { reminder ->
                    reminderDao.update(reminder.copy(groupId = groupId))
                }
            }
            groupId
        }

    suspend fun setPriority(group: DoseGroup, priority: NotificationPriority) =
        doseGroupDao.update(group.copy(priority = priority))

    suspend fun rename(group: DoseGroup, newName: String) =
        doseGroupDao.update(group.copy(name = newName))

    /** Ungroups without deleting the reminders themselves -- same
     *  atomicity reasoning as [confirmGroup]: without a transaction, a
     *  process death mid-loop could delete the group while some reminders
     *  still carry its now-dangling groupId. */
    suspend fun disbandGroup(group: DoseGroup, memberReminderIds: List<Long>) {
        database.withTransaction {
            memberReminderIds.forEach { reminderId ->
                reminderDao.getById(reminderId)?.let { reminder ->
                    reminderDao.update(reminder.copy(groupId = null))
                }
            }
            doseGroupDao.delete(group)
        }
    }
}
