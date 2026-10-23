package com.hazia.emivita.feature.today

import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import com.hazia.emivita.ui.theme.EmivitaTheme
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

/**
 * A medicine with no reminder has its own section on Today, "No reminder",
 * with Add reminder on its row (§7 R114, the owner's pick); "As needed" keeps
 * the medicines with the *Al bisogno* switch on (§7 R116, not the limit, as
 * R114 had it) and those whose reminders are all paused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class, qualifiers = "w412dp-h869dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TodayNoReminderTest {
    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)

    private val addReminderFor = mutableListOf<Long>()

    private fun showToday() {
        val c = h.container
        compose.mainClock.autoAdvance = false
        compose.setContent {
            EmivitaTheme {
                TodayScreen(
                    profileId = h.profileId,
                    medicineRepository = c.medicineRepository,
                    doseEventRepository = c.doseEventRepository,
                    occurrenceProjectionCache = c.occurrenceProjectionCache,
                    settingsRepository = c.settingsRepository,
                    reminderRepository = c.reminderRepository,
                    notificationBuilder = c.notificationBuilder,
                    onAddReminder = { addReminderFor += it },
                )
            }
        }
    }

    private fun settle() {
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    private fun eventually(seconds: Int = 10, check: () -> Unit) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (true) {
            try { check(); return } catch (e: AssertionError) { if (System.nanoTime() > deadline) throw e }
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
            settle()
        }
    }

    private fun top(text: String) = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `a medicine with no reminder is under No reminder, and its button opens Add reminder for it`() {
        val metformin = h.medicine("Metformin")
        val paracetamol = h.medicine("Paracetamol")
        runBlocking { h.container.medicineRepository.update(h.medicineRow(paracetamol).copy(asNeeded = true)) }
        showToday()
        eventually { compose.onNodeWithText("No reminder").assertExists() }
        compose.onNodeWithText("As needed").assertExists()
        // Each under its own heading: As needed, Paracetamol, No reminder, Metformin.
        assertTrue(top("As needed") < top("Paracetamol"))
        assertTrue(top("Paracetamol") < top("No reminder"))
        assertTrue(top("No reminder") < top("Metformin"))
        compose.onNode(hasText("Add reminder") and hasClickAction()).performClick()
        assertEquals(listOf(metformin), addReminderFor)
    }

    @Test
    fun `a medicine with the switch on keeps its Log button, one with only a limit does not`() {
        val paracetamol = h.medicine("Paracetamol")
        val ibuprofen = h.medicine("Ibuprofen")
        runBlocking {
            h.container.medicineRepository.update(h.medicineRow(paracetamol).copy(asNeeded = true))
            h.container.medicineRepository.update(h.medicineRow(ibuprofen).copy(prnMaxDoses = 3, prnWindowHours = 24))
        }
        showToday()
        eventually { compose.onNodeWithText("No reminder").assertExists() }
        assertEquals(1, compose.onAllNodes(hasText("Log") and hasClickAction()).fetchSemanticsNodes().size)
        assertTrue(top("As needed") < top("Paracetamol"))
        assertTrue("Paracetamol, switch on, is under As needed", top("Paracetamol") < top("No reminder"))
        assertTrue("Ibuprofen, a limit only, is under No reminder", top("No reminder") < top("Ibuprofen"))
    }

    // ---- which section, by the rule alone ----

    private fun med(id: Long, max: Int? = null, hours: Int? = null, asNeeded: Boolean = false) =
        Medicine(id = id, profileId = 1L, name = "m$id", iconKey = "pill", sortOrder = id.toDouble(), prnMaxDoses = max, prnWindowHours = hours, asNeeded = asNeeded, createdAtEpochMillis = 0L)

    private fun rem(type: ReminderType, enabled: Boolean, config: ReminderConfig = ReminderConfig.FixedTime(8, 0, 1.0)) =
        Reminder(medicineId = 0, type = type, configJson = encodeReminderConfig(config), enabled = enabled, createdAtEpochMillis = 0L)

    @Test
    fun `the sections, case by case`() {
        val sections = standingSections(
            listOf(
                MedicineWithReminders(med(1), emptyList()), // nothing at all: no reminder
                MedicineWithReminders(med(2, asNeeded = true), emptyList()), // the switch on: as needed
                MedicineWithReminders(med(3), listOf(rem(ReminderType.FIXED_TIME, enabled = false))), // all paused: as needed
                MedicineWithReminders(med(4), listOf(rem(ReminderType.FIXED_TIME, enabled = true))), // scheduled: the timeline
                MedicineWithReminders(
                    med(5),
                    listOf(rem(ReminderType.LOW_STOCK_ALERT, enabled = true, ReminderConfig.LowStockAlert(5.0, LowStockTriggerMode.ONCE_WHEN_BELOW, 9, 0))),
                ), // an alert schedules no dose: no reminder
                MedicineWithReminders(med(6, max = 2, hours = 8), listOf(rem(ReminderType.FIXED_TIME, enabled = true))), // scheduled with a limit: the timeline
                MedicineWithReminders(med(7, max = 4, hours = 24), emptyList()), // a limit, the switch off: no reminder
                MedicineWithReminders(med(8, asNeeded = true), listOf(rem(ReminderType.FIXED_TIME, enabled = true))), // the switch on and scheduled: the timeline only
                MedicineWithReminders(
                    med(9, asNeeded = true),
                    listOf(rem(ReminderType.EXPIRATION_ALERT, enabled = true, ReminderConfig.ExpirationAlert(7, ExpirationTriggerFrequency.ONCE))),
                ), // the switch on, alert-only: as needed
            ),
        )
        assertEquals(listOf(2L, 3L, 9L), sections.asNeeded.map { it.id })
        assertEquals(listOf(1L, 5L, 7L), sections.noReminder.map { it.id })
    }
}
