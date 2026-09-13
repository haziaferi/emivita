package com.hazia.emivita.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-register the channels when the system language changes (§9 #25;
 * Appendix F HM-F3, HM-35). `createAll` runs once per process, so after a
 * language change the three channel names stayed in the old language in
 * Android's own notification settings until the next process start --
 * invisible with one locale, a Stage C defect with two. Names and
 * descriptions are the two channel properties the platform lets an app
 * update after creation, which is exactly what this touches.
 */
class LocaleChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
        ReminderNotificationChannels.createAll(context)
    }
}
