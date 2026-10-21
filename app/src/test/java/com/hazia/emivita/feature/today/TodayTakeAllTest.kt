package com.hazia.emivita.feature.today

import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import org.junit.rules.TestRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.ui.theme.EmivitaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Take all" on a time group (§7 R107, census D-20, R106's pick V1), through
 * the real Today screen and the real repository: the button appears only on a
 * time with two or more doses due, its sheet lists exactly those doses, the
 * confirm records each one as its own Take would (status and stock), and
 * Cancel records nothing.
 */
@RunWith(RobolectricTestRunner::class)
// The 6T's size: on Robolectric's default screen the 11:00 chip sits under the Log dose FAB.
@Config(application = TestEmivitaApplication::class, qualifiers = "w412dp-h869dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TodayTakeAllTest {
    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)


    private fun at(hour: Int): Long = LocalDate.now().atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private data class Due(val medicineId: Long, val eventId: Long)

    /** A medicine with 10 in stock and one dose due today at [hour]. */
    private fun due(name: String, hour: Int): Due {
        val m = h.medicine(name, stock = 10.0)
        val r = h.reminder(m, ReminderConfig.FixedTime(hour, 0, 1.0))
        return Due(m, runBlocking { checkNotNull(h.container.doseEventRepository.materializeIfAbsent(r, m, at(hour), 1.0)) })
    }

    private fun showToday() {
        val c = h.container
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
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Levothyroxine", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun status(d: Due) = h.doseEvent(d.eventId).status
    private fun stock(d: Due) = h.medicineRow(d.medicineId).stockInfo.quantity

    /** Room writes run on Dispatchers.IO, outside the test's clock: retry the
     *  assertions until they hold or the time is up, then fail with the last. */
    private fun eventually(seconds: Int = 10, check: () -> Unit) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (true) {
            try { check(); return } catch (e: AssertionError) { if (System.nanoTime() > deadline) throw e }
            Thread.sleep(50)
            compose.waitForIdle()
        }
    }

    private fun seedThreeAndOne(): Pair<Due, List<Due>> {
        val morning = due("Levothyroxine", 8)
        return morning to listOf(due("Atorvastatin", 11), due("Metformin", 11), due("Ramipril", 11))
    }

    @Test
    fun `the button shows only on the time with two or more due, and its sheet lists exactly those`() {
        seedThreeAndOne()
        showToday()
        compose.onAllNodes(hasText("Take all", substring = true)).assertCountEquals(1)
        compose.onNodeWithText("Take all (3)").performScrollTo().performClick()
        compose.onNodeWithText("Take these doses?").assertExists()
        // Each 11:00 name is on its row and in the sheet; the 08:00 one only on its row.
        for (name in listOf("Atorvastatin", "Metformin", "Ramipril")) {
            compose.onAllNodes(hasText(name, substring = true)).assertCountEquals(2)
        }
        compose.onAllNodes(hasText("Levothyroxine", substring = true)).assertCountEquals(1)
        compose.onNodeWithText("Take 3 doses").assertExists()
    }

    @Test
    fun `confirming records the three as Taken with their stock, and leaves the fourth due`() {
        val (morning, eleven) = seedThreeAndOne()
        showToday()
        compose.onNodeWithText("Take all (3)").performScrollTo().performClick()
        compose.onNode(hasText("Take 3 doses") and hasClickAction()).performClick()
        eventually {
            assertEquals(List(3) { DoseStatus.TAKEN }, eleven.map(::status))
            assertEquals(List(3) { 9.0 }, eleven.map(::stock))
        }
        assertEquals(DoseStatus.PENDING, status(morning))
        assertEquals(10.0, stock(morning), 0.0)
        eventually { compose.onAllNodes(hasText("Take all", substring = true)).assertCountEquals(0) }
    }

    @Test
    fun `cancelling the sheet records nothing`() {
        val (morning, eleven) = seedThreeAndOne()
        showToday()
        compose.onNodeWithText("Take all (3)").performScrollTo().performClick()
        compose.onNodeWithText("Take these doses?").assertExists()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitForIdle()
        Thread.sleep(500) // a write, had one been sent, lands on Dispatchers.IO
        compose.waitForIdle()
        assertEquals(List(4) { DoseStatus.PENDING }, (eleven + morning).map(::status))
        assertEquals(List(4) { 10.0 }, (eleven + morning).map(::stock))
        compose.onAllNodes(hasText("Take these doses?")).assertCountEquals(0)
    }

    @Test
    fun `with one dose due at each time there is no button`() {
        due("Levothyroxine", 8)
        due("Atorvastatin", 11)
        showToday()
        compose.onAllNodes(hasText("Take all", substring = true)).assertCountEquals(0)
    }
}
