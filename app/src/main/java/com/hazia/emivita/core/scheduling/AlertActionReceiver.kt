package com.hazia.emivita.core.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.core.notification.ACTION_REFILL
import com.hazia.emivita.core.notification.ACTION_ALERT_DISMISSED
import com.hazia.emivita.core.notification.EXTRA_MEDICINE_ID
import com.hazia.emivita.core.notification.EXTRA_NOTIFICATION_ID
import com.hazia.emivita.core.notification.EXTRA_REFILL_AMOUNT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AlertActionReceiver"

/**
 * Handles the Refill action posted from a LowStockAlert notification (see
 * AlertNotificationBuilder). Separate from [ReminderActionReceiver] because
 * this action is keyed by medicineId, not a doseEventId -- there's no dose
 * event involved in restocking. Not exported -- only reachable via this
 * app's own notification action PendingIntents.
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_ALERT_DISMISSED) {
            // A swiped stock alert (§9 #27): the summary's count follows it.
            (context.applicationContext as? EmivitaApplication)?.container?.alertNotificationBuilder?.updateStockSummary()
            return
        }
        if (intent.action != ACTION_REFILL) {
            Log.w(TAG, "Received an unrecognized action: ${intent.action} -- ignoring.")
            return
        }
        val medicineId = intent.getLongExtra(EXTRA_MEDICINE_ID, -1L)
        val amount = intent.getDoubleExtra(EXTRA_REFILL_AMOUNT, -1.0)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (medicineId < 0 || amount <= 0.0) {
            Log.w(TAG, "Received a refill action with an invalid medicineId/amount -- ignoring.")
            return
        }

        val container = (context.applicationContext as? EmivitaApplication)?.container
        if (container == null) {
            Log.e(TAG, "applicationContext is not an EmivitaApplication -- dropping refill action for medicine $medicineId.")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                container.doseEventRepository.logRefill(medicineId, amount, System.currentTimeMillis())
                NotificationManagerCompat.from(context).cancel(notificationId)
                container.alertNotificationBuilder.updateStockSummary()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed handling refill for medicine $medicineId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
