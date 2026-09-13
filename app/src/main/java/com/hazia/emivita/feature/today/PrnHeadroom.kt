package com.hazia.emivita.feature.today

/**
 * Where an as-needed medicine stands against its ceiling (§9 #26; Appendix F
 * PT-01, Pill Time's "max N in H hours"): how many were taken in the window
 * ending at the instant being logged, the ceiling itself, and -- once at or
 * over it -- when the oldest counted dose ages out and headroom returns.
 * Pure, so the rule is tested without a database.
 */
internal data class PrnHeadroom(
    val taken: Int,
    val max: Int,
    val headroomReturnsAtEpochMillis: Long?,
) {
    val atCeiling: Boolean get() = taken >= max
}

/** [takenAtEpochMillis] are the Taken instants inside the window, any order. */
internal fun prnHeadroom(takenAtEpochMillis: List<Long>, max: Int, windowMillis: Long): PrnHeadroom {
    val sorted = takenAtEpochMillis.sorted()
    val taken = sorted.size
    // Room for one more opens when enough of the oldest have aged out that
    // taken - aged < max: that is the (taken - max + 1)-th oldest leaving.
    val returnsAt = if (taken >= max && max > 0) sorted[taken - max] + windowMillis else null
    return PrnHeadroom(taken = taken, max = max, headroomReturnsAtEpochMillis = returnsAt)
}
