package com.hazia.emivita.feature.today

import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.entity.Medicine

/** Today's two standing sections beneath the timeline (§9 #26, §7 R114). */
internal data class StandingSections(val asNeeded: List<Medicine>, val noReminder: List<Medicine>)

/**
 * Which medicines with no dosing reminder switched on are as-needed, and
 * which simply have no reminder yet (§7 R116, superseding R114's limit rule).
 * The medicine's own *Al bisogno* switch decides, never its as-needed limit;
 * one whose dosing reminders are all paused (Pause all, §9 #24) keeps the as-needed row it always had, since
 * it has a schedule and adding another is not what it needs. Alert
 * reminders never schedule a dose, so they count as no reminder.
 */
internal fun standingSections(medicines: List<MedicineWithReminders>): StandingSections {
    val asNeeded = mutableListOf<Medicine>()
    val noReminder = mutableListOf<Medicine>()
    for (m in medicines.sortedBy { it.medicine.sortOrder }) {
        val dosing = m.reminders.filter { it.type != ReminderType.LOW_STOCK_ALERT && it.type != ReminderType.EXPIRATION_ALERT }
        when {
            dosing.any { it.enabled } -> Unit // on the timeline
            dosing.isNotEmpty() -> asNeeded += m.medicine
            m.medicine.asNeeded -> asNeeded += m.medicine
            else -> noReminder += m.medicine
        }
    }
    return StandingSections(asNeeded, noReminder)
}
