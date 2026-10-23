package com.hazia.emivita.feature.medicines

import android.os.Looper
import androidx.activity.ComponentDialog
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import com.hazia.emivita.ui.theme.EmivitaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowDialog

/**
 * D-07 V3 (§7 R114, the owner's pick): the Medicines tab's add button opens
 * one sheet whose Save writes the medicine and its first daily reminder and
 * arms its alarm; "Save and add details" saves the same and hands the new id
 * to the full edit form. The sheet keeps its input across a restore, asks
 * before Back or its Annulla drops it (§7 R125), says a bad dose on the field
 * only while it is wrong, and warns about a name the profile already has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class, qualifiers = "w412dp-h869dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QuickAddMedicineTest {
    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)

    private val detailsFor = mutableListOf<Long>()

    private fun showMedicines() {
        val c = h.container
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EmivitaTheme {
                MedicinesScreen(
                    profileId = h.profileId,
                    medicineRepository = c.medicineRepository,
                    doseEventRepository = c.doseEventRepository,
                    occurrenceProjectionCache = c.occurrenceProjectionCache,
                    settingsRepository = c.settingsRepository,
                    onAddMedicineWithBarcode = {},
                    onOpenMedicine = {},
                    onAddDetails = { detailsFor += it },
                )
            }
        }
        settle()
    }

    private fun showSheet(otherNames: List<String> = emptyList(), restore: StateRestorationTester? = null, onDismiss: () -> Unit = {}) {
        compose.mainClock.autoAdvance = restore != null
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            EmivitaTheme { QuickAddMedicineSheet(otherNames, save = { 1L }, onSaved = { _, _ -> }, onDismiss = onDismiss) }
        }
        if (restore != null) restore.setContent(content) else compose.setContent(content)
        settle()
    }

    private fun settle() {
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    /** Room writes run on Dispatchers.IO, outside the test's clock. */
    private fun eventually(seconds: Int = 10, check: () -> Unit) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (true) {
            try { check(); return } catch (e: AssertionError) { if (System.nanoTime() > deadline) throw e }
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
            settle()
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private val saveButton = hasText("Save") and hasClickAction()

    private fun openSheet() {
        compose.onNode(hasContentDescription("Add medicine") and hasClickAction()).performClick()
        settle()
    }

    private fun medicines(): List<Medicine> = runBlocking {
        h.container.medicineRepository.observeForProfile(h.profileId).first().map { it.medicine }
    }

    @Test
    fun `the add button opens the sheet, and Save writes the medicine with its daily reminder and arms it`() {
        showMedicines()
        openSheet()
        field("Name").performTextInput("Metformin")
        field("Unit").performTextInput("tablet")
        settle()
        compose.onNode(saveButton).performClick()
        settle()

        eventually {
            val medicine = medicines().single()
            assertEquals("Metformin", medicine.name)
            assertEquals("tablet", medicine.doseUnit)
            val reminder = runBlocking { h.container.reminderRepository.observeForMedicine(medicine.id).first() }.single()
            assertEquals(ReminderType.FIXED_TIME, reminder.type)
            assertEquals(ReminderConfig.FixedTime(8, 0, 1.0, RepeatPattern.Daily), decodeReminderConfig(reminder.configJson))
            assertTrue("Add reminder's B-16 default: repeat until answered", reminder.repeatOnUnanswered != null)
            assertEquals("its alarm is armed", 1, h.regularAlarms(reminder.id).size)
        }
        assertEquals("Save alone opens nothing else", emptyList<Long>(), detailsFor)
        eventually { compose.onAllNodes(saveButton).fetchSemanticsNodes().isEmpty().let { assertTrue("the sheet closed", it) } }
    }

    @Test
    fun `Save and add details saves the same and hands the new medicine to the full form`() {
        showMedicines()
        openSheet()
        field("Name").performTextInput("Atorvastatin")
        settle()
        compose.onNode(hasText("Save and add details") and hasClickAction()).performClick()
        settle()
        eventually {
            val medicine = medicines().single()
            assertEquals(listOf(medicine.id), detailsFor)
            assertEquals(1, runBlocking { h.container.reminderRepository.observeForMedicine(medicine.id).first() }.size)
        }
    }

    @Test
    fun `a dose that is not a number above zero is said on the field as typed, and Save waits`() {
        showSheet()
        field("Name").performTextInput("Metformin")
        settle()
        compose.onNode(saveButton).assertIsEnabled()
        // §7 R125: a rule, so not said at rest (the line stays reserved).
        compose.onNode(hasText("Above zero")).assertDoesNotExist()
        field("Dose amount").performTextReplacement("0")
        settle()
        compose.onNodeWithText("Above zero").assertExists()
        compose.onNode(saveButton).assertIsNotEnabled()
        field("Dose amount").performTextReplacement("2")
        settle()
        compose.onNode(hasText("Above zero")).assertDoesNotExist()
        compose.onNode(saveButton).assertIsEnabled()
    }

    /** On the clock running by itself, so the restore really recomposes; the
     *  text is set without focusing, as a blinking cursor never lets it idle. */
    @Test
    fun `the typed input survives a saved-state restore`() {
        val restore = StateRestorationTester(compose)
        showSheet(restore = restore)
        field("Name").performTextReplacement("Levothyroxine")
        field("Unit").performTextReplacement("drops")
        compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Levothyroxine").assertExists()
        compose.onNodeWithText("drops").assertExists()
    }

    @Test
    fun `Back with typed input asks before dropping it`() {
        var dismissed = 0
        showSheet(onDismiss = { dismissed++ })
        field("Name").performTextInput("Metformin")
        settle()
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        eventually { compose.onNodeWithText("Discard changes?").assertExists() }
        assertEquals("nothing dropped yet", 0, dismissed)
        compose.onNode(hasText("Discard") and hasClickAction()).performClick()
        settle()
        assertEquals(1, dismissed)
    }

    /** §7 R125: the sheet's foot has Annulla, which asks as Back does. */
    @Test
    fun `Annulla with typed input asks before dropping it, and keeping the edits keeps them`() {
        var dismissed = 0
        showSheet(onDismiss = { dismissed++ })
        field("Name").performTextInput("Metformin")
        settle()
        compose.onNode(hasText("Cancel") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        eventually { compose.onNodeWithText("Discard changes?").assertExists() }
        assertEquals("nothing dropped yet", 0, dismissed)
        compose.onNode(hasText("Keep editing") and hasClickAction()).performClick()
        settle()
        assertEquals("kept", 0, dismissed)
        compose.onNodeWithText("Metformin").assertExists()
        compose.onNode(hasText("Cancel") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        eventually { compose.onNodeWithText("Discard changes?").assertExists() }
        compose.onNode(hasText("Discard") and hasClickAction()).performClick()
        settle()
        assertEquals(1, dismissed)
    }

    @Test
    fun `untouched, Annulla leaves at once`() {
        var dismissed = 0
        showSheet(onDismiss = { dismissed++ })
        compose.onNode(hasText("Cancel") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        eventually { assertEquals(1, dismissed) }
        compose.onNode(hasText("Discard changes?")).assertDoesNotExist()
    }

    @Test
    fun `untouched, Back leaves at once`() {
        var dismissed = 0
        showSheet(onDismiss = { dismissed++ })
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        eventually { assertEquals(1, dismissed) }
        compose.onNode(hasText("Discard changes?")).assertDoesNotExist()
    }

    @Test
    fun `a name the profile already has is warned about, and Save still works`() {
        h.medicine("Vitamina D")
        showMedicines()
        openSheet()
        field("Name").performTextInput("  vitamina   d ")
        settle()
        compose.onNodeWithText("You already have a medicine with this name").assertExists()
        compose.onNode(saveButton).assertIsEnabled().performClick()
        settle()
        eventually { assertEquals(2, medicines().size) }
    }

    @Test
    fun `a name in another profile is not warned about`() {
        runBlocking {
            val other = h.container.profileRepository.create("Other")
            h.container.medicineRepository.create(
                Medicine(profileId = other, name = "Aspirin", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0L),
            )
        }
        showMedicines()
        openSheet()
        field("Name").performTextInput("aspirin")
        settle()
        compose.onNode(hasText("You already have a medicine with this name")).assertDoesNotExist()
    }

    @Test
    fun `a name left empty after a visit says so, and Save stays off`() {
        showSheet()
        compose.onNode(hasText("Write the medicine's name")).assertDoesNotExist()
        field("Name").performClick()
        settle()
        field("Unit").performClick()
        settle()
        compose.onNodeWithText("Write the medicine's name").assertExists()
        compose.onNode(saveButton).assertIsNotEnabled()
    }

    @Test
    fun `names compare without case and spaces`() {
        assertTrue(sameMedicineName("Vitamina D", " vitamina d"))
        assertTrue(sameMedicineName("VitaminaD", "vitamina d"))
        assertTrue(!sameMedicineName("Vitamina D", "Vitamina D3"))
        assertTrue(!sameMedicineName("", " "))
    }
}
