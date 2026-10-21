package com.hazia.emivita.feature.today

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.settings.TodayLayout
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.ui.theme.EmivitaTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * A tap on a dose still to come opens its sheet (§7 R107, census D-55, R106's
 * pick V1): the reminder's instructions and the medicine's notes, with Take
 * now and Skip answering the slot ahead of time through the repository.
 *
 * The rows are Today's own ([todayItems] over [buildTimeline]) fed by the real
 * projection cache and the real dose events, for tomorrow's day: the cache
 * projects from now, so a slot today could already be past when the test runs,
 * and tomorrow's 09:00 is always still to come.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UpcomingDoseSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val h = ReceiverHarness()
    private val c = h.container
    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.now(zone).plusDays(1)
    private val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val slot = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    private val instructions = "After dinner"
    private val notes = "Avoid grapefruit juice."
    private var medicine = 0L
    private var reminder = 0L

    @Before
    fun seed() = runBlocking {
        medicine = h.medicine("Atorvastatin", stock = 10.0)
        c.medicineRepository.update(h.medicineRow(medicine).copy(notes = notes))
        reminder = c.reminderRepository.create(
            medicineId = medicine, groupId = null, type = ReminderType.FIXED_TIME,
            config = ReminderConfig.FixedTime(9, 0, 1.0), instructions = instructions,
        )
        c.occurrenceProjectionCache.requestWindow(CONSUMER, 2)
    }

    @After
    fun tearDown() {
        c.occurrenceProjectionCache.releaseWindow(CONSUMER)
        h.close()
    }

    @Composable
    private fun Day() {
        val projection by c.occurrenceProjectionCache.state.collectAsState()
        val events by c.doseEventRepository.observeInRange(h.profileId, start, end).collectAsState(initial = null)
        val medicines by c.medicineRepository.observeWithRemindersForProfile(h.profileId).collectAsState(initial = null)
        val byId = medicines?.associate { it.medicine.id to it.medicine }
        val reminders = medicines.orEmpty().flatMap { it.reminders }.associateBy { it.id }
        val deps = remember { TodayDeps(c.doseEventRepository, false, c.reminderRepository, c.notificationBuilder) }
        val nodes = buildTimeline(byId, events, projection, start, end, reminders)
        LazyColumn { todayItems(TodayLayout.TIMELINE, nodes, deps, zone) }
    }

    private fun show() {
        compose.setContent { EmivitaTheme { Day() } }
        compose.waitUntil(10_000) { compose.onAllNodes(upcomingRow).fetchSemanticsNodes().isNotEmpty() }
    }

    private val upcomingRow = hasStateDescription("Upcoming") and hasText("Atorvastatin", substring = true)

    private fun projected() = c.occurrenceProjectionCache.state.value.occurrences.any { it.reminderId == reminder && it.epochMillis == slot }

    private fun eventually(seconds: Int = 10, check: () -> Unit) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (true) {
            try { check(); return } catch (e: AssertionError) { if (System.nanoTime() > deadline) throw e }
            Thread.sleep(50)
            compose.waitForIdle()
        }
    }

    @Test
    fun `tapping an upcoming row opens its sheet with the instructions and the medicine's note`() {
        show()
        compose.onNode(upcomingRow).performClick()
        compose.onNodeWithText("Instructions").assertExists()
        // The instructions are on the row and in the sheet; the note only in the sheet.
        compose.onAllNodes(hasText(instructions, substring = true)).assertCountEquals(2)
        compose.onNodeWithText(notes).assertExists()
        assertTrue("nothing is recorded by opening the sheet", h.doseEvents(reminder).isEmpty())
    }

    @Test
    fun `Take now in the sheet records one Taken for the slot, and the slot is no longer upcoming`() {
        show()
        assertTrue(projected())
        compose.onNode(upcomingRow).performClick()
        // The sheet's chip, not the row's own Take now.
        compose.onNode(hasText("Take now") and hasClickAction() and !hasAnyAncestor(hasStateDescription("Upcoming"))).performClick()
        eventually {
            val events = h.doseEvents(reminder)
            assertEquals(1, events.size)
            assertEquals(DoseStatus.TAKEN, events.single().status)
            assertEquals(slot, events.single().scheduledEpochMillis)
            assertEquals(9.0, h.medicineRow(medicine).stockInfo.quantity, 0.0)
        }
        eventually {
            assertFalse("the projection still holds the answered slot (R104 A-38)", projected())
            compose.onAllNodes(upcomingRow).assertCountEquals(0)
            compose.onAllNodes(hasStateDescription("Taken") and hasText("Atorvastatin", substring = true)).assertCountEquals(1)
        }
        compose.onAllNodes(hasText("Instructions")).assertCountEquals(0)
    }

    @Test
    fun `Skip in the sheet records Skipped for the slot and leaves the stock`() {
        show()
        compose.onNode(upcomingRow).performClick()
        compose.onNode(hasText("Skip") and hasClickAction()).performClick()
        eventually {
            val events = h.doseEvents(reminder)
            assertEquals(1, events.size)
            assertEquals(DoseStatus.SKIPPED, events.single().status)
            assertEquals(slot, events.single().scheduledEpochMillis)
        }
        assertEquals(10.0, h.medicineRow(medicine).stockInfo.quantity, 0.0)
        eventually { assertFalse("the projection still holds the answered slot (R104 A-38)", projected()) }
    }

    private companion object {
        const val CONSUMER = "upcoming-dose-sheet-test"
    }
}
