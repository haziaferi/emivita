package com.hazia.emivita.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.MedicineNote
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.feature.history.ExportRange
import com.hazia.emivita.feature.history.ExportScopeDialog
import com.hazia.emivita.feature.medicines.NotesJournal
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two R65 rows still owed a device look, measured instead (spec §9 #33,
 * F-14): the export dialog's medicine chips and the notes journal at the
 * small phone width.
 */
@RunWith(RobolectricTestRunner::class)
class DialogAndJournalLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun medicine(id: Long, name: String) = Medicine(id = id, profileId = 1L, name = name, iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0L)

    @Test
    fun `the export dialog's chips sit on one line and never overlap, even with long names at 320 dp`() {
        compose.setContentAt(320) {
            ExportScopeDialog(
                range = ExportRange.LAST_30_DAYS, onRange = {},
                medicines = listOf(medicine(1, "Levothyroxine sodium"), medicine(2, "Ibuprofen"), medicine(3, "Sertraline hydrochloride")),
                medicineId = null, onMedicine = {}, onDismiss = {}, onConfirm = {},
            )
        }
        // A dialog composes into its own window; its root is the second root node.
        val dialogRoot = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxBy { it.boundsInRoot.top }.boundsInRoot
        val chips = listOf("All", "Levothyroxine sodium", "Ibuprofen", "Sertraline hydrochloride")
            .map { label -> compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot }
        for ((a, b) in chips.zipWithNext()) {
            assertNoOverlap("chips in order", a, b)
            assertTrue("chips share one line: $a then $b", b.top < a.bottom && b.left >= a.right)
        }
        assertTrue("the first chip is inside the dialog: ${chips.first()} in $dialogRoot", chips.first().left >= dialogRoot.left && chips.first().right <= dialogRoot.right)
    }

    @Test
    fun `the notes journal fits 320 dp -- the field and two notes stack, nothing crosses the edge`() {
        val repository = mockk<MedicineRepository>()
        every { repository.observeNotes(1L) } returns flowOf(
            listOf(
                MedicineNote(id = 2, medicineId = 1, text = "Second note, a little longer than the first so that it has to wrap at a small width", createdAtEpochMillis = 2_000L),
                MedicineNote(id = 1, medicineId = 1, text = "First note", createdAtEpochMillis = 1_000L),
            ),
        )
        compose.setContentAt(320) {
            Box(Modifier.semantics { testTag = "journal" }) { NotesJournal(medicineId = 1L, medicineRepository = repository) }
        }
        val journal = compose.onNodeWithTag("journal").px()
        val hint = compose.nodesInside(journal, hasText("How is it going?", substring = true)).single().boundsInRoot
        val second = compose.nodesInside(journal, hasText("Second note", substring = true)).single().boundsInRoot
        val first = compose.nodesInside(journal, hasText("First note")).single().boundsInRoot
        // Add on a line of its own (§7 R137): beside the field it left the field a sliver at font 1.8 on the 9 Pro.
        val field = compose.nodesInside(journal, hasSetTextAction()).single().boundsInRoot
        val add = compose.nodesInside(journal, hasText("Add")).single().boundsInRoot
        assertStacked("Add under the field", field, add)
        assertEquals("the field takes the journal's width inside its padding", journal.width - compose.dp(32f), field.width, 0.5f)
        assertStacked("the newest note under the field", hint, second)
        assertStacked("the older note under the newer", second, first)
        for (node in compose.nodesInside(journal, hasText("", substring = true))) {
            assertWithin("a journal text node", node.boundsInRoot, journal)
        }
        assertEquals("the journal is exactly the width it was given", compose.dp(320f), journal.width, 0.5f)
    }
}
