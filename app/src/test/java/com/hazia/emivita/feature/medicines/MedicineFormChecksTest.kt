package com.hazia.emivita.feature.medicines

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.hazia.emivita.R
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The full form's checks (§7 R114): the duplicate-name warning that never
 * blocks Save and never fires on the medicine being edited, the blank-name
 * message once the field has been left, and the as-needed limit taken as
 * both numbers or neither, which was saved half and then showed no ceiling.
 */
@RunWith(RobolectricTestRunner::class)
class MedicineFormChecksTest {

    @get:Rule
    val compose = createComposeRule()

    private fun med(id: Long, name: String) = Medicine(id = id, profileId = 1L, name = name, iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0L)

    private fun repository(vararg names: String): MedicineRepository {
        val medicines = mockk<MedicineRepository>(relaxed = true)
        val list = names.mapIndexed { i, n -> MedicineWithTags(med(i + 1L, n), emptyList()) }
        every { medicines.observeForProfile(1L) } returns flowOf(list)
        every { medicines.observeTagsForMedicine(any()) } returns flowOf(emptyList())
        list.forEach { coEvery { medicines.getById(it.medicine.id) } returns it.medicine }
        return medicines
    }

    private fun add(medicines: MedicineRepository) {
        compose.setContent { AddMedicineScreen(profileId = 1L, medicineRepository = medicines, onSaved = {}, onCancel = {}) }
        compose.waitForIdle()
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private val duplicate = "You already have a medicine with this name"

    @Test
    fun `a name the profile already has is warned about, and Save still works`() {
        val medicines = repository("Vitamina D")
        add(medicines)
        field("Name").performTextInput("vitamina d")
        compose.onNodeWithText(duplicate).assertExists()
        compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        coVerify { medicines.create(match { it.name == "vitamina d" }) }
    }

    @Test
    fun `editing a medicine does not warn about itself, only about another`() {
        val medicines = repository("Aspirin", "Ibuprofen")
        compose.setContent { EditMedicineScreen(medicineId = 1L, medicineRepository = medicines, onSaved = {}, onCancel = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("Aspirin").assertExists()
        compose.onNode(hasText(duplicate)).assertDoesNotExist()
        field("Name").performTextReplacement("IBUPROFEN")
        compose.onNodeWithText(duplicate).assertExists()
    }

    @Test
    fun `a name left empty after a visit says so, and Save stays off`() {
        add(repository())
        compose.onNode(hasText("Write the medicine's name")).assertDoesNotExist()
        field("Name").performClick()
        field("Measurement (optional)").performClick()
        compose.onNodeWithText("Write the medicine's name").assertExists()
        compose.onNode(hasText("Save") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `an as-needed maximum without its hours is said on the hours, and Save waits for both`() {
        val medicines = repository()
        add(medicines)
        field("Name").performTextInput("Ibuprofen")
        field("Max doses").performTextInput("3")
        compose.onNodeWithText("Fill in both, or leave both empty").assertExists()
        compose.onNode(hasText("Save") and hasClickAction()).assertIsNotEnabled()
        field("Per hours").performTextInput("24")
        compose.onNode(hasText("Fill in both, or leave both empty")).assertDoesNotExist()
        val saved = slot<Medicine>()
        coEvery { medicines.create(capture(saved)) } returns 9L
        compose.onNode(hasText("Save") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(3, saved.captured.prnMaxDoses)
        assertEquals(24, saved.captured.prnWindowHours)
    }

    @Test
    fun `hours without a maximum, or a number that is not one, are said too`() {
        add(repository())
        field("Name").performTextInput("Ibuprofen")
        field("Per hours").performTextInput("8")
        compose.onNodeWithText("Fill in both, or leave both empty").assertExists()
        field("Max doses").performTextInput("0")
        compose.onNodeWithText("Enter a whole number above zero").assertExists()
        compose.onNode(hasText("Save") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `the limit check, both or neither`() {
        assertEquals(PrnCeilingCheck(null, null), checkPrnCeiling("", ""))
        assertEquals(PrnCeilingCheck(null, null), checkPrnCeiling("3", "24"))
        assertEquals(PrnCeilingCheck(null, R.string.add_medicine_prn_both), checkPrnCeiling("3", ""))
        assertEquals(PrnCeilingCheck(R.string.add_medicine_prn_both, null), checkPrnCeiling(" ", "8"))
        assertEquals(R.string.add_medicine_prn_whole, checkPrnCeiling("x", "8").maxError)
        assertNull(checkPrnCeiling("2", "-1").maxError)
        assertEquals(R.string.add_medicine_prn_whole, checkPrnCeiling("2", "-1").hoursError)
    }
}
