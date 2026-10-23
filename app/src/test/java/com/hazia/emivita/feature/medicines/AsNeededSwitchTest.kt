package com.hazia.emivita.feature.medicines

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hazia.emivita.core.data.dao.MedicineWithTags
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.repository.MedicineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The full form's *Al bisogno* switch (§7 R116, the owner's choice): one row,
 * the switch display-only inside it, beside the as-needed limit; it saves what
 * it shows, and a limit saved with it off leaves it off.
 */
@RunWith(RobolectricTestRunner::class)
class AsNeededSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private fun repository(vararg existing: Medicine): MedicineRepository {
        val medicines = mockk<MedicineRepository>(relaxed = true)
        every { medicines.observeForProfile(1L) } returns flowOf(existing.map { MedicineWithTags(it, emptyList()) })
        every { medicines.observeTagsForMedicine(any()) } returns flowOf(emptyList())
        existing.forEach { coEvery { medicines.getById(it.id) } returns it }
        return medicines
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun switchRow() = compose.onNode(isToggleable() and hasText("As needed"))
    private fun save(label: String = "Save") = compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()

    @Test
    fun `turning it on saves an as-needed medicine, its line said beside it`() {
        val medicines = repository()
        val saved = slot<Medicine>()
        coEvery { medicines.create(capture(saved)) } returns 9L
        compose.setContent { AddMedicineScreen(profileId = 1L, medicineRepository = medicines, onSaved = {}, onCancel = {}) }
        compose.waitForIdle()
        field("Name").performTextInput("Paracetamol")
        compose.onNodeWithText("Taken when needed: stays on Today with its Log button", useUnmergedTree = true).assertExists()
        switchRow().performScrollTo().assertIsOff().performClick()
        switchRow().assertIsOn()
        save()
        compose.waitForIdle()
        assertEquals(true, saved.captured.asNeeded)
    }

    @Test
    fun `a limit saved with the switch off leaves it off`() {
        val medicines = repository()
        val saved = slot<Medicine>()
        coEvery { medicines.create(capture(saved)) } returns 9L
        compose.setContent { AddMedicineScreen(profileId = 1L, medicineRepository = medicines, onSaved = {}, onCancel = {}) }
        compose.waitForIdle()
        field("Name").performTextInput("Ibuprofen")
        field("Max doses").performTextInput("3")
        field("Per hours").performTextInput("24")
        save()
        compose.waitForIdle()
        assertEquals(3, saved.captured.prnMaxDoses)
        assertEquals(false, saved.captured.asNeeded)
    }

    @Test
    fun `editing shows the medicine's switch, and turning it off saves off`() {
        val paracetamol = Medicine(id = 1L, profileId = 1L, name = "Paracetamol", iconKey = "pill", sortOrder = 0.0, asNeeded = true, createdAtEpochMillis = 0L)
        val medicines = repository(paracetamol)
        compose.setContent { EditMedicineScreen(medicineId = 1L, medicineRepository = medicines, onSaved = {}, onCancel = {}) }
        compose.waitForIdle()
        switchRow().performScrollTo().assertIsOn().performClick()
        save("Save changes")
        compose.waitForIdle()
        coVerify { medicines.update(match { it.id == 1L && !it.asNeeded }) }
    }
}
