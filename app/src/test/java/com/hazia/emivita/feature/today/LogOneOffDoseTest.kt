package com.hazia.emivita.feature.today

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import com.hazia.emivita.ui.theme.EmivitaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * C-15 V1 through Today's Log dose sheet (§7 R111): a typed name that matches
 * no medicine is logged once under itself, Taken, with no medicine and nothing
 * new in Medicines, and Today shows it as a past row by that name; a typed
 * name that matches a listed medicine logs that medicine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class, qualifiers = "w412dp-h869dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LogOneOffDoseTest {
    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)

    private fun showToday() {
        val c = h.container
        // The clock is driven by hand (settle): the dialog's text field blinks a
        // cursor, and with the clock advancing on its own Compose never reports idle.
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
                )
            }
        }
    }

    /** A listed medicine with a daily reminder, so Today lists it as scheduled. */
    private fun listed(name: String): Long = h.medicine(name).also { h.reminder(it, ReminderConfig.FixedTime(hour = 23, minute = 59, dose = 1.0)) }

    private fun rows() = runBlocking { h.container.doseEventRepository.getInRangeOnce(h.profileId, 0L, Long.MAX_VALUE, null) }
    private fun medicineNames() = runBlocking { h.container.medicineRepository.observeForProfile(h.profileId).first().map { it.medicine.name } }

    private fun settle() {
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    /** Room writes run on Dispatchers.IO, outside the test's clock: retry until
     *  the check holds or the time is up, then fail with the last. A row not yet
     *  written fails `single()`, not an assertion, so that retries too (§7 R138). */
    private fun eventually(seconds: Int = 10, check: () -> Unit) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (true) {
            try { check(); return } catch (e: Throwable) {
                if (e !is AssertionError && e !is NoSuchElementException || System.nanoTime() > deadline) throw e
            }
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
            settle()
        }
    }

    private fun logTyped(name: String) {
        eventually { compose.onNode(hasContentDescription("Log dose") and hasClickAction()).assertExists() }
        compose.onNode(hasContentDescription("Log dose") and hasClickAction()).performClick()
        eventually { compose.onNode(hasSetTextAction() and hasText("Medicine or name")).assertExists() }
        compose.onNode(hasSetTextAction() and hasText("Medicine or name")).performTextReplacement(name)
        settle()
    }

    @Test
    fun `a name not on the list is logged once under itself, with no medicine, and shows on Today`() {
        listed("Metformin")
        showToday()
        logTyped("Paracetamol")
        compose.onNodeWithText("“Paracetamol” is not on your list: only this dose is recorded.").assertExists()
        compose.onNode(hasText("Confirm") and hasClickAction()).performClick()
        settle()

        eventually {
            val dose = rows().single().doseEvent
            assertEquals(DoseStatus.TAKEN, dose.status)
            assertEquals("Paracetamol", dose.customName)
            assertNull(dose.medicineId)
            assertEquals(h.profileId, dose.customProfileId)
        }
        assertEquals("nothing new in Medicines", listOf("Metformin"), medicineNames())
        // Today: a past row by its name, recorded as taken.
        eventually { compose.onAllNodes(hasText("Paracetamol", substring = true)).assertCountEquals(1) }
    }

    @Test
    fun `a typed name on the list logs that medicine`() {
        val metformin = listed("Metformin")
        listed("Atorvastatin")
        showToday()
        logTyped("metformin")
        compose.onAllNodes(hasText("is not on your list", substring = true)).assertCountEquals(0)
        compose.onNode(hasText("Confirm") and hasClickAction()).performClick()
        settle()
        eventually {
            val dose = rows().single().doseEvent
            assertEquals(metformin, dose.medicineId)
            assertNull(dose.customName)
        }
    }
}
