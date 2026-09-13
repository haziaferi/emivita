package com.hazia.emivita.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The four permission rows (spec §9 #29, HM-37): state as stateDescription, Fix only when blocked. */
@RunWith(RobolectricTestRunner::class)
class PermissionRowsSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `each row announces its state and only blocked rows offer Fix`() {
        val fixed = mutableSetOf<String>()
        // In a Column: the rows are emitted bare, and a bare root stacks them
        // at the origin, where a tap on one row's button lands on another's.
        compose.setContent {
            Column {
                PermissionRows(
                    state = PermissionState(notifications = true, exactAlarms = false, fullScreen = true, batteryExempt = false),
                    onNotifications = { fixed += "n" }, onExactAlarms = { fixed += "e" }, onFullScreen = { fixed += "f" }, onBattery = { fixed += "b" },
                )
            }
        }
        compose.onAllNodes(hasStateDescription("Allowed")).assertCountEquals(2)
        compose.onAllNodes(hasStateDescription("Blocked")).assertCountEquals(2)
        compose.onAllNodes(hasText("Fix")).assertCountEquals(2)
        compose.onNode(hasText("Fix") and hasAnyAncestor(hasStateDescription("Blocked") and hasAnyDescendant(hasText("Exact alarms"))), useUnmergedTree = true).performClick()
        compose.onNode(hasText("Fix") and hasAnyAncestor(hasStateDescription("Blocked") and hasAnyDescendant(hasText("Unrestricted battery"))), useUnmergedTree = true).performClick()
        assertEquals("each Fix belongs to its own blocked row", setOf("e", "b"), fixed)
    }

    @Test
    fun `all granted -- no Fix at all`() {
        compose.setContent {
            Column { PermissionRows(PermissionState(true, true, true, true), {}, {}, {}, {}) }
        }
        compose.onAllNodes(hasText("Fix")).assertCountEquals(0)
        compose.onNodeWithText("Exact alarms").assertExists()
    }
}
