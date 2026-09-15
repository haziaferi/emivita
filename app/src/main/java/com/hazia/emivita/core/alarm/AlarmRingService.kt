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
 * follow-up 1). A foreground service of type `mediaPlayback` whose
 * notification *is* the reminder's: posting an Alarm-tier reminder starts
 * this with the built notification, and `startForeground` shows it, so
 * the heads-up the user sees while the phone is unlocked and the sound
 * they hear come from the same place.
 *
 * Stops when: the dose is answered anywhere (Prendi / Rinvia / Salta on
 * the heads-up, the alarm screen, Today, the edit dialog -- every path
 * goes through [ReminderNotifications.dismiss], and the service also polls
 * the dose every few seconds so a path that does not cannot leave it
 * ringing); the notification is swiped away (the swipe action dismisses
 * too); or [RING_TIMEOUT_MILLIS] pass unanswered -- the owner's ten
 * minutes, the phone alarm's own default -- after which the sound stops and
 * the notification stays. Leaving the alarm screen without answering does
 * not stop it: it is the Alarm tier (the owner's decision, R80).
 *
 * Started from the alarm-fired receiver, which Android 12+ allows to start
 * a foreground service (an exact alarm firing is on the exemption list);
 * if a device refuses anyway, [ring] falls back to posting the notification
 * with `FLAG_INSISTENT`, which loops the channel's own tone.
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
                stopRinging(keepNotification = true)
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

    private fun stopRinging(keepNotification: Boolean) {
        watch?.cancel(); watch = null
        ringer?.stop(); ringer = null
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
