package com.hazia.emivita.core.scheduling

import android.content.Context
import androidx.core.content.edit

/**
 * The snoozes still running, kept where a reboot cannot erase them (§7 R83 (e)).
 * AlarmManager forgets every alarm at a reboot and a PendingIntent outlives
 * its own firing, so neither can say whether a dose is snoozed. Without this
 * the launch re-post rang a snoozed dose at once, and a reboot lost the snooze.
 *
 * SharedPreferences, like [AlarmWatchdog]: the snooze path is not suspending.
 */
object SnoozeLedger {
    private const val PREFS = "snooze_ledger"

    data class Entry(val doseEventId: Long, val reminderId: Long, val originalScheduledEpochMillis: Long, val fireAtEpochMillis: Long)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, entry: Entry) {
        prefs(context).edit {
            putString(entry.doseEventId.toString(), "${entry.reminderId}:${entry.originalScheduledEpochMillis}:${entry.fireAtEpochMillis}")
        }
    }

    fun forget(context: Context, doseEventId: Long) {
        prefs(context).edit { remove(doseEventId.toString()) }
    }

    /** The instant the snooze of [doseEventId] ends, or null when none is running at [nowEpochMillis]. */
    fun snoozedUntil(context: Context, doseEventId: Long, nowEpochMillis: Long = System.currentTimeMillis()): Long? =
        entry(doseEventId, prefs(context).getString(doseEventId.toString(), null))
            ?.fireAtEpochMillis?.takeIf { it > nowEpochMillis }

    fun all(context: Context): List<Entry> =
        prefs(context).all.mapNotNull { (key, value) -> key.toLongOrNull()?.let { entry(it, value as? String) } }

    private fun entry(doseEventId: Long, raw: String?): Entry? {
        val parts = raw?.split(':')?.mapNotNull { it.toLongOrNull() } ?: return null
        if (parts.size != 3) return null
        return Entry(doseEventId, parts[0], parts[1], parts[2])
    }
}
