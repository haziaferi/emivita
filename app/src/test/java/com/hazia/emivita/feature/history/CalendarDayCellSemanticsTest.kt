package com.hazia.emivita.feature.history

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import com.hazia.emivita.core.data.entity.DoseStatus
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The calendar's day cell is the one surface in the app where state is
 * carried by colour alone -- three dots, no text (spec §9 #19 Stage B).
 * R55 gave the adherence chart a texture channel for exactly this reason
 * and the calendar never got its equivalent. A screen reader was told the
 * day number and nothing else.
 *
 * Fixed date: 2026-06-09 is a Tuesday, so the description's weekday and month
 * can be asserted verbatim. Default locale in the test JVM is what
 * `Locale.current` resolves to under Robolectric; the assertions use the
 * English forms and would need the same treatment as `EmergencyCardExporterTest`
 * if the suite ever ran under another default.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarDayCellSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val tuesday = LocalDate.of(2026, 6, 9)
    private fun day(position: DayPosition = DayPosition.MonthDate) = CalendarDay(tuesday, position)
    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun aDayWithDosesNamesTheDateAndCountsEachStatus() {
        compose.setContent {
            CalendarDayCell(
                day(), today = tuesday,
                statuses = listOf(DoseStatus.TAKEN, DoseStatus.TAKEN, DoseStatus.PENDING),
                selected = false, onClick = {},
            )
        }
        compose.onNode(hasContentDescription("Tuesday 9 June", substring = true))
            .assert(hasContentDescription("2 Taken", substring = true))
            // PENDING reads as Missed, the same relabel the list and the chart use.
            .assert(hasContentDescription("1 Missed", substring = true))
            .assert(hasRole(Role.Button))
            .assertHasClickAction()
            .assertIsNotSelected()
    }

    @Test
    fun aDayWithNothingRecordedSaysSo() {
        // "no doses" is information; silence would be ambiguous between "none"
        // and "the cell is empty for some other reason".
        compose.setContent { CalendarDayCell(day(), today = tuesday, statuses = emptyList(), selected = true, onClick = {}) }
        compose.onNode(hasContentDescription("no doses", substring = true)).assertIsSelected()
    }

    @Test
    fun anOutOfMonthDayIsDescribedButNotActionable() {
        // The cell is dimmed and not clickable for a sighted user; a screen
        // reader should get the same: the date, no button, no selected state.
        compose.setContent { CalendarDayCell(day(DayPosition.OutDate), today = tuesday, statuses = emptyList(), selected = false, onClick = {}) }
        compose.onNode(hasContentDescription("Tuesday 9 June", substring = true)).assertHasNoClickAction()
    }
}
