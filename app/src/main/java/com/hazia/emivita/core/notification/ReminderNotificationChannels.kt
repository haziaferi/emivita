package com.hazia.emivita.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.R
import androidx.annotation.StringRes
import android.media.AudioAttributes
import android.provider.Settings

/**
 * One notification channel per [NotificationPriority] tier; call [createAll]
 * once, early in [com.hazia.emivita.EmivitaApplication.onCreate].
 *
 * Deliberately no DND bypass for the Alarm channel yet: it needs
 * `ACCESS_NOTIFICATION_POLICY`, a separate user-facing consent flow not in the
 * manifest, and `setBypassDnd(true)` without it silently does nothing.
 */
object ReminderNotificationChannels {
    const val CHANNEL_STANDARD = "reminders_standard"
    const val CHANNEL_HIGH = "reminders_high"
    // "_v2": a NotificationChannel's sound, importance and vibration are
    // fixed the moment it is first created and the app cannot change them
    // afterwards -- only the user can. The original "reminders_alarm" was
    // created with the defaults, i.e. the notification chime on the
    // notification stream, and every installed device already has it that
    // way. A new id is the only way to ship the alarm sound; the old channel
    // is deleted in createAll (§7 R59).
    const val CHANNEL_ALARM = "reminders_alarm_v2"
    private const val LEGACY_CHANNEL_ALARM = "reminders_alarm"

    fun channelIdFor(priority: NotificationPriority): String = when (priority) {
        NotificationPriority.STANDARD -> CHANNEL_STANDARD
        NotificationPriority.HIGH -> CHANNEL_HIGH
        NotificationPriority.ALARM -> CHANNEL_ALARM
    }

    /** The tier's name as the Notifications & Alerts screen prints it, so a
     *  warning about a blocked tier names the same row the user just read.
     *  @StringRes rather than String since §9 #19: the screen's tier rows
     *  resolve these same ids, so the two cannot drift into naming the same
     *  tier differently. */
    @StringRes
    fun tierNameFor(priority: NotificationPriority): Int = when (priority) {
        NotificationPriority.STANDARD -> R.string.tier_standard
        NotificationPriority.HIGH -> R.string.tier_high
        NotificationPriority.ALARM -> R.string.tier_alarm
    }

    /**
     * Tiers the user has blocked outright (`IMPORTANCE_NONE`) -- see
     * [com.hazia.emivita.core.notification.NotificationDelivery.TiersBlocked]
     * for why only `NONE` counts and a merely-quietened channel does not.
     *
     * Walks [NotificationPriority.entries] rather than a hand-written id list,
     * so a new tier (which must add branches to [channelIdFor] and
     * [tierNameFor] to compile) is covered here without anything to forget. A
     * channel that does not exist yet reads as not blocked, which only happens
     * before [createAll] has run in `EmivitaApplication.onCreate`.
     */
    fun blockedTiers(context: Context): List<String> =
        NotificationPriority.entries.filter { isBlocked(context, it) }.map { context.getString(tierNameFor(it)) }

    /** True when Android has this tier's channel at IMPORTANCE_NONE: nothing posted to it is
     *  ever shown, so nothing should ring for it either (§7 R81). */
    fun isBlocked(context: Context, priority: NotificationPriority): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return manager.getNotificationChannel(channelIdFor(priority))?.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun createAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val standard = NotificationChannel(
            CHANNEL_STANDARD,
            context.getString(R.string.channel_standard_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_standard_desc)
        }

        val high = NotificationChannel(
            CHANNEL_HIGH,
            context.getString(R.string.channel_high_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_high_desc)
        }

        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_desc)
            // The alarm ringtone on the ALARM stream, so the heads-up this
            // channel produces while the phone is in use rings like an alarm
            // rather than chiming like a message. AlarmActivity plays its own
            // looping tone when it is on screen; this covers the case where
            // Android shows a banner instead (screen on and unlocked).
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 500, 500)
        }

        // The pre-v2 alarm channel carried the notification chime; retire it
        // so Android's settings do not list two "Alarm-style" channels.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ALARM)
        manager.createNotificationChannels(listOf(standard, high, alarm))
    }
}
