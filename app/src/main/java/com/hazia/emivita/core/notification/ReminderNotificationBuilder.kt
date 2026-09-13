package com.hazia.emivita.core.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hazia.emivita.R
import com.hazia.emivita.core.data.dao.DoseGroupDao
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.scheduling.EXTRA_REMINDER_ID
import com.hazia.emivita.core.scheduling.ReminderActionReceiver
import com.hazia.emivita.feature.alarm.AlarmActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.hazia.emivita.core.format.formatDecimal

const val ACTION_TAKE = "com.hazia.emivita.action.TAKE"
const val ACTION_SKIP = "com.hazia.emivita.action.SKIP"
const val ACTION_SNOOZE = "com.hazia.emivita.action.SNOOZE"
const val EXTRA_DOSE_EVENT_ID = "com.hazia.emivita.extra.DOSE_EVENT_ID"

private const val TAG = "ReminderNotification"

/**
 * Builds and posts the notification for a fired reminder. Resolves the
 * *effective* priority the way DoseGroup.priority's doc comment specifies:
 * a reminder that's part of a confirmed group uses the group's priority
 * instead of its own medicine's, for as long as the grouping is active.
 */
class ReminderNotificationBuilder(
    private val context: Context,
    private val doseGroupDao: DoseGroupDao,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun buildAndPost(doseEvent: DoseEvent, reminder: Reminder, medicine: Medicine) {
        val priority = effectivePriority(reminder, medicine)
        val channelId = ReminderNotificationChannels.channelIdFor(priority)
        // doseEvent.id.toInt(): same Long->Int caveat as AlarmScheduler's
        // reminderId.toInt() -- practically safe for a personal app's data volume.
        val notificationId = doseEvent.id.toInt()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(medicine.name)
            .setContentText(contentText(doseEvent))
            .setPriority(compatPriorityFor(priority))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .addAction(0, context.getString(R.string.common_take), actionPendingIntent(ACTION_TAKE, doseEvent.id, requestCodeSalt = 1))
            .addAction(0, context.getString(R.string.common_snooze), actionPendingIntent(ACTION_SNOOZE, doseEvent.id, requestCodeSalt = 3))

        // Lock-until-taken (spec §3's own bug writeup: this field was typed as
        // a plain Boolean and nothing read the resolved value at all -- both
        // fixed now). Omit Skip entirely when locked, rather than adding it
        // and disabling it, so there's no button at all to defensively guard
        // against surviving a later setting change (ReminderActionReceiver/
        // AlarmActivity still re-check independently regardless).
        if (!settingsRepository.isLockedUntilTaken(medicine)) {
            builder.addAction(0, context.getString(R.string.common_skip), actionPendingIntent(ACTION_SKIP, doseEvent.id, requestCodeSalt = 2))
        }

        if (priority == NotificationPriority.ALARM) {
            configureAlarmPresentation(builder, reminder.id, doseEvent.id)
        }

        postIfPermitted(context, TAG, notificationId, builder.build())
    }

    private suspend fun effectivePriority(reminder: Reminder, medicine: Medicine): NotificationPriority =
        reminder.groupId?.let { doseGroupDao.getById(it)?.priority } ?: medicine.notificationPriority

    private fun compatPriorityFor(priority: NotificationPriority): Int = when (priority) {
        NotificationPriority.STANDARD -> NotificationCompat.PRIORITY_DEFAULT
        NotificationPriority.HIGH, NotificationPriority.ALARM -> NotificationCompat.PRIORITY_HIGH
    }

    private fun contentText(doseEvent: DoseEvent): String {
        val time = Instant.ofEpochMilli(doseEvent.scheduledEpochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        return context.getString(R.string.notif_dose_body, formatDecimal(doseEvent.doseAmount), time)
    }

    /**
     * Full-screen alarm presentation, gated per the audit's §2.3 item:
     * `USE_FULL_SCREEN_INTENT` can be silently revoked by the user on API 34+,
     * so this checks [NotificationManagerCompat.canUseFullScreenIntent]
     * rather than assuming the intent will actually launch anything. If it's
     * not available, the alarm screen becomes a tap-to-open action instead
     * of something that's supposed to auto-launch and silently doesn't.
     */
    private fun configureAlarmPresentation(builder: NotificationCompat.Builder, reminderId: Long, doseEventId: Long) {
        // canUseFullScreenIntent() already handles the version branching
        // itself (true pre-29, a direct permission check for 29-33, the real
        // platform API from 34+) -- no need to re-guard by SDK_INT here.
        val canFullScreen = NotificationManagerCompat.from(context).canUseFullScreenIntent()

        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_DOSE_EVENT_ID, doseEventId)
        }
        val alarmPendingIntent = PendingIntent.getActivity(
            context,
            doseEventId.toInt(),
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // CATEGORY_ALARM, as medTimer sets it: tells the system and any
        // notification-aware tooling that this is an alarm, which is also what
        // Do Not Disturb's default "allow alarms" rule keys on.
        builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        if (canFullScreen) {
            builder.setFullScreenIntent(alarmPendingIntent, true)
        } else {
            Log.i(TAG, "Full-screen intent not available (revoked or unsupported) -- falling back to a tap action.")
            builder.addAction(0, context.getString(R.string.action_show_alarm), alarmPendingIntent)
        }
        builder.setContentIntent(alarmPendingIntent)
    }

    private fun actionPendingIntent(action: String, doseEventId: Long, requestCodeSalt: Int): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_DOSE_EVENT_ID, doseEventId)
        }
        // Each action needs its own distinct request code for the same
        // doseEventId, or PendingIntent.getBroadcast would return the same
        // instance (and therefore the same `action`) for all three buttons.
        val requestCode = (doseEventId * 10 + requestCodeSalt).toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
