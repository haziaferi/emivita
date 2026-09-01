package com.hazia.emivita.core.format

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
fun formatDecimal(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/**
 * [formatDecimal] plus the medicine's own measurement label
 * ([com.hazia.emivita.core.data.entity.Medicine.doseUnit], e.g. "1 capsules"),
 * falling back to generic "dose"/"doses" wording for a medicine that has none
 * set -- so a medicine with nothing configured reads exactly as it did before
 * that field existed.
 */
fun formatDoseWithUnit(dose: Double, doseUnit: String?): String {
    val suffix = doseUnit?.takeIf { it.isNotBlank() } ?: if (dose == 1.0) "dose" else "doses"
    return "${formatDecimal(dose)} $suffix"
}
