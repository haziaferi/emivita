package com.hazia.emivita.core.scheduling

import android.util.Log
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.entity.Reminder

private const val TAG = "RealSchedulingCoordinator"

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
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
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
        // Snooze and nag slots are per dose event (§7 R60) and this path has
        // no dose-event access; a pending one fires into ReminderAlarmReceiver,
        // which finds no enabled reminder and returns. One spurious wake-up
        // at most, no notification.
        occurrenceProjectionCache.invalidate()
    }

    override suspend fun cancelNag(reminderId: Long, doseEventId: Long) = alarmScheduler.cancelNag(reminderId, doseEventId)

    override suspend fun onDoseRescheduled(reminderId: Long, doseEventId: Long, atEpochMillis: Long) {
        // The snooze slot: a one-off re-notification for an existing dose. The
        // receiver looks the dose up by (reminder, slot), so the slot passed is
        // the new scheduled time, which the repository has already written.
        alarmScheduler.scheduleSnooze(reminderId, doseEventId, originalScheduledEpochMillis = atEpochMillis, fireAtEpochMillis = atEpochMillis)
        occurrenceProjectionCache.invalidate()
    }

    override suspend fun onMedicineChanged(medicineId: Long) {
        val now = System.currentTimeMillis()
        reminderDao.getEnabledForMedicine(medicineId)
            .filter { it.type == ReminderType.LOW_STOCK_ALERT || it.type == ReminderType.EXPIRATION_ALERT }
            .forEach { reminder ->
                // Per-reminder, same reasoning as rescheduleAll below.
                runCatching { scheduleOrCancel(reminder, now) }
                    .onFailure { Log.e(TAG, "Could not re-arm alert reminder ${reminder.id} -- continuing.", it) }
            }
    }

    override suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        // Per-reminder, not per-batch. This is the boot and post-restore path,
        // and AlarmManager has already forgotten everything by the time it
        // runs, so one reminder that fails to reschedule must not take the
        // rest of the list down with it -- the reminders after it in iteration
        // order would simply have no alarm until the app was next opened, with
        // nothing on screen to say so. Failing one is visible as a reminder
        // that does not fire; failing the tail is indistinguishable from the
        // app working.
        reminderDao.getAllEnabled().forEach { reminder ->
            runCatching { scheduleOrCancel(reminder, now) }
                .onFailure { Log.e(TAG, "Could not reschedule reminder ${reminder.id} -- continuing with the rest.", it) }
        }
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
        // Floor at "now" (§7 R60). ReminderAlarmReceiver reschedules from the
        // fired slot + 1 min, which is right when the alarm was on time and
        // wrong when it was not: AlarmManager delivers a past instant at once,
        // so a clock set forward three days -- or an OEM deferring an alarm
        // for hours -- had the receiver arm the next *missed* slot, fire it
        // immediately, arm the one after, and replay every missed dose as a
        // notification chain. medTimer bounds catch-up to today
        // (`raisedToday`, `adjustToToday`); this is the same guarantee at the
        // one point every caller passes through. The dose that just fired
        // still materialises for its own slot; only the ones nobody was there
        // for are skipped, which is what a missed dose is.
        val floor = maxOf(notBeforeEpochMillis, nowEpochMillis())
        val next = schedulingEngine.nextOccurrenceFor(reminder, floor)
        if (next != null) {
            alarmScheduler.schedule(reminder.id, next.epochMillis)
            // The pre-alarm rides on the regular slot's answer (§9 #25): armed
            // only when its own instant is still ahead, so a reminder edited
            // twenty minutes before a dose with a thirty-minute lead gets no
            // pre-alarm for that dose rather than one in the past.
            val lead = reminder.preAlarmMinutes
            val preAt = lead?.let { next.epochMillis - it * 60_000L }
            if (preAt != null && preAt > nowEpochMillis()) alarmScheduler.schedulePreAlarm(reminder.id, next.epochMillis, preAt)
        } else {
            alarmScheduler.cancel(reminder.id)
        }
        // Explicit invalidation (spec §9 #4) -- covers both "alarm just fired,
        // rearming the next slot" and "dose resolved" (onDoseResolved routes
        // through here too), independent of the cache's reactive Flow watcher.
        occurrenceProjectionCache.invalidate()
    }
}
