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
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.core.format.maskedMedicineName
import kotlinx.coroutines.flow.first
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.alarm.AlarmRingService

const val ACTION_TAKE = "com.hazia.emivita.action.TAKE"
const val ACTION_SKIP = "com.hazia.emivita.action.SKIP"
const val ACTION_SNOOZE = "com.hazia.emivita.action.SNOOZE"
/** Fired by the notification's deleteIntent when the user swipes it away
 *  (§9 #25); what it records is [com.hazia.emivita.core.data.settings.SwipeAction]. */
const val ACTION_SWIPED = "com.hazia.emivita.action.SWIPED"
/** The group summary's one action (§9 #25): every listed dose is taken. */
const val ACTION_TAKE_ALL = "com.hazia.emivita.action.TAKE_ALL"
const val EXTRA_DOSE_EVENT_IDS = "com.hazia.emivita.extra.DOSE_EVENT_IDS"
const val REMINDER_GROUP_KEY = "com.hazia.emivita.group.REMINDERS"
const val REMINDER_GROUP_SUMMARY_ID = -3_000_000
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
    // The saved palette's accent (§7 R76): refreshed by every suspending post,
    // read as-is by the two summary builders, which cannot suspend.
    @Volatile private var accentArgb: Int = DEFAULT_NOTIFICATION_ACCENT_ARGB

    suspend fun buildAndPost(doseEvent: DoseEvent, reminder: Reminder, medicine: Medicine) {
        accentArgb = notificationAccentArgb(settingsRepository)
        val priority = effectivePriority(reminder, medicine)
        val channelId = ReminderNotificationChannels.channelIdFor(priority)
        // doseEvent.id.toInt(): same Long->Int caveat as AlarmScheduler's
        // reminderId.toInt() -- practically safe for a personal app's data volume.
        val notificationId = doseEvent.id.toInt()
        val hideNames = settingsRepository.hideMedicineNames.first()
        val title = maskedMedicineName(medicine.name, hideNames)

        // The lock-screen copy (§9 #25; Appendix F PT-06 / DP-F1). A phone set
        // to hide sensitive content used to show "Content hidden" with no
        // buttons, so a Standard or High reminder could not be answered
        // without unlocking; only the ALARM tier reached the lock screen. The
        // public version names no medicine and carries the same actions.
        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setColor(accentArgb)
            .setContentTitle(context.getString(R.string.notif_public_title))
            .setContentText(context.getString(R.string.notif_public_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, context.getString(R.string.common_take), actionPendingIntent(ACTION_TAKE, doseEvent.id, requestCodeSalt = 1))
            .addAction(0, context.getString(R.string.common_snooze), actionPendingIntent(ACTION_SNOOZE, doseEvent.id, requestCodeSalt = 3))
            .build()

        // Snooze: one tap for the saved duration, or the picker when the user
        // asked to choose each time (§9 #25; medTimer's "Custom…" preference).
        val askEachTime = settingsRepository.snoozeAskEachTime.first()
        val snoozeIntent = if (askEachTime) snoozePickerPendingIntent(doseEvent.id) else actionPendingIntent(ACTION_SNOOZE, doseEvent.id, requestCodeSalt = 3)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setColor(accentArgb)
            .setContentTitle(title)
            .setContentText(contentText(doseEvent, reminder, medicine))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText(doseEvent, reminder, medicine)))
            .setPriority(compatPriorityFor(priority))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            // Every reminder joins one group; the summary below carries
            // "Take all" once two or more are showing (§9 #25; medTimer's
            // combine, HomeMedkit's "Intake all"). Each keeps its own actions.
            .setGroup(REMINDER_GROUP_KEY)
            // Back-to-back reminders alert once (§9 #25, E B-06); the ALARM tier
            // is exempt -- an alarm that does not sound is not an alarm.
            .setSilent(priority != NotificationPriority.ALARM && NotificationRateLimit.shouldBeSilent())
            // A swipe records what the owner chose it to (Snooze by default);
            // the receiver reads the setting at swipe time, not post time.
            .setDeleteIntent(actionPendingIntent(ACTION_SWIPED, doseEvent.id, requestCodeSalt = 4))
            .addAction(0, context.getString(R.string.common_take), actionPendingIntent(ACTION_TAKE, doseEvent.id, requestCodeSalt = 1))
            .addAction(0, context.getString(R.string.common_snooze), snoozeIntent)

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
        } else {
            // Tapping the body opens the app (§7 R60 B F-1). Only the ALARM
            // tier had a content intent -- the full-screen one -- so a tap on
            // a Standard or High reminder did nothing at all. medTimer sets
            // one on every kind (`NotificationFactory.getStartAppIntent`).
            builder.setContentIntent(openAppPendingIntent(context, doseEvent.id.toInt()))
        }

        if (priority == NotificationPriority.ALARM && notificationsPermitted(context) && !ReminderNotificationChannels.isBlocked(context, priority)) {
            // The Alarm tier rings until answered (§7 R80): the service posts
            // this notification as its own and loops the tone under it. Not
            // when the user has blocked the Alarm channel in Android (§7 R81):
            // the tone would play for ten minutes under a notification the
            // system never shows, with no screen to answer it from. medTimer
            // is silent here by construction (its tone lives in the full-screen
            // activity the notification launches); the blocked-tier card in
            // Settings › Notifications says why nothing came.
            AlarmRingService.ring(context, doseEvent.id, builder.build())
        } else {
            postIfPermitted(context, TAG, notificationId, builder.build())
        }
        // The real reminder replaces its own pre-alarm.
        androidx.core.app.NotificationManagerCompat.from(context).cancel(preAlarmNotificationId(reminder.id))
        updateGroupSummary()
    }

    /** Post, refresh or remove the group summary from what is actually
     *  showing: the dose-event notifications (positive ids) in the group.
     *  Two or more -> a summary with "Take all" over exactly those ids; fewer
     *  -> no summary, so a lone reminder is a lone card. Called after every
     *  post and every dismissal, which is what keeps the id list honest. */
    fun updateGroupSummary() {
        val nm = androidx.core.app.NotificationManagerCompat.from(context)
        val showing = nm.activeNotifications
            .filter { it.id > 0 && it.notification.group == REMINDER_GROUP_KEY }
            .map { it.id.toLong() }
            .sorted()
        if (showing.size < 2) {
            nm.cancel(REMINDER_GROUP_SUMMARY_ID)
            return
        }
        val takeAllIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ACTION_TAKE_ALL
            putExtra(EXTRA_DOSE_EVENT_IDS, showing.toLongArray())
        }
        val takeAll = PendingIntent.getBroadcast(
            context, REMINDER_GROUP_SUMMARY_ID, takeAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = NotificationCompat.Builder(context, ReminderNotificationChannels.CHANNEL_STANDARD)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setColor(accentArgb)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_group_summary, showing.size, showing.size))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(REMINDER_GROUP_KEY)
            .setGroupSummary(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent(context, REMINDER_GROUP_SUMMARY_ID))
            .addAction(0, context.getString(R.string.notif_take_all), takeAll)
            .build()
        postIfPermitted(context, TAG, REMINDER_GROUP_SUMMARY_ID, summary)
    }

    private fun snoozePickerPendingIntent(doseEventId: Long): PendingIntent {
        val intent = Intent(context, com.hazia.emivita.feature.alarm.SnoozePickerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DOSE_EVENT_ID, doseEventId)
        }
        return PendingIntent.getActivity(
            context, (doseEventId * 10 + 5).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The silent pre-alarm (§9 #25): the medicine and the dose time, on the
     *  Standard channel, no sound, no actions, gone on tap or when the real
     *  reminder fires. Id in its own negative range so it cannot collide with
     *  a dose (positive) or an alert (negative reminder id). */
    suspend fun postPreAlarm(reminder: Reminder, medicine: Medicine, doseEpochMillis: Long) {
        accentArgb = notificationAccentArgb(settingsRepository)
        val hideNames = settingsRepository.hideMedicineNames.first()
        val time = LocaleFormats.time(doseEpochMillis)
        val notification = NotificationCompat.Builder(context, ReminderNotificationChannels.CHANNEL_STANDARD)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setColor(accentArgb)
            .setContentTitle(context.getString(R.string.notif_pre_alarm_title, maskedMedicineName(medicine.name, hideNames)))
            .setContentText(context.getString(R.string.notif_pre_alarm_text, time))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(context, preAlarmNotificationId(reminder.id)))
            .build()
        postIfPermitted(context, TAG, preAlarmNotificationId(reminder.id), notification)
    }

    private suspend fun effectivePriority(reminder: Reminder, medicine: Medicine): NotificationPriority =
        reminder.groupId?.let { doseGroupDao.getById(it)?.priority } ?: medicine.notificationPriority

    private fun compatPriorityFor(priority: NotificationPriority): Int = when (priority) {
        NotificationPriority.STANDARD -> NotificationCompat.PRIORITY_DEFAULT
        NotificationPriority.HIGH, NotificationPriority.ALARM -> NotificationCompat.PRIORITY_HIGH
    }

    private fun contentText(doseEvent: DoseEvent, reminder: Reminder, medicine: Medicine): String {
        val time = LocaleFormats.time(doseEvent.scheduledEpochMillis)
        val parts = mutableListOf(context.getString(R.string.notif_dose_body, formatDecimalLocalized(doseEvent.doseAmount), time))
        // Instructions first (§9 #26; medTimer MT-06): "with food" is the one
        // line that belongs on the notification, before the stock count.
        reminder.instructions?.takeIf { it.isNotBlank() }?.let { parts += it }
        // "N left after this dose" when stock is tracked (§9 #25; Appendix F
        // HM-18) -- the text only, never the take-blocking HomeMedkit does.
        val stock = medicine.stockInfo
        if (stock.unit != null) {
            val left = (stock.quantity - doseEvent.doseAmount).coerceAtLeast(0.0)
            parts += context.getString(R.string.notif_left_after_dose, formatDecimalLocalized(left), stock.unit)
        }
        return parts.joinToString(" · ")
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

/** Negative, below the alert range (-reminderId) by a fixed offset. */
fun preAlarmNotificationId(reminderId: Long): Int = -(2_000_000 + reminderId.toInt())
