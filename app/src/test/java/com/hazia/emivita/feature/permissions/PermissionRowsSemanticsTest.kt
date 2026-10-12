package com.hazia.emivita.feature.permissions

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
                    onFix = { fixed += it.name.first().lowercase() },
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
            Column { PermissionRows(PermissionState(true, true, true, true), {}) }
        }
        compose.onAllNodes(hasText("Fix")).assertCountEquals(0)
        compose.onNodeWithText("Exact alarms").assertExists()
    }

    // ---- §7 R83: the setup sheet and the Today card ----

    @Test
    fun `what is missing, and the dismissal key, ignore the order the grants are read in`() {
        val state = PermissionState(notifications = true, exactAlarms = false, fullScreen = true, batteryExempt = false)
        assertEquals(listOf(ReminderPermission.EXACT_ALARMS, ReminderPermission.BATTERY), state.missing)
        assertEquals("BATTERY,EXACT_ALARMS", missingKey(state.missing))
        assertEquals(missingKey(state.missing), missingKey(state.missing.reversed()))
    }

    @Test
    fun `Today's card lists only the missing grants, each with Allow, and offers Not now`() {
        var fixed: ReminderPermission? = null
        var dismissed = false
        compose.setContent {
            MissingPermissionsCard(PermissionState(true, true, false, true), onFix = { fixed = it }, onDismiss = { dismissed = true })
        }
        compose.onNodeWithText("Full-screen alarms").assertExists()
        compose.onAllNodes(hasText("Exact alarms")).assertCountEquals(0)
        compose.onNodeWithText("Allow").performClick()
        assertEquals(ReminderPermission.FULL_SCREEN, fixed)
        compose.onNodeWithText("Not now").performClick()
        assertEquals(true, dismissed)
    }
}
