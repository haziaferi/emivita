package com.hazia.emivita.feature.reminders

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The window form says when it runs past midnight (spec §9 #31, A-20), and only then. */
@RunWith(RobolectricTestRunner::class)
class WindowEndsNextDayNoteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an end before the start is announced as the next day`() {
        compose.setContent { WindowEndsNextDayNote(startHour = 22, startMinute = 0, endHour = 6, endMinute = 0) }
        compose.onNodeWithText("Ends the next day at", substring = true).assertExists()
    }

    @Test
    fun `a same-day window says nothing`() {
        compose.setContent { WindowEndsNextDayNote(startHour = 8, startMinute = 0, endHour = 20, endMinute = 0) }
        compose.onNodeWithText("Ends the next day at", substring = true).assertDoesNotExist()
    }
}
