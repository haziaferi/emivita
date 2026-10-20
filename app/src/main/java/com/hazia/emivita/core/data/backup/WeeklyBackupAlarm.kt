package com.hazia.emivita.core.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hazia.emivita.EmivitaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The weekly copy's alarm (§7 R101): inexact, so Android may shift it to save
 * battery (the owner's choice); the launch check and the after-every-change
 * write catch up whatever it misses. One PendingIntent, replaced on each arm.
 */
object WeeklyBackupAlarm {
    fun arm(context: Context, atEpochMillis: Long) {
        context.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC_WAKEUP, atEpochMillis, pendingIntent(context))
    }

    internal fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0, Intent(context, WeeklyBackupReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class WeeklyBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? EmivitaApplication)?.container ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                container.autoSyncManager.runWeekly()
            } catch (t: Throwable) {
                Log.e("WeeklyBackupReceiver", "weekly backup failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
