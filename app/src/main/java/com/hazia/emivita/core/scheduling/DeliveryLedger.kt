package com.hazia.emivita.core.scheduling

import android.content.Context
import androidx.core.content.edit

/**
 * When each reminder's latest dose actually reached the person (§7 R83 (c)).
 * A follow-up counts its delay from that moment, not from the slot: the
 * owner's rule is that a parent delayed by the phone rings as soon as it can,
 * and its follow-up comes the set time after that.
 *
 * One entry per reminder, and the first delivery of a dose wins, so a nag or
 * a re-raise of the same dose cannot push its follow-up later.
 */
object DeliveryLedger {
    private const val PREFS = "delivery_ledger"

    /** A delivery this close to its slot counts as on time; the slot stays the anchor. */
    const val LATE_MILLIS = 60_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordFirst(context: Context, reminderId: Long, doseEventId: Long, deliveredAtEpochMillis: Long) {
        if (deliveredAt(context, reminderId, doseEventId) != null) return
        prefs(context).edit { putString(reminderId.toString(), "$doseEventId:$deliveredAtEpochMillis") }
    }

    fun deliveredAt(context: Context, reminderId: Long, doseEventId: Long): Long? {
        val parts = prefs(context).getString(reminderId.toString(), null)?.split(':') ?: return null
        if (parts.size != 2 || parts[0].toLongOrNull() != doseEventId) return null
        return parts[1].toLongOrNull()
    }
}
