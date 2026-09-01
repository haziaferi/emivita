package com.hazia.emivita.core.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Same-package constants so ReminderAlarmReceiver doesn't need to guess the
 *  extra keys this class used to build its PendingIntent. */
const val EXTRA_REMINDER_ID = "com.hazia.emivita.extra.REMINDER_ID"
const val EXTRA_TRIGGER_AT_EPOCH_MILLIS = "com.hazia.emivita.extra.TRIGGER_AT_EPOCH_MILLIS"
// Nagging (spec §3/§4, repeatOnUnanswered) -- distinguishes a nag re-fire
// from a regular/snooze fire so the receiver can skip the regular chain's
// reschedule/cascade side effects (see ReminderAlarmReceiver.handleNagFire's
// doc comment for why those two paths can't share the same handling).
const val EXTRA_IS_NAG = "com.hazia.emivita.extra.IS_NAG"

// The fresh-install default only. SettingsRepository.snoozeDurationMinutes
// persists the user's own choice (Settings > Alarms & Snooze); every caller
// reads that and falls back to this, so it isn't a second magic number.
const val DEFAULT_SNOOZE_MINUTES = 10L

private const val TAG = "AlarmScheduler"

/**
 * Thin wrapper around [AlarmManager] for reminder alarms. Three alarm "slots"
 * per reminder, each its own PendingIntent request code so they can't clobber
 * each other -- the same "each concern gets its own slot" idea medTimer's
 * own `AlarmProcessor` uses (slot 0 for the next-scheduled alarm, a separate
 * per-event slot for snooze/repeat/show), ported onto Emivita's per-reminder
 * request-code scheme rather than medTimer's per-event one, since Emivita
 * keys everything else here by reminderId already:
 *  - the regular recurring slot (`reminderId.toInt()`), managed by
 *    [schedule]/[cancel] via [RealSchedulingCoordinator];
 *  - a snooze slot (`-reminderId.toInt()`), a one-off re-notification for an
 *    already-materialized dose event that doesn't touch the recurring chain;
 *  - a nag slot (`1_500_000_000 + reminderId.toInt()`, spec §3/§4
 *    `repeatOnUnanswered`), a re-fire for a still-unanswered dose that
 *    doesn't touch the recurring chain either -- see [scheduleNag].
 *
 * Handles the API 31+ exact-alarm permission split (audit item, gating
 * exact vs. inexact) -- below API 31 SCHEDULE_EXACT_ALARM is a normal,
 * install-time-granted permission and [AlarmManager.canScheduleExactAlarms]
 * doesn't exist yet, so exact scheduling is always assumed available there.
 */
class AlarmScheduler(private val context: Context) {
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Whether exact alarms can be scheduled right now. The manifest declares
     *  `USE_EXACT_ALARM`, which is granted at install and makes this return
     *  true without any user interaction -- see AndroidManifest.xml for why
     *  that permission rather than a Settings prompt. This stays a real check
     *  rather than an assumed `true`: the [setAlarm] fallback below is what
     *  keeps reminders firing (late, but firing) if that ever stops holding. */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    /** Schedules (replacing any prior alarm for the same reminder) the next
     *  regular firing at [triggerAtEpochMillis]. */
    fun schedule(reminderId: Long, triggerAtEpochMillis: Long) {
        setAlarm(
            requestCode = regularRequestCode(reminderId),
            reminderId = reminderId,
            triggerAtEpochMillis = triggerAtEpochMillis,
            embeddedScheduledEpochMillis = triggerAtEpochMillis,
            isNag = false,
        )
    }

    /** One-off re-notification for a dose that's already materialized (and
     *  still pending) -- [originalScheduledEpochMillis] must be the dose
     *  event's original slot, not [fireAtEpochMillis], so the receiver looks
     *  up the same DoseEvent rather than trying to materialize a new one. */
    fun scheduleSnooze(reminderId: Long, originalScheduledEpochMillis: Long, fireAtEpochMillis: Long) {
        setAlarm(
            requestCode = snoozeRequestCode(reminderId),
            reminderId = reminderId,
            triggerAtEpochMillis = fireAtEpochMillis,
            embeddedScheduledEpochMillis = originalScheduledEpochMillis,
            isNag = false,
        )
    }

