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

private const val TAG = "TimeChangeReceiver"

/**
 * Re-bases every alarm when the clock or the zone changes (§7 R60 B F-4;
 * medTimer's `TimeChangeReceiver`).
 *
 * AlarmManager holds absolute instants. A fixed-time reminder at 08:00 is
 * armed as "08:00 in the zone that was current when it was computed"; after
 * a +6 h zone change that instant is 14:00 on the new clock, and nothing
 * re-computed it until the alarm fired or the reminder was edited. Same shape
 * as BootRescheduleReceiver, same answer: re-arm everything from `now`.
 * TIME_SET matters less (an instant is an instant) but a clock moved back
 * leaves an alarm waiting the difference, so it is handled the same way.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val container = (context.applicationContext as? EmivitaApplication)?.container ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                container.schedulingCoordinator.rescheduleAll()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to reschedule after ${intent.action}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
