package com.hazia.emivita.feature.today

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hazia.emivita.core.data.entity.Medicine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a screen reader is told about a dose on Today (spec §9 #19 Stage B).
 *
 * The row's status used to be text only, inside a Surface that was clickable
 * with no role. Now the row is one merged node with `Role.Button`, an
 * `onClickLabel`, and the status carried as `stateDescription` -- the channel
 * TalkBack treats as state, which is to accessibility what R55's texture
 * channel is to colour-blindness. Semantics only, no pixels (§7 R47).
 */
@RunWith(RobolectricTestRunner::class)
class TimelineEntryRowSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun medicine() = Medicine(
        id = 1L, profileId = 1L, name = "Sertraline", iconKey = "tablet",
        sortOrder = 0.0, createdAtEpochMillis = 0L,
    )

    private fun entry(status: TimelineStatus) = TimelineEntry(
        medicine = medicine(), doseAmount = 1.0, status = status, doseEventId = 7L,
        resolvedAtEpochMillis = null,
    )

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private val anyState = hasStateDescription("Taken") or hasStateDescription("Due") or
        hasStateDescription("Skipped") or hasStateDescription("Upcoming")

    @Test
    fun aTakenDoseIsOneButtonNodeWhoseStateIsItsStatus() {
        compose.setContent { TimelineEntryRow(entry(TimelineStatus.TAKEN), canSkip = true, onResolve = {}, onClick = {}) }
        compose.onNode(anyState)
            .assert(hasStateDescription("Taken"))
            .assert(hasRole(Role.Button))
            .assertHasClickAction()
            // Merged: the medicine name is part of this node, not a sibling.
            .assert(hasText("Sertraline", substring = true))
    }

    @Test
    fun anUpcomingDoseStillAnnouncesItsStateButIsNotAButton() {
        // UPCOMING is projected, not materialised: nothing to edit, so no click.
        // The state must still be spoken, or a screen reader cannot tell a
        // future dose from a due one.
        compose.setContent { TimelineEntryRow(entry(TimelineStatus.UPCOMING), canSkip = true, onResolve = {}, onClick = {}) }
        compose.onNode(anyState).assert(hasStateDescription("Upcoming")).assertHasNoClickAction()
    }

    @Test
    fun takeAndSkipStayTheirOwnControlsInsideADueRow() {
        // mergeDescendants on the row must not swallow the action chips: a
        // screen reader user needs "Take, button" reachable on its own, on the
        // one screen where a wrong action is a wrong dose.
        compose.setContent { TimelineEntryRow(entry(TimelineStatus.PENDING), canSkip = true, onResolve = {}, onClick = {}) }
        compose.onNode(anyState).assert(hasStateDescription("Due"))
        compose.onNodeWithText("Take").assert(hasClickAction())
        compose.onNodeWithText("Skip").assert(hasClickAction())
    }

    @Test
    fun aLockedDoseOffersNoSkipControlAtAll() {
        // Omitted rather than disabled (see the row's own comment): a screen
        // reader should find no Skip node, not a disabled one.
        compose.setContent { TimelineEntryRow(entry(TimelineStatus.PENDING), canSkip = false, onResolve = {}, onClick = {}) }
        compose.onNodeWithText("Take").assert(hasClickAction())
        compose.onAllNodes(hasText("Skip")).assertCountEquals(0)
    }
}
