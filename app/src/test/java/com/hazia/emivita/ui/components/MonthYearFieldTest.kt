package com.hazia.emivita.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Locale

/** The month/year expiry picker (spec §9 #27; HM-09): end-of-month
 *  arithmetic, the display rule, one radio node per option. */
@RunWith(RobolectricTestRunner::class)
class MonthYearFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a month stores its last day, leap February included`() {
        assertEquals(LocalDate.of(2027, 3, 31).toEpochDay(), endOfMonthEpochDay(2027, 3))
        assertEquals(LocalDate.of(2028, 2, 29).toEpochDay(), endOfMonthEpochDay(2028, 2))
        assertEquals(LocalDate.of(2027, 2, 28).toEpochDay(), endOfMonthEpochDay(2027, 2))
    }

    @Test
    fun `a month end shows as MM slash YYYY and any other day as a full date`() {
        assertTrue(isEndOfMonth(LocalDate.of(2027, 3, 31).toEpochDay()))
        assertFalse(isEndOfMonth(LocalDate.of(2027, 3, 30).toEpochDay()))
        assertEquals("03/2027", formatMonthYearOrDate(LocalDate.of(2027, 3, 31).toEpochDay(), Locale.US))
        assertEquals("Mar 30, 2027", formatMonthYearOrDate(LocalDate.of(2027, 3, 30).toEpochDay(), Locale.US))
    }

    @Test
    fun `the dialog is one selectable node per month and per year, and OK writes the month end`() {
        var chosen: Long? = null
        compose.setContent { MonthYearField(label = "Expiry", epochDay = LocalDate.of(2027, 3, 31).toEpochDay(), yearsAhead = 3, onChange = { chosen = it }) }
        compose.onNodeWithText("03/2027").assertExists()
        compose.onNodeWithContentDescription("Expiry: open the calendar").performClick()
        // 12 months + (this year - 1 .. this year + 3) = 5 years
        compose.onAllNodes(isSelectable()).assertCountEquals(17)
        compose.onNodeWithText("Jun").performClick()
        compose.onNodeWithText("OK").performClick()
        assertEquals(LocalDate.of(2027, 6, 30).toEpochDay(), chosen)
    }
}
