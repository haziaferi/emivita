package com.hazia.emivita.feature.today

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import com.hazia.emivita.core.scheduling.ProjectedOccurrence
import com.hazia.emivita.core.scheduling.ProjectionState
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import com.hazia.emivita.feature.alarm.TakeAmountActivity
import com.hazia.emivita.ui.theme.EmivitaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * A reminder that asks the amount (§7 R109, B-45) asks it from Today's own
 * Take too (§7 R113): the row's Take and a projected slot's Take now open
 * [TakeAmountActivity] on the dose, materialised first when it is still only
 * projected, and record nothing themselves, so the dose stays due until the
 * amount is typed. Only "Take all" (R110) and the notification read the flag before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class, qualifiers = "w412dp-h869dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TodayAsksAmountTest {
    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)

    private fun at(hour: Int): Long = LocalDate.now().atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun deps() = TodayDeps(h.container.doseEventRepository, false, h.container.reminderRepository, h.container.notificationBuilder)

    /** The next activity started, from any context: Robolectric records them in one place. */
    private fun nextStarted(): Intent? = shadowOf(h.context as Application).nextStartedActivity

    private fun eventually(seconds: Int = 10, check: () -> Unit) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (true) {
            try { check(); return } catch (e: AssertionError) { if (System.nanoTime() > deadline) throw e }
            Thread.sleep(50)
            compose.waitForIdle()
        }
    }

    private fun assertOpensTakeAmount(doseId: Long) {
        var started: Intent? = null
        eventually { started = started ?: nextStarted(); assertNotNull("Take opened no screen", started) }
        assertEquals(TakeAmountActivity::class.java.name, started!!.component?.className)
        assertEquals(doseId, started!!.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L))
    }

    @Test
    fun `a due dose that asks the amount opens the amount screen from its Take and stays due`() {
        val m = h.medicine("Metformin", stock = 10.0)
        val r = h.reminder(m, ReminderConfig.FixedTime(8, 0, 1.0), variableAmount = true)
        val id = runBlocking { checkNotNull(h.container.doseEventRepository.materializeIfAbsent(r, m, at(8), 1.0)) }
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
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Take") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Take") and hasClickAction()).performClick()
        assertOpensTakeAmount(id)
        Thread.sleep(300) // a write, had one been sent, lands on Dispatchers.IO
        assertEquals(DoseStatus.PENDING, h.doseEvent(id).status)
        assertEquals(10.0, h.medicineRow(m).stockInfo.quantity, 0.0)
    }

    @Test
    fun `Take now on a projected slot that asks the amount materialises it, opens the amount screen, and records nothing`() {
        val m = h.medicine("Metformin", stock = 10.0)
        val r = h.reminder(m, ReminderConfig.FixedTime(20, 0, 1.0), variableAmount = true)
        val slot = at(20)
        val entry = TimelineEntry(
            medicine = h.medicineRow(m), doseAmount = 1.0, status = TimelineStatus.UPCOMING,
            doseEventId = null, resolvedAtEpochMillis = null, reminderId = r, asksAmount = true,
        )
        compose.setContent { EmivitaTheme { TimelineNodeItem(TimelineNode(slot, listOf(entry)), deps()) } }
        compose.onNode(hasText("Take now") and hasClickAction()).performClick()
        var started: Intent? = null
        eventually { started = started ?: nextStarted(); assertNotNull("Take now opened no screen", started) }
        val event = h.doseEvents(r).single()
        assertEquals(slot, event.scheduledEpochMillis)
        assertEquals(TakeAmountActivity::class.java.name, started!!.component?.className)
        assertEquals(event.id, started!!.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L))
        assertEquals(DoseStatus.PENDING, event.status)
        assertEquals(10.0, h.medicineRow(m).stockInfo.quantity, 0.0)
    }

    @Test
    fun `a projected slot carries its reminder's asks-the-amount flag`() {
        val m = h.medicine("Metformin")
        val r = h.reminder(m, ReminderConfig.FixedTime(20, 0, 1.0), variableAmount = true)
        val reminder = runBlocking { checkNotNull(h.container.reminderRepository.getById(r)) }
        val start = at(0)
        val nodes = buildTimeline(
            medicinesById = mapOf(m to h.medicineRow(m)),
            materialized = emptyList(),
            projection = ProjectionState(occurrences = listOf(ProjectedOccurrence(reminderId = r, medicineId = m, epochMillis = at(20), doseAmount = 1.0))),
            startOfToday = start,
            startOfTomorrow = start + 24 * 3_600_000L,
            remindersById = mapOf(r to reminder),
        )
        assertTrue("the projected entry should ask the amount", nodes.single().entries.single().asksAmount)
    }
}
