package com.hazia.emivita.feature.medicines

import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import java.time.LocalDate

/**
 * How many medicines need a hand (§9 #27; Appendix F DS-05, Dosage's tab
 * badge): tracked and empty, tracked and at or below an enabled low-stock
 * threshold, or expired by the effective date. The same three predicates
 * the card tints on and the alerts fire on, so the number on the tab can
 * never disagree with the cards behind it. Pure; archived medicines are
 * already absent from the list this reads.
 */
fun stockAttentionCount(medicines: List<MedicineWithReminders>, today: LocalDate): Int =
    medicines.count { needsStockAttention(it, today) }

fun needsStockAttention(entry: MedicineWithReminders, today: LocalDate): Boolean {
    val stock = entry.medicine.stockInfo
    val tracked = stock.unit != null
    if (tracked && stock.quantity <= 0.0) return true
    if (stock.effectiveExpirationEpochDay?.let { it < today.toEpochDay() } == true) return true
    if (!tracked) return false
    return entry.reminders.any { r ->
        r.enabled && (runCatching { decodeReminderConfig(r.configJson) }.getOrNull() as? ReminderConfig.LowStockAlert)?.let { stock.quantity <= it.threshold } == true
    }
}
