package com.hazia.emivita.core.notification

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
