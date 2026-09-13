package com.hazia.emivita.ui.components

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.isSelectable
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.repository.DoseEventRepository
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText

/**
 * The other half of spec §7 R46 that shipped without verification: how this
 * dialog renders and behaves for a PENDING ("Missed") dose. R46's own note
 * records the device work being abandoned partway through, so nothing ever
 * confirmed the two behaviours it argues for at length (§7 R47).
 *
 * Both are about refusing to guess. Neither status radio starts selected,
 * because resolving a dose the app never saw answered is a choice; and the
 * recorded time starts at the dose's own scheduled slot, because a dose taken
 * on Tuesday and recorded on Friday happened on Tuesday -- which is also what
 * keeps a backdated correction from re-anchoring a live interval reminder.
 */
@RunWith(RobolectricTestRunner::class)
class DoseEditDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 2024-06-04, 08:30 local -- the slot the dose was scheduled for. */
    private val scheduled: Long =
        LocalDateTime.of(2024, 6, 4, 8, 30).atZone(zone).toInstant().toEpochMilli()

    private fun show(
        status: DoseStatus,
        resolvedAt: Long? = null,
        repository: DoseEventRepository = mockk(relaxed = true),
    ) {
        compose.setContent {
            DoseEditDialog(
                medicineName = "Sertraline",
                doseEventId = 7L,
                originalStatus = status,
                originalDoseAmount = 1.0,
                originalResolvedAtEpochMillis = resolvedAt,
                scheduledEpochMillis = scheduled,
                doseEventRepository = repository,
                onDismiss = {},
            )
        }
    }

    @Test
    fun missedDoseStartsWithNeitherStatusChosen() {
        show(DoseStatus.PENDING)
        compose.onAllNodes(isSelectable())[0].assertIsNotSelected()
        compose.onAllNodes(isSelectable())[1].assertIsNotSelected()
    }

    @Test
    fun missedDoseCannotBeSavedUntilAStatusIsChosen() {
        show(DoseStatus.PENDING)
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Taken").performClick()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun missedDoseExplainsWhyRecordingItMatters() {
        show(DoseStatus.PENDING)
        compose.onNodeWithText("This dose was never answered", substring = true).assertExists()
    }

    /**
     * The anchor property, from the UI side: a missed dose offers its own
     * scheduled time, not now. `resolve` must therefore be called with that
     * timestamp -- the repository-level half is pinned in
     * DoseEventRepositoryTest.
     */
    @Test
    fun missedDoseRecordsItsScheduledTimeNotNow() {
        val repository = mockk<DoseEventRepository>(relaxed = true)
        show(DoseStatus.PENDING, repository = repository)
        compose.onNodeWithText("Taken").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        coVerify { repository.resolve(7L, DoseStatus.TAKEN, scheduled) }
    }

    /** An already-resolved dose is unchanged by R46 -- its status starts set. */
    @Test
    fun resolvedDoseStartsWithItsOwnStatusChosen() {
        show(DoseStatus.TAKEN, resolvedAt = scheduled)
        compose.onAllNodes(isSelectable())[0].assertIsSelected()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    /**
     * §7 R60 C F-1: resolve() takes the stock change from the stored
     * doseAmount and editDoseAmount() never touches stock, so for a Missed
     * dose corrected with a different amount the edit must land first --
     * otherwise the bottle is charged the reminder's default, not the amount
     * the user just typed.
     */
    @Test
    fun missedDoseWithANewAmountWritesTheAmountBeforeResolving() {
        val repository = mockk<DoseEventRepository>(relaxed = true)
        show(DoseStatus.PENDING, repository = repository)
        compose.onNodeWithText("Taken").performClick()
        compose.onNodeWithText("Dose amount").performTextClearance()
        compose.onNodeWithText("Dose amount").performTextInput("2")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        coVerifyOrder {
            repository.editDoseAmount(7L, 2.0)
            repository.resolve(7L, DoseStatus.TAKEN, scheduled)
        }
    }

    // ---- §9 #26: the record can be annotated, undone, moved and removed ----

    @Test
    fun aNoteIsSavedOnlyWhenItChanged() {
        val repository = mockk<DoseEventRepository>(relaxed = true)
        show(DoseStatus.TAKEN, resolvedAt = scheduled, repository = repository)
        compose.onNodeWithText("Note (optional)").performTextInput("with food")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.setNotes(7L, "with food") }
    }

    @Test
    fun aResolvedDoseOffersReturnToDueAndAPendingOneOffersMove() {
        show(DoseStatus.TAKEN, resolvedAt = scheduled)
        compose.onNodeWithText("Return to due").assertExists()
        compose.onAllNodes(hasText("Move this dose")).assertCountEquals(0)
    }

    @Test
    fun aPendingDoseOffersMoveNotReturnToDue() {
        show(DoseStatus.PENDING)
        compose.onNodeWithText("Move this dose").assertExists()
        compose.onNodeWithText("Move and remind me then").assertIsNotEnabled()
        compose.onAllNodes(hasText("Return to due")).assertCountEquals(0)
    }

    @Test
    fun deleteTakesTwoTapsAndThenSoftDeletes() {
        val repository = mockk<DoseEventRepository>(relaxed = true)
        show(DoseStatus.TAKEN, resolvedAt = scheduled, repository = repository)
        compose.onNodeWithText("Delete this record").performScrollTo().performClick()
        coVerify(exactly = 0) { repository.softDelete(any()) }
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.waitForIdle()
        coVerify(exactly = 1) { repository.softDelete(7L) }
    }
}
