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
 * The calendar's day cell carries state by colour alone, three dots and no
 * text, so its screen-reader description must say what they mean (spec §9
 * #19 Stage B).
 *
 * Fixed date: 2026-06-09 is a Tuesday, so weekday and month are asserted
 * verbatim in English, which would need `EmergencyCardExporterTest`'s
 * treatment if the test JVM's default locale ever changed.
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
            // PENDING reads Due, as on the list and Today (§7 R121), never Missed.
            .assert(hasContentDescription("2 Taken, 1 Due", substring = true))
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
