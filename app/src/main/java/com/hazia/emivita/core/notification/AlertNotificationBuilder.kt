package com.hazia.emivita.core.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.format.formatDecimal
import com.hazia.emivita.core.scheduling.AlertActionReceiver
import java.time.LocalDate

const val ACTION_REFILL = "com.hazia.emivita.action.REFILL"
const val EXTRA_MEDICINE_ID = "com.hazia.emivita.extra.MEDICINE_ID"
const val EXTRA_REFILL_AMOUNT = "com.hazia.emivita.extra.REFILL_AMOUNT"
const val EXTRA_NOTIFICATION_ID = "com.hazia.emivita.extra.NOTIFICATION_ID"

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
class AlertNotificationBuilder(private val context: Context) {

    fun postLowStock(medicine: Medicine, reminder: Reminder) {
        val notificationId = -(reminder.id.toInt())
        val unit = medicine.stockInfo.unit?.let { " $it" } ?: ""
        val builder = NotificationCompat.Builder(context, ReminderNotificationChannels.channelIdFor(medicine.notificationPriority))
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle("${medicine.name} is running low")
            .setContentText("${formatDecimal(medicine.stockInfo.quantity)}$unit left")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)

        // One-tap restock (spec §3/§9 #2/#8) -- only offered when a refill
        // amount is actually configured; otherwise there's nothing to log
        // without asking the user a question a notification action can't ask.
        val refillAmount = medicine.stockInfo.refillQuantity
        if (refillAmount != null && refillAmount > 0.0) {
            builder.addAction(0, "Refill", refillPendingIntent(medicine.id, refillAmount, notificationId))
        }

        postIfPermitted(context, TAG, notificationId, builder.build())
    }

    fun postExpiration(medicine: Medicine, reminder: Reminder, expirationEpochDay: Long) {
        val notificationId = -(reminder.id.toInt())
        val expirationDate = LocalDate.ofEpochDay(expirationEpochDay)
        val builder = NotificationCompat.Builder(context, ReminderNotificationChannels.channelIdFor(medicine.notificationPriority))
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle("${medicine.name} is expiring")
            .setContentText("Expires $expirationDate")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)

        postIfPermitted(context, TAG, notificationId, builder.build())
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
