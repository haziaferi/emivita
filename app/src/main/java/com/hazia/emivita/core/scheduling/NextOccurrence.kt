package com.hazia.emivita.core.scheduling

/**
 * The next instant a [com.hazia.emivita.core.data.entity.Reminder] should
 * fire, plus the dose amount that instant should materialize with (doses can
 * change over time, so this is always freshly read from the reminder's
 * current config, not cached from a previous computation).
 *
 * A `null` [NextOccurrence] from a calculator means "no more occurrences will
 * ever be computed for this input" (e.g. a `SpecificDaysOfWeek` pattern with
 * no days selected, or a misconfigured window). It does NOT cover "this
 * reminder type isn't scheduled yet" -- see the explicit skip-list in
 * [SchedulingEngine] for that distinction.
 */
data class NextOccurrence(
    val epochMillis: Long,
    val doseAmount: Double,
)
