package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.entity.Reminder

/**
 * Production [SchedulingCoordinator]: recomputes a reminder's next
 * occurrence via [SchedulingEngine] and hands it to [AlarmScheduler].
 * Every method funnels through [scheduleOrCancel] so "no next occurrence"
 * and "cancel the alarm" are never two separately-maintained code paths.
 *
 * Depends on [ReminderDao] directly, not ReminderRepository -- see
 * SchedulingEngine's doc comment for why (ReminderRepository depends on
 * this interface, so depending back on it here would be circular).
 */
class RealSchedulingCoordinator(
    private val reminderDao: ReminderDao,
    private val schedulingEngine: SchedulingEngine,
    private val alarmScheduler: AlarmScheduler,
    private val occurrenceProjectionCache: OccurrenceProjectionCache,
) : SchedulingCoordinator {

    override suspend fun onDoseResolved(reminderId: Long) {
        rescheduleOne(reminderId, System.currentTimeMillis())

        // LinkedFollowing cascade, resolve-triggered side (spec §3/§9 #14) --
        // one level down only: direct children whose config is ON_RESOLVED get
        // rescheduled now that this reminder's anchor (its most recently
        // resolved event) just changed. ON_REMINDED children are the other
        // trigger point, in ReminderAlarmReceiver.
        reminderDao.getChildrenOf(reminderId).forEach { child ->
            if (!child.enabled) return@forEach
            val childConfig = decodeReminderConfig(child.configJson)
            if (childConfig is ReminderConfig.LinkedFollowing && childConfig.startBehavior == IntervalStartBehavior.ON_RESOLVED) {
                rescheduleOne(child.id, System.currentTimeMillis())
            }
        }
    }

    override suspend fun onReminderChanged(reminderId: Long, notBeforeEpochMillis: Long) =
        rescheduleOne(reminderId, notBeforeEpochMillis)

    override suspend fun onReminderRemoved(reminderId: Long) {
        alarmScheduler.cancel(reminderId)
        alarmScheduler.cancelNag(reminderId)
        occurrenceProjectionCache.invalidate()
    }

    override suspend fun cancelNag(reminderId: Long) = alarmScheduler.cancelNag(reminderId)

    override suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        reminderDao.getAllEnabled().forEach { reminder -> scheduleOrCancel(reminder, now) }
    }

    private suspend fun rescheduleOne(reminderId: Long, notBeforeEpochMillis: Long) {
        val reminder = reminderDao.getById(reminderId)
        if (reminder == null) {
            // Deleted between whatever triggered this call and now -- make
            // sure no stale alarm survives rather than leaving it dangling.
            alarmScheduler.cancel(reminderId)
            return
        }
        scheduleOrCancel(reminder, notBeforeEpochMillis)
    }

    private suspend fun scheduleOrCancel(reminder: Reminder, notBeforeEpochMillis: Long) {
        val next = schedulingEngine.nextOccurrenceFor(reminder, notBeforeEpochMillis)
        if (next != null) {
            alarmScheduler.schedule(reminder.id, next.epochMillis)
        } else {
            alarmScheduler.cancel(reminder.id)
        }
        // Explicit invalidation (spec §9 #4) -- covers both "alarm just fired,
        // rearming the next slot" and "dose resolved" (onDoseResolved routes
        // through here too), independent of the cache's reactive Flow watcher.
        occurrenceProjectionCache.invalidate()
    }
}
