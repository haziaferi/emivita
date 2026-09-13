package com.hazia.emivita.feature.history

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.entity.Medicine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The export scope dialog (spec §9 #26): a radio per range preset, a chip
 *  per medicine plus All, each its own node (Stage B pattern). */
@RunWith(RobolectricTestRunner::class)
class ExportScopeDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun medicine(id: Long, name: String) = Medicine(id = id, profileId = 1L, name = name, iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0L)

    @Test
    fun everyRangeIsItsOwnSelectableNodeAndTheChosenOneIsSelected() {
        var range = ExportRange.LAST_30_DAYS
        compose.setContent {
            ExportScopeDialog(range = range, onRange = { range = it }, medicines = emptyList(), medicineId = null, onMedicine = {}, onDismiss = {}, onConfirm = {})
        }
        assertEquals(4, ExportRange.entries.size)
        compose.onNodeWithText("Last 30 days").assertIsSelected()
        compose.onNodeWithText("Everything").performClick()
        assertEquals(ExportRange.ALL, range)
    }

    @Test
    fun theMedicineFilterOffersAllPlusOneChipPerMedicine() {
        var chosen: Long? = 99L
        compose.setContent {
            ExportScopeDialog(
                range = ExportRange.ALL, onRange = {}, medicines = listOf(medicine(1, "Sertraline"), medicine(2, "Ibuprofen")),
                medicineId = null, onMedicine = { chosen = it }, onDismiss = {}, onConfirm = {},
            )
        }
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithText("Ibuprofen").performClick()
        assertEquals(2L, chosen)
        // 4 radios + 3 chips, each selectable on its own
        assertEquals(7, compose.onAllNodes(isSelectable()).fetchSemanticsNodes().size)
    }
}
