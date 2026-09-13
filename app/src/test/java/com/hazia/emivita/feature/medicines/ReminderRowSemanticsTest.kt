package com.hazia.emivita.feature.medicines

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.entity.Reminder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reminder row on the medicine detail screen (spec §9 #24): the row is
 * a button that opens the editor, the switch is a separate, labelled node
 * that pauses -- two actions, two nodes, unlike the Stage B rows where one
 * action had two. An alert reminder has no switch (the stock screen owns it).
 */
@RunWith(RobolectricTestRunner::class)
class ReminderRowSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun reminder(type: ReminderType, enabled: Boolean) = Reminder(
        id = 1L, medicineId = 1L, type = type, enabled = enabled,
        configJson = encodeReminderConfig(
            if (type == ReminderType.EXPIRATION_ALERT) ReminderConfig.ExpirationAlert(leadDays = 7, frequency = com.hazia.emivita.core.data.config.ExpirationTriggerFrequency.ONCE)
            else ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0),
        ),
        createdAtEpochMillis = 0,
    )

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun theRowIsAButtonAndTheSwitchIsItsOwnLabelledNode() {
        var edited = 0
        compose.setContent { ReminderRow(reminder(ReminderType.FIXED_TIME, enabled = true), onEdit = { edited++ }, onEnabledChange = {}) }
        compose.onAllNodes(hasRole(Role.Button)).assertCountEquals(1)
        compose.onAllNodes(hasRole(Role.Switch)).assertCountEquals(1)
        compose.onAllNodes(hasContentDescription("Fixed Time reminder enabled")).assertCountEquals(1)
        compose.onAllNodes(hasRole(Role.Switch))[0].assertIsOn()
        compose.onNodeWithText("Fixed Time").performClick()
        assertEquals(1, edited)
    }

    @Test
    fun aPausedReminderSaysSoAndItsSwitchIsOff() {
        var changedTo: Boolean? = null
        compose.setContent { ReminderRow(reminder(ReminderType.FIXED_TIME, enabled = false), onEdit = {}, onEnabledChange = { changedTo = it }) }
        compose.onNodeWithText("Paused").assertExists()
        compose.onAllNodes(hasRole(Role.Switch))[0].assertIsOff()
        compose.onAllNodes(hasRole(Role.Switch))[0].performClick()
        assertEquals(true, changedTo)
    }

    @Test
    fun anAlertReminderHasNoSwitch() {
        compose.setContent { ReminderRow(reminder(ReminderType.EXPIRATION_ALERT, enabled = true), onEdit = {}, onEnabledChange = {}) }
        compose.onAllNodes(hasRole(Role.Switch)).assertCountEquals(0)
        compose.onAllNodes(hasRole(Role.Button)).assertCountEquals(1)
    }
}
