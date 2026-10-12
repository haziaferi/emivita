package com.hazia.emivita.feature

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Profile
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ProfileRepository
import com.hazia.emivita.feature.lock.relocksOn
import com.hazia.emivita.feature.medicines.AddMedicineScreen
import com.hazia.emivita.feature.reminders.AddReminderScreen
import com.hazia.emivita.feature.settings.MedicalIdScreen
import com.hazia.emivita.ui.components.DoseEditDialog
import com.hazia.emivita.ui.components.rememberDirty
import com.hazia.emivita.ui.components.stableForm
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * §7 R83 (a): a rotation, a theme or language switch, or a process death
 * used to reset every form. Each case types into a form, emulates the saved
 * state round trip the platform performs, and reads the input back.
 * StateRestorationTester also fails on any field that cannot be saved.
 */
@RunWith(RobolectricTestRunner::class)
class FormRotationTest {

    @get:Rule
    val compose = createComposeRule()
    private val restore = StateRestorationTester(compose)

    @Test
    fun `Add Medicine keeps the typed name`() {
        val medicines = mockk<MedicineRepository>(relaxed = true)
        every { medicines.observeForProfile(1L) } returns flowOf(emptyList())
        restore.setContent { AddMedicineScreen(profileId = 1L, medicineRepository = medicines, onSaved = {}, onCancel = {}) }
        compose.onNodeWithText("Name").performTextInput("Aspirin")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Aspirin").assertExists()
    }

    @Test
    fun `Add Reminder keeps the chosen type and the edited dose`() {
        val medicines = mockk<MedicineRepository>(relaxed = true)
        coEvery { medicines.getById(1L) } returns null
        restore.setContent {
            AddReminderScreen(medicineId = 1L, reminderRepository = mockk(relaxed = true), medicineRepository = medicines, onDone = {}, onCancel = {})
        }
        compose.onNodeWithText("Same time (or times) every day").performClick()
        compose.onNodeWithText("Dose amount").performTextReplacement("2.5")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2.5").assertExists()
    }

    @Test
    fun `Medical ID keeps an edit even though its load runs again`() {
        val profiles = mockk<ProfileRepository>(relaxed = true)
        coEvery { profiles.getById(1L) } returns Profile(id = 1L, name = "Me", createdAtEpochMillis = 0L)
        val medicines = mockk<MedicineRepository>(relaxed = true)
        every { medicines.observeForProfile(1L) } returns flowOf(emptyList())
        restore.setContent { MedicalIdScreen(profileId = 1L, profileRepository = profiles, medicineRepository = medicines, onBack = {}) }
        compose.waitForIdle()
        compose.onNodeWithText("Name").performTextInput("Ada Lovelace")
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Ada Lovelace").assertExists()
    }

    @Test
    fun `Dose Edit keeps the typed note`() {
        restore.setContent {
            DoseEditDialog(
                medicineName = "Sertraline",
                doseEventId = 7L,
                originalStatus = DoseStatus.TAKEN,
                originalDoseAmount = 1.0,
                originalResolvedAtEpochMillis = 0L,
                scheduledEpochMillis = 0L,
                doseEventRepository = mockk<DoseEventRepository>(relaxed = true),
                onDismiss = {},
            )
        }
        compose.onNodeWithText("Note (optional)").performTextInput("after food")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("after food").assertExists()
    }

    /** Without a saved baseline, the restored edits became "unchanged" and
     *  Back dropped them without asking. */
    @Test
    fun `an edited form is still dirty after the restore`() {
        var dirty = false
        restore.setContent {
            var text by rememberSaveable { mutableStateOf("a") }
            dirty = rememberDirty(Unit, text, setOf(2, 1))
            Text(text, Modifier.clickable { text = "b" })
        }
        compose.onNodeWithText("a").performClick()
        compose.waitForIdle()
        assertTrue(dirty)
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("the baseline is still the first value", dirty)
    }

    @Test
    fun `the lock re-locks on a real stop, not on the stop a rotation passes through`() {
        assertTrue(relocksOn(Lifecycle.Event.ON_STOP, changingConfigurations = false))
        assertFalse(relocksOn(Lifecycle.Event.ON_STOP, changingConfigurations = true))
        assertFalse(relocksOn(Lifecycle.Event.ON_PAUSE, changingConfigurations = false))
    }

    @Test
    fun `a set compares by its members, not their order`() {
        assertEquals(
            stableForm(setOf(1, 2)),
            stableForm(setOf(2, 1)),
        )
    }
}
