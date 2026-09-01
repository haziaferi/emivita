package com.hazia.emivita.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "ReminderAlarmReceiver"

/**
 * Fired by AlarmManager via the PendingIntent [AlarmScheduler] built.
 * Not exported in the manifest -- nothing outside this app can trigger it.
 *
 * `onReceive` isn't suspend and Android gives receivers only a short window
 * before treating them as unresponsive, so this uses the standard
 * `goAsync()` + coroutine pattern: get a [android.content.BroadcastReceiver.PendingResult],
 * do the real work off the main thread, and always call `finish()`.
 *
 * [EXTRA_IS_NAG] branches to [handleNagFire] instead of [handleAlarm] --
 * ported from medTimer's own design (`RepeatProcessor`/`AlarmProcessor`'s
 * secondary-alarm slot), where a repeat/nag re-fire deliberately does NOT
 * go through the same path as a genuine new occurrence: [handleAlarm]'s
 * `reschedulesImmediatelyOnFire()` and cascade-reschedule side effects are
 * meant to fire once per real occurrence, not once per nag -- sharing the
 * path would re-trigger them on every nag re-notification.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        if (reminderId < 0) {
            Log.w(TAG, "Received alarm with no valid reminder id -- ignoring.")
            return
        }
        val scheduledEpochMillis = intent.getLongExtra(EXTRA_TRIGGER_AT_EPOCH_MILLIS, System.currentTimeMillis())
        val isNag = intent.getBooleanExtra(EXTRA_IS_NAG, false)
        // Safe cast, not `as` -- this runs synchronously in onReceive(), before
        // goAsync()/the coroutine's own try/catch exist to catch anything. An
        // unexpected Application type here should log and drop this one
        // alarm, not crash the whole process.
        val container = (context.applicationContext as? EmivitaApplication)?.container
        if (container == null) {
            Log.e(TAG, "applicationContext is not an EmivitaApplication -- dropping alarm for reminder $reminderId.")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                if (isNag) {
                    handleNagFire(reminderId, scheduledEpochMillis, container)
                } else {
                    handleAlarm(reminderId, scheduledEpochMillis, container)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed handling alarm for reminder $reminderId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAlarm(
        reminderId: Long,
        scheduledEpochMillis: Long,
        container: com.hazia.emivita.core.di.AppContainer,
    ) {
        val reminder = container.reminderRepository.getById(reminderId)
        if (reminder == null || !reminder.enabled) {
            Log.i(TAG, "Reminder $reminderId is gone or disabled -- nothing to materialize.")
            return
        }
        val medicine = container.medicineRepository.getById(reminder.medicineId)
        if (medicine == null) {
            Log.w(TAG, "Reminder $reminderId references missing medicine ${reminder.medicineId}.")
            return
        }

        val config = decodeReminderConfig(reminder.configJson)
        val doseAmount = config.doseAmountOrNull()
        if (doseAmount == null) {
            // LOW_STOCK_ALERT / EXPIRATION_ALERT are never materialized as
            // dose events -- see SchedulingEngine.supportedTypes.
            Log.i(TAG, "Reminder $reminderId is not a dose-producing type yet -- skipping.")
            return
        }

        val doseEventId = container.doseEventRepository.materializeIfAbsent(
            reminderId = reminderId,
            medicineId = reminder.medicineId,
            scheduledEpochMillis = scheduledEpochMillis,
            doseAmount = doseAmount,
        )

        // null from materializeIfAbsent means a DoseEvent for this exact
        // reminder+time already exists (e.g. a duplicate alarm delivery) --
        // still worth re-showing the notification using the existing event,
        // rather than silently doing nothing.
        val doseEvent = if (doseEventId != null) {
            container.doseEventRepository.getById(doseEventId)
        } else {
            container.doseEventRepository.findByReminderAndTime(reminderId, scheduledEpochMillis)
        }

        if (doseEvent == null) {
            Log.e(TAG, "Could not materialize or find a DoseEvent for reminder $reminderId at $scheduledEpochMillis.")
        } else if (doseEvent.status == DoseStatus.PENDING) {
            if (reminder.autoResolve) {
                // Silent tracking for another person (spec §3/§4) -- resolved
                // TAKEN immediately (stock still decrements via the normal
                // resolve() path), no notification built at all. Intended for
                // a reminder whose Medicine belongs to a Profile other than
                // the device owner's.
                container.doseEventRepository.resolve(doseEvent.id, DoseStatus.TAKEN)
            } else {
                // Only post a notification while the dose is still unresolved --
                // a duplicate alarm delivery after the user already acted on it
                // shouldn't re-notify.
                container.notificationBuilder.buildAndPost(doseEvent, reminder, medicine)
                scheduleFirstNagIfConfigured(reminder, doseEvent, scheduledEpochMillis, container)
            }
        }

        if (config.reschedulesImmediatelyOnFire()) {
            // +1 minute, not "now": guarantees the calculator can't return
            // the same slot that just fired, regardless of how fast this
            // code happens to run relative to the alarm's trigger time.
            container.schedulingCoordinator.onReminderChanged(reminderId, scheduledEpochMillis + 60_000L)
        }
        // Otherwise (ON_RESOLVED interval/window): the following occurrence
        // is scheduled by onDoseResolved() once the user actually taps
        // Take/Skip, not here.

        // LinkedFollowing cascade, fire-triggered side (spec §3/§9 #14) --
        // one level down only: direct children whose config is ON_REMINDED
        // get rescheduled now that this reminder's anchor (its most recent
        // materialized event) just changed. ON_RESOLVED children are the
        // other trigger point, in RealSchedulingCoordinator.onDoseResolved.
        cascadeRescheduleOnRemindedChildren(reminderId, container)
    }

    /** Nagging (spec §3/§4 `repeatOnUnanswered`), ported from medTimer's
     *  `ReminderNotificationProcessor.notificationAction` -- right after the
     *  notification is (re-)shown, check whether this reminder opts into
     *  nagging and whether the dose has room left under its `maxRepeats`
     *  cap; if so, arm the nag slot. Runs after both a genuine first fire
     *  and a snooze re-fire (both reach here via [handleAlarm]'s notification
     *  branch) -- a snoozed-then-re-shown notification is still unanswered
     *  and still deserves the nag safety net; `FLAG_UPDATE_CURRENT` means a
     *  snooze simply resets the nag countdown rather than doubling up. */
    private fun scheduleFirstNagIfConfigured(
        reminder: com.hazia.emivita.core.data.entity.Reminder,
        doseEvent: com.hazia.emivita.core.data.entity.DoseEvent,
        scheduledEpochMillis: Long,
        container: com.hazia.emivita.core.di.AppContainer,
    ) {
        val repeatConfig = reminder.repeatOnUnanswered ?: return
        if (doseEvent.repeatCount >= repeatConfig.maxRepeats) return
        container.alarmScheduler.scheduleNag(
            reminderId = reminder.id,
            originalScheduledEpochMillis = scheduledEpochMillis,
            fireAtEpochMillis = System.currentTimeMillis() + repeatConfig.delayMinutes * 60_000L,
        )
    }

    /** The nag-fire counterpart to [handleAlarm] -- re-shows the notification
     *  for a still-unanswered dose, increments [com.hazia.emivita.core.data.entity.DoseEvent.repeatCount],
     *  and re-arms the next nag if [com.hazia.emivita.core.data.entity.RepeatConfig.maxRepeats]
     *  hasn't been reached yet. Deliberately does not touch
     *  `schedulingCoordinator`/cascade-reschedule at all -- see this class's
     *  own doc comment for why nag re-fires and the regular occurrence chain
     *  must stay separate (matches medTimer's own `RepeatProcessor` being a
     *  wholly separate path from `ScheduleNextReminderNotificationProcessor`). */
    private suspend fun handleNagFire(
        reminderId: Long,
        scheduledEpochMillis: Long,
        container: com.hazia.emivita.core.di.AppContainer,
    ) {
        val reminder = container.reminderRepository.getById(reminderId)
        if (reminder == null || !reminder.enabled) {
            Log.i(TAG, "Nag fire for reminder $reminderId, but it's gone or disabled -- nothing to do.")
            return
        }
        val repeatConfig = reminder.repeatOnUnanswered
        if (repeatConfig == null) {
            Log.w(TAG, "Nag fire for reminder $reminderId, but repeatOnUnanswered is now null -- was disabled after this nag was scheduled.")
            return
        }
        val medicine = container.medicineRepository.getById(reminder.medicineId) ?: return

        // Normally already resolved-and-cancelled (DoseEventRepository.resolve()
        // cancels the pending nag alarm the moment the dose is answered) -- this
        // PENDING check is the defensive fallback for the race where a nag was
        // already in-flight in AlarmManager the instant the user answered.
        val doseEvent = container.doseEventRepository.findByReminderAndTime(reminderId, scheduledEpochMillis)
        if (doseEvent == null || doseEvent.status != DoseStatus.PENDING) {
            Log.i(TAG, "Nag fire for reminder $reminderId, but its dose event is no longer pending -- nothing to do.")
            return
        }

        container.doseEventRepository.incrementRepeatCount(doseEvent.id)
        val newRepeatCount = doseEvent.repeatCount + 1
        Log.d(TAG, "Nag re-fire ${newRepeatCount}/${repeatConfig.maxRepeats} for reminder $reminderId.")
        container.notificationBuilder.buildAndPost(doseEvent, reminder, medicine)

        if (newRepeatCount < repeatConfig.maxRepeats) {
            container.alarmScheduler.scheduleNag(
                reminderId = reminderId,
                originalScheduledEpochMillis = scheduledEpochMillis,
                fireAtEpochMillis = System.currentTimeMillis() + repeatConfig.delayMinutes * 60_000L,
            )
        }
    }

    private suspend fun cascadeRescheduleOnRemindedChildren(
        reminderId: Long,
        container: com.hazia.emivita.core.di.AppContainer,
    ) {
        val children = container.reminderRepository.getChildrenOf(reminderId)
        for (child in children) {
            if (!child.enabled) continue
            val childConfig = decodeReminderConfig(child.configJson)
            if (childConfig is ReminderConfig.LinkedFollowing && childConfig.startBehavior == IntervalStartBehavior.ON_REMINDED) {
                container.schedulingCoordinator.onReminderChanged(child.id, System.currentTimeMillis())
            }
        }
    }
}
