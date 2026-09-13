package com.hazia.emivita.core.notification

import android.app.PendingIntent
import android.content.Intent
import com.hazia.emivita.MainActivity

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Posts [notification] only if POST_NOTIFICATIONS is actually held.
 *
 * The permission is a runtime grant from API 33 on, and
 * [com.hazia.emivita.navigation.EmivitaApp] asks for it exactly once -- so a
 * user who declined has a permanently un-granted app, and
 * `NotificationManagerCompat.notify` would throw rather than no-op. Shared by
 * both notification builders, which had identical copies of this guard.
 *
 * [tag] is the caller's log tag, so a dropped notification is attributable to
 * the builder that tried to post it.
 */
internal fun postIfPermitted(context: Context, tag: String, notificationId: Int, notification: Notification) {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) {
        Log.w(tag, "POST_NOTIFICATIONS not granted -- notification $notificationId not shown.")
        return
    }
    NotificationManagerCompat.from(context).notify(notificationId, notification)
}

/** A content intent that brings the app to the front -- Today, which is where
 *  a reminder or an alert is acted on. `CLEAR_TOP or SINGLE_TOP` re-uses the
 *  running MainActivity rather than stacking a second one over the alarm
 *  screen or the settings page. [requestCode] keeps one PendingIntent per
 *  notification so `FLAG_UPDATE_CURRENT` on one cannot rewrite another's. */
internal fun openAppPendingIntent(context: android.content.Context, requestCode: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
