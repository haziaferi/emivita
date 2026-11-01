package com.hazia.emivita.core.scheduling

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Says so when an armed alarm did not fire (§9 #29, Appendix F DP-04, the
 * finding DP-F2), because `rescheduleOnLaunch` repairs dropped alarms silently
 * and the person would never learn their OEM is killing the app. [AlarmScheduler]
 * records every armed regular slot, [recordFired] stamps every fire, and on
 * launch, *before* the repair, [checkOnLaunch] posts one notice, kept until
 * dismissed, when the earliest planned instant is more than a minute past, no
 * alarm has fired since it, and the phone has been up for five minutes (Daily
 * Pill's guards against AlarmManager's slack and a receiver not yet run after
 * a boot).
 *
 * SharedPreferences, not DataStore: [AlarmScheduler.schedule] is not a
 * suspend function and runs on the alarm path.
 */
object AlarmWatchdog {
    private const val PREFS = "alarm_watchdog"
    private const val KEY_PLANNED = "planned"        // "reminderId:epochMillis" set
    private const val KEY_LAST_FIRED = "last_fired"
    private const val KEY_NOTICE = "missed_notice"
    const val SLACK_MILLIS = 60_000L
    const val MIN_UPTIME_MILLIS = 5L * 60_000L

    private val _notice = MutableStateFlow<Long?>(null)
    /** The planned instant of the missed alarm, null when there is none to show. */
    val notice: StateFlow<Long?> get() = _notice

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordPlanned(context: Context, reminderId: Long, triggerAtEpochMillis: Long) {
        val set = plannedSet(context).filterNot { it.startsWith("$reminderId:") }.toMutableSet()
        set += "$reminderId:$triggerAtEpochMillis"
        prefs(context).edit { putStringSet(KEY_PLANNED, set) }
    }

    fun recordCancelled(context: Context, reminderId: Long) {
        prefs(context).edit { putStringSet(KEY_PLANNED, plannedSet(context).filterNot { it.startsWith("$reminderId:") }.toSet()) }
    }

    fun recordFired(context: Context, nowEpochMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_LAST_FIRED, nowEpochMillis) }
    }

    fun earliestPlanned(context: Context): Long? = plannedSlots(context).values.minOrNull()

    /** Each reminder's armed regular slot, by reminder id: one the OS is still holding is past and not yet fired. */
    fun plannedSlots(context: Context): Map<Long, Long> =
        plannedSet(context).mapNotNull { entry ->
            val reminderId = entry.substringBefore(':').toLongOrNull()
            val at = entry.substringAfter(':').toLongOrNull()
            if (reminderId != null && at != null) reminderId to at else null
        }.toMap()

    /** Runs before the launch reschedule repairs anything. Returns the missed
     *  instant when there is one, and remembers it for the Today card. */
    fun checkOnLaunch(context: Context, nowEpochMillis: Long = System.currentTimeMillis(), uptimeMillis: Long = SystemClock.elapsedRealtime()): Long? {
        val stored = prefs(context).getLong(KEY_NOTICE, -1L).takeIf { it > 0 }
        val missed = verdict(earliestPlanned(context), prefs(context).getLong(KEY_LAST_FIRED, -1L).takeIf { it > 0 }, nowEpochMillis, uptimeMillis)
        if (missed != null) prefs(context).edit { putLong(KEY_NOTICE, missed) }
        _notice.value = missed ?: stored
        return missed
    }

    fun loadNotice(context: Context) {
        _notice.value = prefs(context).getLong(KEY_NOTICE, -1L).takeIf { it > 0 }
    }

    fun dismissNotice(context: Context) {
        prefs(context).edit { remove(KEY_NOTICE) }
        _notice.value = null
    }

    /** The rule, pure. */
    fun verdict(earliestPlanned: Long?, lastFired: Long?, nowEpochMillis: Long, uptimeMillis: Long): Long? {
        if (earliestPlanned == null) return null
        if (uptimeMillis < MIN_UPTIME_MILLIS) return null
        if (earliestPlanned >= nowEpochMillis - SLACK_MILLIS) return null
        if (lastFired != null && lastFired >= earliestPlanned) return null
        return earliestPlanned
    }

    private fun plannedSet(context: Context): Set<String> = prefs(context).getStringSet(KEY_PLANNED, emptySet()).orEmpty()
}
