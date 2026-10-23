package com.hazia.emivita.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** A second past midnight, so the re-read can't land on the boundary itself
 *  and compute the day it just left. */
private const val MIDNIGHT_OVERSHOOT_MILLIS = 1_000L

/**
 * Today's date, recomputed when it actually changes: `remember { LocalDate.now() }`
 * never updates, so a screen left open across midnight would keep showing
 * yesterday's doses, and nothing else recomposes because the observed data is
 * queried for the stale range. Sleeps until just after the next local midnight
 * rather than ticking every 24 hours, so a zone change or DST shift lands on
 * the real boundary.
 */
@Composable
fun rememberCurrentDate(zone: ZoneId): LocalDate =
    produceState(initialValue = LocalDate.now(zone), zone) {
        while (true) {
            val now = ZonedDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            val untilMidnight = Duration.between(now, nextMidnight).toMillis()
            delay(untilMidnight.coerceAtLeast(0L) + MIDNIGHT_OVERSHOOT_MILLIS)
            value = LocalDate.now(zone)
        }
    }.value

/** The clock, re-read each minute: what History's figures read the midnight
 *  rule at (§7 R121), so a dose left unanswered past its day's midnight counts
 *  as missed without leaving the screen. */
@Composable
fun rememberNowEpochMillis(periodMillis: Long = 60_000L): Long =
    produceState(initialValue = System.currentTimeMillis(), periodMillis) {
        while (true) {
            delay(periodMillis - System.currentTimeMillis() % periodMillis)
            value = System.currentTimeMillis()
        }
    }.value