    /** Nag re-fire (spec §3/§4 `repeatOnUnanswered`) for a still-unanswered
     *  dose -- [originalScheduledEpochMillis] is the dose event's original
     *  slot, same convention [scheduleSnooze] uses, so the receiver looks up
     *  the same DoseEvent rather than trying to materialize a new one.
     *  [EXTRA_IS_NAG] is what tells [ReminderAlarmReceiver] to route this to
     *  [ReminderAlarmReceiver]'s nag-only handling instead of the regular
     *  fire path -- see that class's doc comment for why the two can't share
     *  handling (the regular chain's reschedule/cascade side effects must
     *  not re-fire on every nag). `FLAG_UPDATE_CURRENT` means a fresh call
     *  (e.g. the user snoozes while a nag is already pending) simply resets
     *  this same slot to the new time rather than leaving two nags pending. */
    fun scheduleNag(reminderId: Long, originalScheduledEpochMillis: Long, fireAtEpochMillis: Long) {
        setAlarm(
            requestCode = nagRequestCode(reminderId),
            reminderId = reminderId,
            triggerAtEpochMillis = fireAtEpochMillis,
            embeddedScheduledEpochMillis = originalScheduledEpochMillis,
            isNag = true,
        )
    }

    /** Cancels any pending *regular* alarm for [reminderId] (e.g. reminder
     *  disabled or deleted). A pending snooze, if any, is left alone --
     *  see ReminderActionReceiver's doc comment on why that's safe. */
    fun cancel(reminderId: Long) {
        alarmManager.cancel(buildPendingIntent(regularRequestCode(reminderId), reminderId, null, isNag = false))
    }

    /** Cancels any pending nag re-fire for [reminderId] -- called the moment
     *  a dose is genuinely answered (Taken/Skipped), spec §3/§4's "capped at
     *  maxRepeats" cap being reached is the *other* way nagging stops; this
     *  is the "answered before that" way. Not called on snooze -- snoozing
     *  isn't answering, so a nag mid-snooze-window is intended, not a bug. */
    fun cancelNag(reminderId: Long) {
        alarmManager.cancel(buildPendingIntent(nagRequestCode(reminderId), reminderId, null, isNag = true))
    }

    private fun setAlarm(
        requestCode: Int,
        reminderId: Long,
        triggerAtEpochMillis: Long,
        embeddedScheduledEpochMillis: Long,
        isNag: Boolean,
    ) {
        val pendingIntent = buildPendingIntent(requestCode, reminderId, embeddedScheduledEpochMillis, isNag)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, pendingIntent)
        } else {
            Log.w(TAG, "SCHEDULE_EXACT_ALARM not granted -- scheduling reminder $reminderId inexactly.")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, pendingIntent)
        }
    }

    private fun buildPendingIntent(requestCode: Int, reminderId: Long, scheduledEpochMillis: Long?, isNag: Boolean): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminderId)
            scheduledEpochMillis?.let { putExtra(EXTRA_TRIGGER_AT_EPOCH_MILLIS, it) }
            putExtra(EXTRA_IS_NAG, isNag)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        // reminderId.toInt(): Room's autoIncrement Long practically never
        // exceeds Int range for a personal, single-device app; if it somehow
        // did, two reminders would collide on the same alarm slot --
        // documented, not silent.
        private fun regularRequestCode(reminderId: Long): Int = reminderId.toInt()
        private fun snoozeRequestCode(reminderId: Long): Int = -(reminderId.toInt())

        // Large fixed offset, not a sign flip like snooze -- guarantees no
        // overlap with either regular or snooze codes for any realistic
        // personal-app reminderId range, same "practically never collides"
        // reasoning as above.
        private fun nagRequestCode(reminderId: Long): Int = 1_500_000_000 + reminderId.toInt()
    }
}
