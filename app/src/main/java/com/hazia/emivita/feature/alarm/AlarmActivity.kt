package com.hazia.emivita.feature.alarm

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.di.AppContainer
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import com.hazia.emivita.core.scheduling.DEFAULT_SNOOZE_MINUTES
import com.hazia.emivita.core.scheduling.EXTRA_REMINDER_ID
import com.hazia.emivita.ui.theme.EmivitaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.media.AudioManager
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import androidx.core.net.toUri
import com.hazia.emivita.core.locale.AppLocale

/**
 * Full-screen target for ALARM-priority reminders (see
 * ReminderNotificationBuilder.configureAlarmPresentation), launched either
 * automatically via the full-screen intent or by tapping the notification's
 * fallback action when full-screen intents aren't available.
 *
 * Deliberately minimal: Take/Skip/Snooze work end-to-end, but the visual
 * design (the "triangle-button alarm view" the manifest's own comment
 * anticipates) is layout/frontend work for later, same as everywhere else in
 * this app. Don't mistake this Composable for a design decision.
 */
class AlarmActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    // The half of medTimer's ReminderAlarmActivity that the port left out
    // (§7 R59): this screen showed over the lock screen and turned the
    // display on, and then sat there silently. medTimer loops the alarm
    // ringtone through a MediaPlayer on USAGE_ALARM -- the alarm stream,
    // which rings through vibrate mode and, by default, through Do Not
    // Disturb -- and vibrates 500ms on / 500ms off until the dose is
    // answered. Found on the device: "alarm firing but no sound".
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        // Full screen means full screen: no status or navigation bars, the
        // same call medTimer makes.
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val doseEventId = intent.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L)

        setContent {
            EmivitaTheme {
                AlarmScreen(
                    reminderId = reminderId,
                    doseEventId = doseEventId,
                    onFinished = { finish() },
                )
            }
        }
    }

    // Ring while the screen is in front; stop the moment it is not. Leaving
    // for Home without answering stops the noise but keeps the notification,
    // which is the same trade medTimer makes in its pauseAlarm.
    override fun onStart() { super.onStart(); startRinging() }
    override fun onStop() { stopRinging(); super.onStop() }
    override fun onDestroy() { stopRinging(); super.onDestroy() }

    private var ringingJob: Job? = null

    private fun startRinging() {
        if (player != null || ringingJob?.isActive == true) return
        val settings = (application as EmivitaApplication).container.settingsRepository
        // The three settings are read once per ring (§9 #25; medTimer's
        // AlarmSettingsFragment): the chosen tone, and whether the phone's
        // silent switch should be respected for sound and for vibration.
        // USAGE_ALARM rings through vibrate mode by design; these two switches
        // are the user's way of saying "not when I have silenced the phone".
        ringingJob = lifecycleScope.launch {
            val toneUri = settings.alarmRingtoneUri.first()?.let { it.toUri() } ?: Settings.System.DEFAULT_ALARM_ALERT_URI
            val noSoundWhenSilent = settings.alarmNoSoundWhenSilent.first()
            val noVibrationWhenSilent = settings.alarmNoVibrationWhenSilent.first()
            val ringerSilent = (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode == AudioManager.RINGER_MODE_SILENT
            if (isDestroyed || isFinishing) return@launch

            if (!(noSoundWhenSilent && ringerSilent)) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                // The chosen tone, else the system's alarm tone -- and if the
                // chosen one cannot be opened (deleted file, revoked grant), the
                // default again rather than silence: medTimer's double fallback.
                player = runCatching { MediaPlayer.create(this@AlarmActivity, toneUri, null, attrs, 0) }.getOrNull()
                    ?: runCatching { MediaPlayer.create(this@AlarmActivity, Settings.System.DEFAULT_ALARM_ALERT_URI, null, attrs, 0) }.getOrNull()
                player?.apply { isLooping = true; start() }
                if (player == null) Log.w(TAG, "Could not create the alarm MediaPlayer -- ringing without sound.")
            }

            if (!(noVibrationWhenSilent && ringerSilent)) {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(500, 500), 0))
            }
        }
    }

    private fun stopRinging() {
        ringingJob?.cancel()
        ringingJob = null
        runCatching { player?.stop() }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private companion object {
        const val TAG = "AlarmActivity"
    }
}

@Composable
private fun AlarmScreen(reminderId: Long, doseEventId: Long, onFinished: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as EmivitaApplication).container }
    val scope = rememberCoroutineScope()

    var medicine by remember { mutableStateOf<Medicine?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var snoozeMinutes by remember { mutableIntStateOf(DEFAULT_SNOOZE_MINUTES.toInt()) }

    LaunchedEffect(reminderId, doseEventId) {
        snoozeMinutes = container.settingsRepository.snoozeDurationMinutes.first()
        if (reminderId < 0 || doseEventId < 0) {
            loadFailed = true
            return@LaunchedEffect
        }
        val reminder = container.reminderRepository.getById(reminderId)
        val med = reminder?.let { container.medicineRepository.getById(it.medicineId) }
        if (reminder == null || med == null) {
            loadFailed = true
        } else {
            medicine = med
            locked = container.settingsRepository.isLockedUntilTaken(med)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (loadFailed) {
                Text(stringResource(R.string.alarm_this_reminder_is_no_longer_available))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onFinished) { Text(stringResource(R.string.alarm_close)) }
                return@Column
            }

            Text(
                text = medicine?.name ?: stringResource(R.string.common_loading),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    scope.launch {
                        resolveAndDismiss(container, context, doseEventId, DoseStatus.TAKEN)
                        onFinished()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.common_take)) }

            // Lock-until-taken (spec §3): omit Skip entirely when locked --
            // same "no button at all" choice ReminderNotificationBuilder makes,
            // not a disabled one.
            if (!locked) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            resolveAndDismiss(container, context, doseEventId, DoseStatus.SKIPPED)
                            onFinished()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_skip)) }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = {
                    scope.launch {
                        snoozeAndDismiss(container, context, doseEventId, snoozeMinutes)
                        onFinished()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.alarm_snooze_minutes, snoozeMinutes)) }
        }
    }
}

private suspend fun resolveAndDismiss(
    container: AppContainer,
    context: Context,
    doseEventId: Long,
    status: DoseStatus,
) {
    if (status == DoseStatus.SKIPPED) {
        // Defensive re-check (spec §3) -- the Skip button is already omitted
        // when locked, but re-verify at the moment of action rather than
        // trusting the composition's snapshot in case the setting changed
        // while this screen was on screen.
        val event = container.doseEventRepository.getById(doseEventId)
        val medicine = event?.let { container.medicineRepository.getById(it.medicineId) }
        if (medicine != null && container.settingsRepository.isLockedUntilTaken(medicine)) {
            return
        }
    }
    container.doseEventRepository.resolve(doseEventId, status)
    NotificationManagerCompat.from(context).cancel(doseEventId.toInt())
}

private suspend fun snoozeAndDismiss(container: AppContainer, context: Context, doseEventId: Long, snoozeMinutes: Int) {
    val event = container.doseEventRepository.getById(doseEventId)
    val reminderId = event?.reminderId
    if (event != null && reminderId != null) {
        container.alarmScheduler.scheduleSnooze(
            reminderId = reminderId,
            doseEventId = doseEventId,
            originalScheduledEpochMillis = event.scheduledEpochMillis,
            fireAtEpochMillis = System.currentTimeMillis() + snoozeMinutes * 60_000L,
        )
    }
    NotificationManagerCompat.from(context).cancel(doseEventId.toInt())
}
