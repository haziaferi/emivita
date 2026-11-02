package com.hazia.emivita.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hazia.emivita.EmivitaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "BootRescheduleReceiver"

/**
 * AlarmManager forgets every exact alarm across a reboot, and app updates on
 * some OEM skins can have similar effects -- this receiver recomputes and
 * re-arms every enabled reminder's next alarm from scratch in both cases.
 */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val container = (context.applicationContext as? EmivitaApplication)?.container
        if (container == null) {
            Log.e(TAG, "applicationContext is not an EmivitaApplication -- cannot reschedule after ${intent.action}.")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                // Before the re-arm: what passed while the phone was off was missed, not held (§7 R143).
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) AlarmWatchdog.recordBoot(context)
                container.schedulingCoordinator.rescheduleAll()
                // The future is re-armed; now the past (§9 #25, #26): slots that
                // passed while the phone was off become Missed rows, and the
                // last day's unanswered doses get their notification back.
                container.missedDoseBackfiller.backfill()
                container.alarmScheduler.rearmSnoozes()
                if (container.unansweredDoseReposter.repost() > 0) container.schedulingCoordinator.rescheduleAll()
                // A reboot forgets the weekly backup's alarm too (§7 R101).
                container.autoSyncManager.runWeekly()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to reschedule reminders after ${intent.action}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
