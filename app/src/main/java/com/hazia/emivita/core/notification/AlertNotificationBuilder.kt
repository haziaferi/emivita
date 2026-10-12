package com.hazia.emivita.core.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.scheduling.AlertActionReceiver
import java.time.LocalDate
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.core.format.maskedMedicineName
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

const val ACTION_REFILL = "com.hazia.emivita.action.REFILL"
const val EXTRA_MEDICINE_ID = "com.hazia.emivita.extra.MEDICINE_ID"
const val EXTRA_REFILL_AMOUNT = "com.hazia.emivita.extra.REFILL_AMOUNT"
const val EXTRA_NOTIFICATION_ID = "com.hazia.emivita.extra.NOTIFICATION_ID"
/** A stock alert was swiped away: the summary's count must follow (§9 #27). */
const val ACTION_ALERT_DISMISSED = "com.hazia.emivita.action.ALERT_DISMISSED"
const val STOCK_GROUP_KEY = "com.hazia.emivita.group.STOCK"
const val STOCK_GROUP_SUMMARY_ID = -4_000_000

private const val TAG = "AlertNotification"

/**
 * Builds and posts LowStockAlert/ExpirationAlert notifications (spec §9 #8).
 * Deliberately separate from [ReminderNotificationBuilder] rather than a
 * third branch bolted onto it -- that class is keyed entirely off a
 * materialized [com.hazia.emivita.core.data.entity.DoseEvent] (Take/Skip/
 * Snooze act on a specific dose instance); these alerts are keyed off a
 * [Reminder]'s id directly, since there's no dose event and never will be --
 * a stock/expiration alert isn't a dose.
 *
 * Notification IDs are negated reminder ids so they can never collide with
 * [ReminderNotificationBuilder]'s `doseEvent.id.toInt()` ids, which are
 * always positive (Room autoincrement starts at 1).
 */
class AlertNotificationBuilder(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    // The saved palette's accent (§7 R76): refreshed by every suspending post,
    // read as-is by the two summary builders, which cannot suspend.
    @Volatile private var accentArgb: Int = DEFAULT_NOTIFICATION_ACCENT_ARGB


    suspend fun postLowStock(medicine: Medicine, reminder: Reminder) {
        accentArgb = notificationAccentArgb(settingsRepository)
        val notificationId = -(reminder.id.toInt())
        val name = maskedMedicineName(medicine.name, settingsRepository.hideMedicineNames.first())
        val unit = medicine.stockInfo.unit?.let { " $it" } ?: ""
        val builder = NotificationCompat.Builder(context, ReminderNotificationChannels.channelIdFor(medicine.notificationPriority))
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setColor(accentArgb)
            .setContentTitle(context.getString(R.string.alert_low_stock_title, name))
            .setContentText(context.getString(R.string.alert_low_stock_text, formatDecimalLocalized(medicine.stockInfo.quantity) + unit))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(context, notificationId))
            .setGroup(STOCK_GROUP_KEY)
            .setDeleteIntent(dismissedPendingIntent(notificationId))

        // One-tap restock (spec §3/§9 #2/#8) -- only offered when a refill
        // amount is actually configured; otherwise there's nothing to log
        // without asking the user a question a notification action can't ask.
        val refillAmount = medicine.stockInfo.refillQuantity
        if (refillAmount != null && refillAmount > 0.0) {
            builder.addAction(0, context.getString(R.string.action_refill), refillPendingIntent(medicine.id, refillAmount, notificationId))
        }

        postIfPermitted(context, TAG, notificationId, builder.build())
        updateStockSummary()
    }

    suspend fun postExpiration(medicine: Medicine, reminder: Reminder, expirationEpochDay: Long) {
        accentArgb = notificationAccentArgb(settingsRepository)
        val notificationId = -(reminder.id.toInt())
        val name = maskedMedicineName(medicine.name, settingsRepository.hideMedicineNames.first())
        val expirationDate = LocalDate.ofEpochDay(expirationEpochDay)
        val builder = NotificationCompat.Builder(context, ReminderNotificationChannels.channelIdFor(medicine.notificationPriority))
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setColor(accentArgb)
            .setContentTitle(context.getString(R.string.alert_expiring_title, name))
            .setContentText(context.getString(R.string.alert_expiring_text, expirationDate.toString()))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent(context, notificationId))
            .setGroup(STOCK_GROUP_KEY)
            .setDeleteIntent(dismissedPendingIntent(notificationId))

        postIfPermitted(context, TAG, notificationId, builder.build())
        updateStockSummary()
    }

    /**
     * One line over the stock alerts (§9 #27; DS-05, Dosage's "You have N
     * treatments low in stock"): the per-medicine cards stay -- they carry
     * the Refill action and are the better data (Appendix F) -- and from two
     * showing a silent summary names them all. Rebuilt after every post,
     * every refill and every swipe, gone below two, so the count is never
     * stale for longer than the next event. The R64 `updateGroupSummary`
     * shape, on the negative ids the alerts use.
     */
    fun updateStockSummary() {
        val nm = androidx.core.app.NotificationManagerCompat.from(context)
        val showing = nm.activeNotifications
            .filter { it.id < 0 && it.id != STOCK_GROUP_SUMMARY_ID && it.notification.group == STOCK_GROUP_KEY }
            .sortedBy { it.id }
        if (showing.size < 2) {
            nm.cancel(STOCK_GROUP_SUMMARY_ID)
            return
        }
        val style = NotificationCompat.InboxStyle()
        showing.forEach { style.addLine(it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE) ?: "") }
        val summary = NotificationCompat.Builder(context, ReminderNotificationChannels.CHANNEL_STANDARD)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setColor(accentArgb)
            .setContentTitle(context.resources.getQuantityString(R.plurals.alert_stock_summary, showing.size, showing.size))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(STOCK_GROUP_KEY)
            .setGroupSummary(true)
            .setSilent(true)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent(context, STOCK_GROUP_SUMMARY_ID))
            .build()
        postIfPermitted(context, TAG, STOCK_GROUP_SUMMARY_ID, summary)
    }

    private fun dismissedPendingIntent(notificationId: Int): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java).apply { action = ACTION_ALERT_DISMISSED }
        // Its own request code space: the refill intent uses notificationId itself.
        return PendingIntent.getBroadcast(context, notificationId * 10 + 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun refillPendingIntent(medicineId: Long, refillAmount: Double, notificationId: Int): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java).apply {
            action = ACTION_REFILL
            putExtra(EXTRA_MEDICINE_ID, medicineId)
            putExtra(EXTRA_REFILL_AMOUNT, refillAmount)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
