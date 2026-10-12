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
 * medTimer's `TimeChangeReceiver`), re-arming everything from `now` as
 * BootRescheduleReceiver does. AlarmManager holds absolute instants, so after a
 * +6 h zone change an 08:00 reminder would otherwise fire at 14:00 on the new
 * clock. TIME_SET is handled the same way because a clock moved back leaves an
 * alarm waiting the difference.
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
