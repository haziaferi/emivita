package com.hazia.emivita.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.di.AppContainer
import com.hazia.emivita.core.notification.ACTION_SKIP
import com.hazia.emivita.core.notification.ACTION_SNOOZE
import com.hazia.emivita.core.notification.ACTION_TAKE
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.hazia.emivita.core.notification.ACTION_SWIPED
import com.hazia.emivita.core.data.settings.SwipeAction
import com.hazia.emivita.core.notification.ACTION_TAKE_ALL
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_IDS
import com.hazia.emivita.core.notification.ReminderNotifications

private const val TAG = "ReminderActionReceiver"

/**
 * Handles the Take / Skip / Snooze actions posted from a reminder
 * notification (see ReminderNotificationBuilder). Not exported -- only
 * reachable via this app's own notification action PendingIntents.
 *
 * Snooze deliberately does NOT cancel/track anything special if the dose
 * gets resolved from elsewhere (the Today screen, the alarm screen) before
 * the snooze fires: the snooze alarm re-enters ReminderAlarmReceiver's normal
 * handling, which already only re-notifies a dose event that's still
 * PENDING. A snoozed-then-resolved dose just silently no-ops when its
 * snooze fires -- one guard, not two places maintaining the same rule.
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_TAKE_ALL) {
            val ids = intent.getLongArrayExtra(EXTRA_DOSE_EVENT_IDS)?.toList().orEmpty()
            val container = (context.applicationContext as? EmivitaApplication)?.container ?: return
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    // Each dose independently: one already answered does not stop the rest.
                    for (id in ids) {
                        runCatching { handleAction(context, ACTION_TAKE, id, container) }
                            .onFailure { Log.w(TAG, "Take-all: dose $id failed", it) }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        val doseEventId = intent.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L)
        if (doseEventId < 0) {
            Log.w(TAG, "Received an action with no valid dose event id -- ignoring.")
            return
        }
        if (action == null || (action != ACTION_TAKE && action != ACTION_SKIP && action != ACTION_SNOOZE && action != ACTION_SWIPED)) {
            Log.w(TAG, "Received an unrecognized action: $action -- ignoring.")
            return
        }

        val container = (context.applicationContext as? EmivitaApplication)?.container
        if (container == null) {
            Log.e(TAG, "applicationContext is not an EmivitaApplication -- dropping action $action for doseEvent $doseEventId.")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                handleAction(context, action, doseEventId, container)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed handling action $action for doseEvent $doseEventId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleAction(
        context: Context,
        action: String,
        doseEventId: Long,
        container: AppContainer,
    ) {
        when (action) {
            ACTION_TAKE -> {
                container.doseEventRepository.resolve(doseEventId, DoseStatus.TAKEN)
                dismissNotification(context, doseEventId)
            }

            ACTION_SKIP -> {
                // Defensive re-check (spec §3) -- ReminderNotificationBuilder
                // already omits this button when locked, but a notification
                // posted before a setting change can still have a stale one.
                val doseEvent = container.doseEventRepository.getById(doseEventId)
                val medicine = doseEvent?.let { container.medicineRepository.getById(it.medicineId) }
                if (medicine != null && container.settingsRepository.isLockedUntilTaken(medicine)) {
                    Log.w(TAG, "Skip rejected for dose event $doseEventId -- reminders are locked until taken for ${medicine.name}.")
                    return
                }
                container.doseEventRepository.resolve(doseEventId, DoseStatus.SKIPPED)
                dismissNotification(context, doseEventId)
            }

            // Swiped away (§9 #25): the setting decides. Snooze is the default
            // and re-uses the snooze path below; Skip respects the lock the
            // way the button does (a locked medicine treats a swipe as
            // Nothing, logged); Nothing leaves the dose Due on Today.
            ACTION_SWIPED -> {
                when (container.settingsRepository.swipeAction.first()) {
                    SwipeAction.NOTHING -> Unit
                    SwipeAction.SNOOZE -> handleAction(context, ACTION_SNOOZE, doseEventId, container)
                    SwipeAction.SKIP -> {
                        val doseEvent = container.doseEventRepository.getById(doseEventId)
                        val medicine = doseEvent?.let { container.medicineRepository.getById(it.medicineId) }
                        if (medicine != null && container.settingsRepository.isLockedUntilTaken(medicine)) {
                            Log.i(TAG, "Swipe-to-skip ignored for dose event $doseEventId -- ${medicine.name} is locked until taken.")
                        } else if (doseEvent?.status == DoseStatus.PENDING) {
                            container.doseEventRepository.resolve(doseEventId, DoseStatus.SKIPPED)
                        }
                    }
                }
            }

            ACTION_SNOOZE -> {
                val doseEvent = container.doseEventRepository.getById(doseEventId)
                if (doseEvent == null) {
                    Log.w(TAG, "Snooze requested for missing dose event $doseEventId.")
                    return
                }
                if (doseEvent.status != DoseStatus.PENDING) {
                    dismissNotification(context, doseEventId)
                    return
                }
                val reminderId = doseEvent.reminderId
                if (reminderId == null) {
                    // PRN events have no reminder/alarm slot to re-fire -- nothing to snooze.
                    Log.w(TAG, "Snooze requested for PRN dose event $doseEventId, which has no reminder.")
                    dismissNotification(context, doseEventId)
                    return
                }
                val snoozeMinutes = container.settingsRepository.snoozeDurationMinutes.first()
                container.alarmScheduler.scheduleSnooze(
                    reminderId = reminderId,
                    doseEventId = doseEventId,
                    originalScheduledEpochMillis = doseEvent.scheduledEpochMillis,
                    fireAtEpochMillis = System.currentTimeMillis() + snoozeMinutes * 60_000L,
                )
                dismissNotification(context, doseEventId)
            }
        }
    }

    private fun dismissNotification(context: Context, doseEventId: Long) {
        ReminderNotifications.dismiss(context, doseEventId)
        // The summary's "Take all" must list only what is still showing.
        (context.applicationContext as? EmivitaApplication)?.container?.notificationBuilder?.updateGroupSummary()
    }
}
