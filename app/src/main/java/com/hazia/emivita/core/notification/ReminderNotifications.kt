package com.hazia.emivita.core.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.hazia.emivita.core.alarm.AlarmRingService

/** The one way a reminder's notification goes away (§7 R80): cancels it and
 *  stops the Alarm tier's ringing for that dose. Five call sites used to
 *  cancel directly; a foreground service's notification cannot be cancelled
 *  from outside the service, so all of them come through here. */
object ReminderNotifications {
    fun dismiss(context: Context, doseEventId: Long) {
        AlarmRingService.stop(context, doseEventId)
        NotificationManagerCompat.from(context).cancel(doseEventId.toInt())
    }
}
