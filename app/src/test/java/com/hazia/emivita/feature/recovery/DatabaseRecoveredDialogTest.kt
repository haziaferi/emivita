package com.hazia.emivita.feature.recovery

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The recovery notice (spec §9 #32): two answers, each its own callback, nothing else. */
@RunWith(RobolectricTestRunner::class)
class DatabaseRecoveredDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Restore and Continue empty each answer the notice once`() {
        var restored = 0
        var continued = 0
        compose.setContent { DatabaseRecoveredDialog(keyLost = false, onRestore = { restored++ }, onContinueEmpty = { continued++ }) }
        compose.onNodeWithText("Your medicines could not be opened").assertExists()
        compose.onNodeWithText("Restore from backup").performClick()
        compose.onNodeWithText("Continue empty").performClick()
        assertEquals(1, restored)
        assertEquals(1, continued)
    }

    /** §7 R83 (f): a lost key gets words that promise nothing recoverable. */
    @Test
    fun `a lost key says the data cannot be read, now or later`() {
        compose.setContent { DatabaseRecoveredDialog(keyLost = true, onRestore = {}, onContinueEmpty = {}) }
        compose.onNodeWithText("Your medicines cannot be opened").assertExists()
        compose.onNodeWithText("now or later", substring = true).assertExists()
        compose.onAllNodesWithText("set aside", substring = true).assertCountEquals(0)
    }
}
