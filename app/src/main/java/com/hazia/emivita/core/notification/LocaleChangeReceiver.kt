package com.hazia.emivita.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-register the channels when the system language changes (§9 #25,
 * Appendix F HM-F3, HM-35), because `createAll` runs once per process and the
 * channel names would otherwise stay in the old language in Android's
 * notification settings until the next process start. Names and descriptions
 * are the two channel properties the platform lets an app update after
 * creation, which is exactly what this touches.
 */
class LocaleChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
        ReminderNotificationChannels.createAll(context)
    }
}
