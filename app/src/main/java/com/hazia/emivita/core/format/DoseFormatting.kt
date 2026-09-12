package com.hazia.emivita.core.format

import androidx.annotation.StringRes
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.config.ReminderType
import android.content.res.Resources

/**
 * Drops a trailing ".0" so a whole number reads as "2" rather than "2.0",
 * while leaving genuine fractions alone.
 *
 * The single implementation behind every dose amount, stock quantity and
 * interval this app displays. It previously existed as seven separate copies
 * of the same expression -- formatDoseAmount, formatDose, formatNumber, two
 * different formatQuantity, the numeric half of formatHours, and one inlined
 * directly into a `remember {}` block.
 */
/**
 * The display label for a dose status, as a resource id (spec §9 #19).
 *
 * PENDING reads as "Missed": the alarm fired (or was projected in the past)
 * and nothing resolved it. That is a display-only relabel of a past-dated
 * PENDING row, not a fifth DoseStatus -- the column stays PENDING so a late
 * resolve() still works. It used to live in HistoryScreen with a second
 * private copy in HistoryExporter; one @StringRes function here serves the
 * History list, the table, and the PDF, and the CSV deliberately does not
 * use it (see [csvStatusLabel]).
 */
@StringRes
fun doseStatusLabelRes(status: DoseStatus): Int = when (status) {
    DoseStatus.PENDING -> R.string.status_missed
    DoseStatus.TAKEN -> R.string.status_taken
    DoseStatus.SKIPPED -> R.string.status_skipped
    DoseStatus.REFILLED -> R.string.status_refilled
}

/**
 * English, on purpose, and not a resource. The CSV export is data for a
 * spreadsheet, and a column value that changes with the phone language
 * breaks whatever reads it. The PDF is a document for a person and uses
 * [doseStatusLabelRes]; this is the one place the two are allowed to differ.
 */
fun csvStatusLabel(status: DoseStatus): String = when (status) {
    DoseStatus.PENDING -> "Missed"
    DoseStatus.TAKEN -> "Taken"
    DoseStatus.SKIPPED -> "Skipped"
    DoseStatus.REFILLED -> "Refilled"
}

/** Display name of a reminder type, as a resource id (spec §9 #19). Shared by
 *  the type picker, the medicine detail summary and the parent-reminder
 *  dropdown, which used to carry three String copies. */
@StringRes
fun ReminderType.labelRes(): Int = when (this) {
    ReminderType.FIXED_TIME -> R.string.reminder_fixed_time
    ReminderType.REPEATING_INTERVAL -> R.string.reminder_repeating_interval
    ReminderType.DAILY_WINDOW -> R.string.reminder_daily_window
    ReminderType.LOW_STOCK_ALERT -> R.string.reminder_low_stock_alert
    ReminderType.EXPIRATION_ALERT -> R.string.reminder_expiration_alert
    ReminderType.LINKED_FOLLOWING -> R.string.reminder_linked_following
}

fun formatDecimal(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/**
 * [formatDecimal] plus the medicine's own measurement label
 * ([com.hazia.emivita.core.data.entity.Medicine.doseUnit], e.g. "1 capsules"),
 * falling back to generic "dose"/"doses" wording for a medicine that has none
 * set -- so a medicine with nothing configured reads exactly as it did before
 * that field existed.
 */
// [resources] resolves the fallback unit. The old "dose"/"doses" default was
// the seventh string en-XA found on the device: a Today row read
// "»1 dose«" in plain English inside an otherwise accented sentence, and no
// scanner could have seen a word inside a pure formatter (spec §9 #19).
fun formatDoseWithUnit(dose: Double, doseUnit: String?, resources: Resources): String {
    val unit = doseUnit?.takeIf { it.isNotBlank() }
    if (unit != null) return "${formatDecimal(dose)} $unit"
    // Plural selection wants an integer; a fractional dose ("0.5") takes the
    // "other" form, which is what English and most rules give a non-integer.
    val quantity = if (dose == dose.toLong().toDouble()) dose.toInt() else 2
    return resources.getQuantityString(R.plurals.dose_default_unit, quantity, formatDecimal(dose))
}
