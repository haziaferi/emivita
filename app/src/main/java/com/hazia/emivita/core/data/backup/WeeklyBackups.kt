package com.hazia.emivita.core.data.backup

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The weekly copy's rules (§7 R101, the owner's choices): one dated copy within
 * seven days of the last, beside the after-every-change file, and the four
 * newest kept. A mistake or a damaged database then reaches the current file
 * but not the weeks before it. Pure, so the JVM test pins every rule.
 */
internal object WeeklyBackups {
    const val KEEP = 4
    private const val PREFIX = "emivita_weekly_"
    val INTERVAL_MILLIS: Long = TimeUnit.DAYS.toMillis(7)
    private val NAME = Regex("""emivita_weekly_\d{4}-\d{2}-\d{2}\.zip""")

    /** None yet, seven days passed, or the clock moved back past the last one. */
    fun isDue(lastEpochMillis: Long?, nowEpochMillis: Long): Boolean =
        lastEpochMillis == null || nowEpochMillis - lastEpochMillis >= INTERVAL_MILLIS || nowEpochMillis < lastEpochMillis

    fun nextDue(lastEpochMillis: Long?, nowEpochMillis: Long): Long =
        if (lastEpochMillis == null) nowEpochMillis else maxOf(nowEpochMillis, lastEpochMillis + INTERVAL_MILLIS)

    fun fileName(nowEpochMillis: Long, zone: ZoneId): String =
        "$PREFIX${Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()}.zip"

    /** The weekly copies past the newest [KEEP]; nothing else in the folder is ever named. */
    fun toPrune(names: Collection<String>): List<String> =
        names.filter { NAME.matches(it) }.sortedDescending().drop(KEEP)
}
