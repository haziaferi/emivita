package com.hazia.emivita.feature.history

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The adherence chart draws to a canvas, so its host carries a description of
 * what a sighted reader takes from it at a glance, the totals over the window,
 * and per-day detail is the Table view's job (spec §9 #19 Stage B). Since
 * §7 R121 it has two series, taken and missed, skipped folded into missed.
 *
 * Robolectric draws to a shadow canvas, so the chart's bars are not asserted
 * -- only the semantics the host exposes (§7 R47's own rule for UI tests).
 */
@RunWith(RobolectricTestRunner::class)
class AdherenceChartSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theChartDescribesItsTotalsToAScreenReader() {
        val start = LocalDate.of(2026, 6, 1)
        val counts = listOf(
            DayAdherenceCounts(start, taken = 2, missed = 1),
            DayAdherenceCounts(start.plusDays(1), taken = 1, missed = 1),
            DayAdherenceCounts(start.plusDays(2), taken = 3, missed = 0),
        )
        compose.setContent { AdherenceChart(counts) }
        // Sums: 6 taken, 2 missed, over 3 days -- the same plural the History
        // summary card uses, prefixed so the reader knows it is describing the
        // chart and not a second summary.
        compose.onNode(hasContentDescription("Adherence chart.", substring = true))
            .assertExists()
        compose.onNode(hasContentDescription("Adherence chart. 6 taken, 2 missed, last 3 days"))
            .assertExists()
    }

    @Test
    fun theLegendAndTheStackOrderNameTakenAndMissedOnly() {
        compose.setContent { AdherenceChart(listOf(DayAdherenceCounts(LocalDate.of(2026, 6, 1), taken = 1, missed = 1))) }
        compose.onNodeWithText("Taken").assertExists()
        compose.onNodeWithText("Missed").assertExists()
        compose.onNodeWithText("Skipped").assertDoesNotExist()
        compose.onNodeWithText("Each bar stacks taken at the bottom, then missed.").assertExists()
    }

    @Test
    fun aDaysBarFoldsSkippedIntoMissedAndLeavesAnUnansweredDoseOutUntilMidnight() {
        val zone = ZoneId.of("Europe/Rome")
        fun at(iso: String) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
        fun row(status: DoseStatus, scheduled: String, reminderId: Long? = 1L) = DoseEventWithMedicine(
            DoseEvent(reminderId = reminderId, medicineId = 1L, scheduledEpochMillis = at(scheduled), doseAmount = 1.0, status = status, createdAtEpochMillis = 0L),
            medicine = null,
        )
        val today = LocalDate.of(2026, 9, 15)
        val events = listOf(
            row(DoseStatus.TAKEN, "2026-09-14T08:00"),
            row(DoseStatus.SKIPPED, "2026-09-14T12:00"),
            row(DoseStatus.PENDING, "2026-09-14T20:00"),
            row(DoseStatus.PENDING, "2026-09-15T08:00"),
            row(DoseStatus.TAKEN, "2026-09-15T07:00", reminderId = null),
        )
        val days = dailyAdherenceCounts(events, zone, today, at("2026-09-15T09:00")).associateBy { it.date }
        assertEquals("yesterday: the skip and the dose left past midnight are missed", DayAdherenceCounts(today.minusDays(1), taken = 1, missed = 2), days[today.minusDays(1)])
        assertEquals("today: nothing counted yet, an as-needed dose never", DayAdherenceCounts(today, taken = 0, missed = 0), days[today])
    }
}
