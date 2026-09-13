package com.hazia.emivita.feature.disclaimer

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The first-launch disclaimer (spec §9 #29, MT-21): Continue only after the box is ticked. */
@RunWith(RobolectricTestRunner::class)
class DisclaimerDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Continue is disabled until I understand is checked, then accepts once`() {
        var accepted = 0
        compose.setContent { DisclaimerDialog(onAccept = { accepted++ }) }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        assertEquals("one toggleable node, the checkbox row", 1, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
        compose.onNodeWithText("I understand").performClick()
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(1, accepted)
    }
}
