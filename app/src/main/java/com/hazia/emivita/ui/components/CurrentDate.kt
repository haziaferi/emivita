package com.hazia.emivita.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A second past midnight, so the re-read can't land on the boundary itself
 *  and compute the day it just left. */
private const val MIDNIGHT_OVERSHOOT_MILLIS = 1_000L

/** The longest wait between two reads of the wall clock (§7 R140). */
private const val RECHECK_MILLIS = 60_000L

/**
 * Today's date, recomputed when it actually changes: `remember { LocalDate.now() }`
 * never updates, so a screen left open across midnight would keep showing
 * yesterday's doses, and nothing else recomposes because the observed data is
 * queried for the stale range. Sleeps until just after the next local midnight,
 * so a zone change or DST shift lands on the real boundary, but never more than
 * [RECHECK_MILLIS] at a time: `delay` runs on the monotonic clock, which stops
 * in deep sleep, so one long wait could end well after midnight (§7 R140).
 */
@Composable
fun rememberCurrentDate(zone: ZoneId, nowEpochMillis: () -> Long = System::currentTimeMillis): LocalDate {
    fun now() = Instant.ofEpochMilli(nowEpochMillis()).atZone(zone)
    return produceState(initialValue = now().toLocalDate(), zone) {
        while (true) {
            val now = now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            val untilMidnight = Duration.between(now, nextMidnight).toMillis()
            delay(minOf(untilMidnight.coerceAtLeast(0L) + MIDNIGHT_OVERSHOOT_MILLIS, RECHECK_MILLIS))
            value = now().toLocalDate()
        }
    }.value
}

/** The clock, re-read each minute: what History's figures read the midnight
 *  rule at (§7 R121), so a dose left unanswered past its day's midnight counts
 *  as missed without leaving the screen. */
@Composable
fun rememberNowEpochMillis(periodMillis: Long = 60_000L): Long = rememberNowState(periodMillis).value

/** The same clock as a state, for a `derivedStateOf` that recomposes its readers only when what it derives changes. */
@Composable
fun rememberNowState(periodMillis: Long = 60_000L, nowEpochMillis: () -> Long = System::currentTimeMillis): State<Long> =
    produceState(initialValue = nowEpochMillis(), periodMillis) {
        while (true) {
            delay(periodMillis - nowEpochMillis() % periodMillis)
            value = nowEpochMillis()
        }
    }
