package com.hazia.emivita.feature.history

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction

/**
 * The regression guard for spec §7 R46 and §7 R47: a never-answered
 * ("Missed") dose must open the edit dialog from every History surface,
 * where [HistoryEntryRow] once gated on a `TAKEN || SKIPPED` allow-list.
 *
 * Runs under Robolectric in the unit-test source set rather than `androidTest`
 * because CI runs `testDebugUnitTest` and has no device (see
 * app/build.gradle.kts). Assertions are on semantics and click handling only.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryEntryRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun medicine() = Medicine(
        id = 1L,
        profileId = 1L,
        name = "Sertraline",
        iconKey = "pill",
        sortOrder = 0.0,
        createdAtEpochMillis = 0L,
    )

    private fun row(status: DoseStatus) = DoseEventWithMedicine(
        doseEvent = DoseEvent(
            id = 7L,
            reminderId = 3L,
            medicineId = 1L,
            scheduledEpochMillis = 1_700_000_000_000L,
            doseAmount = 1.0,
            status = status,
            createdAtEpochMillis = 0L,
        ),
        medicine = medicine(),
    )

    private fun clicksFor(status: DoseStatus): Int {
        var clicks = 0
        compose.setContent { HistoryEntryRow(row(status), onClick = { clicks++ }) }
        compose.onNodeWithText("Sertraline").performClick()
        compose.waitForIdle()
        return clicks
    }

    /** The R46 defect itself: a dose that fired and was never answered. */
    @Test
    fun missedDoseIsClickable() {
        assertEquals(1, clicksFor(DoseStatus.PENDING))
    }

    @Test
    fun takenDoseIsClickable() {
        assertEquals(1, clicksFor(DoseStatus.TAKEN))
    }

    @Test
    fun skippedDoseIsClickable() {
        assertEquals(1, clicksFor(DoseStatus.SKIPPED))
    }

    /** A refill is a restock, not a dose -- there is nothing to resolve. */
    @Test
    fun refillIsNotClickable() {
        assertEquals(0, clicksFor(DoseStatus.REFILLED))
    }

    // ---- Stage B (spec §9 #19): what a screen reader is told ------------------

    private fun hasRole(role: androidx.compose.ui.semantics.Role) =
        androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, role)

    @Test
    fun aMissedDoseIsOneButtonNodeWhoseStateSaysMissed() {
        // PENDING is relabelled "Missed" everywhere it is shown; the semantic
        // state must say the same, or a screen reader hears "pending" for a
        // dose the screen calls missed.
        compose.setContent { HistoryEntryRow(row(DoseStatus.PENDING), onClick = {}) }
        compose.onNode(androidx.compose.ui.test.hasStateDescription("Missed"))
            .assert(hasRole(androidx.compose.ui.semantics.Role.Button))
            .assert(androidx.compose.ui.test.hasText("Sertraline", substring = true))
            .assertHasClickAction()
    }

    @Test
    fun aRefillAnnouncesItsStateWithoutBeingAButton() {
        compose.setContent { HistoryEntryRow(row(DoseStatus.REFILLED), onClick = {}) }
        compose.onNode(androidx.compose.ui.test.hasStateDescription("Refilled")).assertHasNoClickAction()
    }
}
