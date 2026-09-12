package com.hazia.emivita.feature.history

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The adherence chart draws to a canvas; a screen reader gets nothing from
 * the pixels (spec §9 #19 Stage B). The chart host carries a description
 * that says what a sighted reader takes from it at a glance: the totals over
 * the window. Per-day detail is what the Table view is for.
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
            DayAdherenceCounts(start, taken = 2, skipped = 0, missed = 1),
            DayAdherenceCounts(start.plusDays(1), taken = 1, skipped = 1, missed = 0),
            DayAdherenceCounts(start.plusDays(2), taken = 3, skipped = 0, missed = 0),
        )
        compose.setContent { AdherenceChart(counts) }
        // Sums: 6 taken, 1 skipped, 1 missed, over 3 days -- the same plural
        // the History summary card uses, prefixed so the reader knows it is
        // describing the chart and not a second summary.
        compose.onNode(hasContentDescription("Adherence chart.", substring = true))
            .assertExists()
        compose.onNode(hasContentDescription("6 taken, 1 skipped, 1 missed, last 3 days", substring = true))
            .assertExists()
    }
}
