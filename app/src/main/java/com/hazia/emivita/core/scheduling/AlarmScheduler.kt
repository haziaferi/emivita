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
/** A pre-alarm fire (§9 #25): post the silent "coming up" notification and
 *  nothing else -- no dose is materialised, nothing is rescheduled. */
const val EXTRA_IS_PRE_ALARM = "com.hazia.emivita.extra.IS_PRE_ALARM"

// The fresh-install default only. SettingsRepository.snoozeDurationMinutes
// persists the user's own choice (Settings > Alarms & Snooze); every caller
// reads that and falls back to this, so it isn't a second magic number.
const val DEFAULT_SNOOZE_MINUTES = 10L

private const val TAG = "AlarmScheduler"

/**
 * Thin wrapper around [AlarmManager] for reminder alarms. Three alarm "slots",
 * each its own PendingIntent request code so they can't clobber each other --
 * the same "each concern gets its own slot" idea medTimer's own
 * `AlarmProcessor` uses (slot 0 for the next-scheduled alarm, a separate
 * per-event slot for snooze/repeat/show):
 *  - the regular recurring slot, keyed by **reminder** (`reminderId.toInt()`),
 *    managed by [schedule]/[cancel] via [RealSchedulingCoordinator] -- one
 *    next firing per reminder is the whole point of it;
 *  - a snooze slot, keyed by **dose event** (`-doseEventId.toInt()`), a
 *    one-off re-notification for an already-materialized dose that doesn't
 *    touch the recurring chain;
 *  - a nag slot, keyed by **dose event** (`1_500_000_000 + doseEventId.toInt()`,
 *    spec §3/§4 `repeatOnUnanswered`), a re-fire for a still-unanswered dose
 *    that doesn't touch the recurring chain either -- see [scheduleNag].
 *
 * The last two used to be keyed by reminder as well (§7 R60). That was fine
 * while a reminder had one outstanding dose, and an ON_REMINDED interval
 * reminder reschedules the moment it fires, so it can have two: snoozing the
 * second silently cancelled the first's pending snooze, and arming the
 * second's nag overwrote the first's chain. medTimer keys these by event
 * (`PendingIntentFactory`); so does this now. A pre-R60 install may still
 * hold per-reminder snooze/nag alarms; they fire once into
 * [ReminderAlarmReceiver], which looks the dose up by (reminder, slot) and
 * ignores it unless it is still PENDING -- harmless, and gone after that.
 *
 * Handles the API 31+ exact-alarm permission split (audit item, gating
 * exact vs. inexact) -- below API 31 SCHEDULE_EXACT_ALARM is a normal,
 * install-time-granted permission and [AlarmManager.canScheduleExactAlarms]
 * doesn't exist yet, so exact scheduling is always assumed available there.
 */
class AlarmScheduler(private val context: Context) {
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Arm the *regular* slot with `setAlarmClock` instead of
     *  `setExactAndAllowWhileIdle` (§9 #25, Appendix F HM-21): the status bar
     *  shows the alarm icon, the lock screen the next reminder, and the OS
     *  exempts it from most deferral as a user-visible alarm. Kept in sync
     *  with the setting by AppContainer; a change re-arms everything. Snooze
     *  and nag slots stay exact alarms -- they are not "the next alarm". */
    @Volatile
    var useAlarmClock: Boolean = false

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
        if (useAlarmClock) {
            val pendingIntent = buildPendingIntent(regularRequestCode(reminderId), reminderId, triggerAtEpochMillis, isNag = false)
            val show = PendingIntent.getActivity(
                context, regularRequestCode(reminderId),
                Intent(context, com.hazia.emivita.MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtEpochMillis, show), pendingIntent)
            return
        }
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
    fun scheduleSnooze(reminderId: Long, doseEventId: Long, originalScheduledEpochMillis: Long, fireAtEpochMillis: Long) {
        setAlarm(
            requestCode = snoozeRequestCode(doseEventId),
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
    fun scheduleNag(reminderId: Long, doseEventId: Long, originalScheduledEpochMillis: Long, fireAtEpochMillis: Long) {
        setAlarm(
            requestCode = nagRequestCode(doseEventId),
            reminderId = reminderId,
            triggerAtEpochMillis = fireAtEpochMillis,
            embeddedScheduledEpochMillis = originalScheduledEpochMillis,
            isNag = true,
        )
    }

    /** The silent "coming up in N minutes" slot (§9 #25, HM-19), keyed by
     *  reminder like the regular one -- there is one next dose, so one
     *  pre-alarm. Re-armed and cancelled with the regular slot. */
    fun schedulePreAlarm(reminderId: Long, doseEpochMillis: Long, fireAtEpochMillis: Long) {
        val pendingIntent = buildPendingIntent(preAlarmRequestCode(reminderId), reminderId, doseEpochMillis, isNag = false, isPreAlarm = true)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtEpochMillis, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtEpochMillis, pendingIntent)
        }
    }

    /** Cancels any pending *regular* alarm for [reminderId] (e.g. reminder
     *  disabled or deleted), and its pre-alarm with it. A pending snooze, if
     *  any, is left alone -- see ReminderActionReceiver's doc comment. */
    fun cancel(reminderId: Long) {
        alarmManager.cancel(buildPendingIntent(regularRequestCode(reminderId), reminderId, null, isNag = false))
        alarmManager.cancel(buildPendingIntent(preAlarmRequestCode(reminderId), reminderId, null, isNag = false, isPreAlarm = true))
    }

    /** Cancels any pending nag re-fire for [doseEventId] -- called the moment
     *  a dose is genuinely answered (Taken/Skipped), spec §3/§4's "capped at
     *  maxRepeats" cap being reached is the *other* way nagging stops; this
     *  is the "answered before that" way. Not called on snooze -- snoozing
     *  isn't answering, so a nag mid-snooze-window is intended, not a bug.
     *  [reminderId] only shapes the intent so it `filterEquals` the armed one. */
    fun cancelNag(reminderId: Long, doseEventId: Long) {
        alarmManager.cancel(buildPendingIntent(nagRequestCode(doseEventId), reminderId, null, isNag = true))
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

    private fun buildPendingIntent(requestCode: Int, reminderId: Long, scheduledEpochMillis: Long?, isNag: Boolean, isPreAlarm: Boolean = false): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(EXTRA_REMINDER_ID, reminderId)
            scheduledEpochMillis?.let { putExtra(EXTRA_TRIGGER_AT_EPOCH_MILLIS, it) }
            putExtra(EXTRA_IS_NAG, isNag)
            putExtra(EXTRA_IS_PRE_ALARM, isPreAlarm)
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
        private fun snoozeRequestCode(doseEventId: Long): Int = -(doseEventId.toInt())

        // Large fixed offset, not a sign flip like snooze -- guarantees no
        // overlap with either regular or snooze codes for any realistic
        // personal-app id range, same "practically never collides" reasoning
        // as above. Dose-event ids grow faster than reminder ids (one per
        // firing), so the headroom is ~647 million events.
        private fun nagRequestCode(doseEventId: Long): Int = 1_500_000_000 + doseEventId.toInt()
        // Its own namespace below the snooze codes: -(1_000_000_000 + reminderId).
        private fun preAlarmRequestCode(reminderId: Long): Int = -(1_000_000_000 + reminderId.toInt())
    }
}
