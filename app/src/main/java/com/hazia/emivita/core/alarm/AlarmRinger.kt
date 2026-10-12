package com.hazia.emivita.core.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * The Alarm tier's sound and vibration (§7 R59, R80): the chosen tone, else
 * the system's alarm tone, looped through a MediaPlayer on USAGE_ALARM (which
 * rings through vibrate mode and, by default, Do Not Disturb) with 500 ms on /
 * 500 ms off vibration until [stop], the two silent-switch settings read once
 * per ring (§9 #25).
 *
 * Owned by [AlarmRingService], not by a screen, because Android shows the
 * full-screen alarm only when the phone is locked or the display is off, and
 * an unlocked phone gets a heads-up whose channel sound plays once (R75).
 */
class AlarmRinger(private val context: Context, private val settings: SettingsRepository) {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    val isRinging: Boolean get() = player != null || vibrator != null

    suspend fun start() {
        if (isRinging) return
        val toneUri = settings.alarmRingtoneUri.first()?.let { it.toUri() } ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val noSoundWhenSilent = settings.alarmNoSoundWhenSilent.first()
        val noVibrationWhenSilent = settings.alarmNoVibrationWhenSilent.first()
        val ringerSilent = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode == AudioManager.RINGER_MODE_SILENT

        if (!(noSoundWhenSilent && ringerSilent)) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // The chosen tone, else the system's alarm tone -- and if the
            // chosen one cannot be opened (deleted file, revoked grant), the
            // default again rather than silence: medTimer's double fallback.
            player = runCatching { MediaPlayer.create(context, toneUri, null, attrs, 0) }.getOrNull()
                ?: runCatching { MediaPlayer.create(context, Settings.System.DEFAULT_ALARM_ALERT_URI, null, attrs, 0) }.getOrNull()
            player?.apply { isLooping = true; start() }
            if (player == null) Log.w(TAG, "Could not create the alarm MediaPlayer -- ringing without sound.")
        }

        if (!(noVibrationWhenSilent && ringerSilent)) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(500, 500), 0))
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private companion object {
        const val TAG = "AlarmRinger"
    }
}
