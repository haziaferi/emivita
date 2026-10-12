package com.hazia.emivita.core.alarm

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.core.data.entity.DoseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rings an Alarm-tier reminder until it is answered (§7 R80; R75's
 * follow-up 1), as a `mediaPlayback` foreground service whose notification
 * *is* the reminder's, so the heads-up and the sound come from one place.
 *
 * Stops when the dose is answered anywhere (every path goes through
 * [ReminderNotifications.dismiss], and a poll every few seconds catches one
 * that does not), when the notification is swiped away, or after
 * [RING_TIMEOUT_MILLIS] (the owner's ten minutes) with the notification left
 * in place, but not on leaving the alarm screen unanswered (owner's decision,
 * R80), and an exact alarm may start it on Android 12+, with [ring] falling
 * back to `FLAG_INSISTENT` if a device refuses anyway.
 */
class AlarmRingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ringer: AlarmRinger? = null
    private var watch: Job? = null
    private var doseEventId: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                val id = intent.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L)
                val notification = IntentCompat.getParcelableExtra(intent, EXTRA_NOTIFICATION, Notification::class.java)
                if (id < 0 || notification == null) { stopSelf(); return START_NOT_STICKY }
                // Within the 5 s the platform allows, before anything suspends.
                ServiceCompat.startForeground(this, id.toInt(), notification, foregroundType())
                if (doseEventId == id && ringer?.isRinging == true) return START_NOT_STICKY
                // Silence a previous dose only; stopForeground here would undo
                // the startForeground above (§7 R83 (d)).
                stopSound()
                doseEventId = id
                val container = (application as EmivitaApplication).container
                val r = AlarmRinger(this, container.settingsRepository)
                ringer = r
                watch = scope.launch {
                    r.start()
                    // elapsedRealtime, not the wall clock: a duration, unaffected by a
                    // clock change while ringing.
                    val started = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - started < RING_TIMEOUT_MILLIS) {
                        delay(POLL_MILLIS)
                        val event = container.doseEventRepository.getById(id)
                        if (event == null || event.status != DoseStatus.PENDING) {
                            Log.i(TAG, "dose $id answered elsewhere -- stopping")
                            stopRinging(keepNotification = false); stopSelf(); return@launch
                        }
                    }
                    Log.i(TAG, "dose $id unanswered after ${RING_TIMEOUT_MILLIS / 60_000} min -- sound stops, notification stays")
                    stopRinging(keepNotification = true); stopSelf()
                }
            }
            ACTION_STOP -> {
                val id = intent.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L)
                if (id == doseEventId || id < 0) { stopRinging(keepNotification = false); stopSelf() }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopSound() {
        watch?.cancel(); watch = null
        ringer?.stop(); ringer = null
    }

    private fun stopRinging(keepNotification: Boolean) {
        stopSound()
        ServiceCompat.stopForeground(this, if (keepNotification) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        ringer?.stop(); ringer = null
        watch?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun foregroundType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    companion object {
        private const val TAG = "AlarmRingService"
        const val ACTION_RING = "com.hazia.emivita.action.RING"
        const val ACTION_STOP = "com.hazia.emivita.action.STOP_RINGING"
        const val EXTRA_DOSE_EVENT_ID = "dose_event_id"
        const val EXTRA_NOTIFICATION = "notification"
        const val RING_TIMEOUT_MILLIS = 10 * 60_000L
        const val POLL_MILLIS = 5_000L

        /** Posts [notification] as this service's own and rings for [doseEventId]. */
        fun ring(context: Context, doseEventId: Long, notification: Notification) {
            val intent = Intent(context, AlarmRingService::class.java)
                .setAction(ACTION_RING)
                .putExtra(EXTRA_DOSE_EVENT_ID, doseEventId)
                .putExtra(EXTRA_NOTIFICATION, notification)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "foreground start refused -- posting insistent instead", e)
                    notification.flags = notification.flags or Notification.FLAG_INSISTENT
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        NotificationManagerCompat.from(context).notify(doseEventId.toInt(), notification)
                    }
                } else throw e
            }
        }

        fun stop(context: Context, doseEventId: Long) {
            // A plain start: the service may not be running, and a plain
            // startService to a stopped service is a cheap no-op path.
            runCatching {
                context.startService(Intent(context, AlarmRingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_DOSE_EVENT_ID, doseEventId))
            }
        }
    }
}
