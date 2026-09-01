package com.hazia.emivita.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.hazia.emivita.core.data.entity.NotificationPriority

/**
 * One notification channel per [NotificationPriority] tier. Call
 * [createAll] once, early in [com.hazia.emivita.EmivitaApplication.onCreate].
 *
 * Not doing yet: requesting `ACCESS_NOTIFICATION_POLICY` / DND-bypass for
 * the Alarm channel. That's a separate, user-facing consent flow (similar
 * in weight to notification-listener access) and isn't declared in the
 * manifest yet -- calling `setBypassDnd(true)` without it would silently
 * do nothing, which is worse than not calling it at all.
 */
object ReminderNotificationChannels {
    const val CHANNEL_STANDARD = "reminders_standard"
    const val CHANNEL_HIGH = "reminders_high"
    const val CHANNEL_ALARM = "reminders_alarm"

    fun channelIdFor(priority: NotificationPriority): String = when (priority) {
        NotificationPriority.STANDARD -> CHANNEL_STANDARD
        NotificationPriority.HIGH -> CHANNEL_HIGH
        NotificationPriority.ALARM -> CHANNEL_ALARM
    }

    fun createAll(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val standard = NotificationChannel(
            CHANNEL_STANDARD,
            "Medicine reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Regular reminders for medicines and supplements."
        }

        val high = NotificationChannel(
            CHANNEL_HIGH,
            "Important medicine reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Higher-priority reminders that shouldn't be easy to miss."
        }

        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            "Alarm-style medicine reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Full-screen alarm reminders for medicines that must not be skipped."
        }

        manager.createNotificationChannels(listOf(standard, high, alarm))
    }
}
