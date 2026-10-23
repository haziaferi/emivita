package com.hazia.emivita.core.notification

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.scheduling.DeliveryLedger
import com.hazia.emivita.core.scheduling.SnoozeLedger

/**
 * Re-post the notifications of doses that fired, were never answered, and
 * whose notification is gone (§9 #25; Appendix E B-20, medTimer's
 * `AutostartService.restoreNotifications`), since a reboot or force-stop
 * clears the shade and `rescheduleAll()` only re-arms the *future* (§7 R61).
 *
 * Only doses from the last [LOOKBACK_MILLIS] (older ones already count as
 * missed) with no active notification are posted, so calling this on every
 * launch cannot re-chime a reminder that is still showing.
 */
class UnansweredDoseReposter(
    private val context: Context,
    private val doseEventDao: DoseEventDao,
    private val reminderDao: ReminderDao,
    private val medicineDao: MedicineDao,
    private val notificationBuilder: ReminderNotificationBuilder,
) {
    suspend fun repost(nowEpochMillis: Long = System.currentTimeMillis()): Int {
        val active = NotificationManagerCompat.from(context).activeNotifications.map { it.id }.toSet()
        var posted = 0
        for (event in doseEventDao.getPendingScheduledBetween(nowEpochMillis - LOOKBACK_MILLIS, nowEpochMillis)) {
            if (event.id.toInt() in active) continue
            // A snoozed dose is PENDING with no notification by design; its own
            // alarm brings it back (§7 R83 (e)).
            if (SnoozeLedger.snoozedUntil(context, event.id, nowEpochMillis) != null) continue
            val reminderId = event.reminderId ?: continue
            val reminder = reminderDao.getById(reminderId) ?: continue
            if (!reminder.enabled) continue
            val medicine = medicineDao.getById(event.medicineId ?: continue) ?: continue
            runCatching {
                notificationBuilder.buildAndPost(event, reminder, medicine); posted++
                DeliveryLedger.recordFirst(context, reminderId, event.id, nowEpochMillis)
            }
                .onFailure { Log.w(TAG, "Could not re-post dose ${event.id}", it) }
        }
        return posted
    }

    companion object {
        const val LOOKBACK_MILLIS = 24L * 60 * 60 * 1000
        private const val TAG = "UnansweredDoseReposter"
    }
}
