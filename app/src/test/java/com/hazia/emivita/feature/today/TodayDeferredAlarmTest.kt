package com.hazia.emivita.feature.today

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import com.hazia.emivita.ui.theme.EmivitaTheme
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * W4 of the R139 walk (§7 R140): on the 9 Pro, OxygenOS held a 00:05 alarm to
 * 00:11, and for those six minutes Today showed no dose at all. The alarm was
 * armed for the slot, so the dose was still owed; a projection pass after
 * 00:05 walked from "now" and dropped it. Real container, real clock: the slot
 * is a few minutes back, its alarm armed and not fired, no dose row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestEmivitaApplication::class, qualifiers = "w412dp-h869dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TodayDeferredAlarmTest {
    val compose = createComposeRule()

    private val h = ReceiverHarness()

    @get:Rule
    val rules: TestRule = closedAfterCompose(h, compose)

    private val zone: ZoneId = ZoneId.systemDefault()

    /** Today's minute [minutesAgo] back; skipped just after midnight, when it would be yesterday's. */
    private fun slotMinutesAgo(minutesAgo: Long): LocalDateTime {
        val at = LocalDateTime.now(zone).minusMinutes(minutesAgo).withSecond(0).withNano(0)
        assumeTrue("the slot would fall yesterday", at.toLocalDate() == LocalDate.now(zone))
        return at
    }

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

    /** A whole pass that started after [sinceEpochMillis]: what Today is about to read. */
    private fun passSettledSince(sinceEpochMillis: Long) = eventually {
        val state = h.container.occurrenceProjectionCache.state.value
        assertTrue(state.computedAtEpochMillis >= sinceEpochMillis && !state.inProgress)
    }

    @Test
    fun `a slot past its time whose alarm the phone is holding stays on Today, due`() {
        val slot = slotMinutesAgo(3)
        val metformin = h.medicine("Metformin")
        val reminderId = h.reminder(metformin, ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0))
        // Armed for the slot while it was still ahead, as at 23:55 on the 9 Pro.
        h.container.alarmScheduler.schedule(reminderId, slot.atZone(zone).toInstant().toEpochMilli())
        // A pass after the slot: returning to Today, or any write, starts one.
        val since = System.currentTimeMillis()
        h.container.occurrenceProjectionCache.invalidate()
        passSettledSince(since)
        showToday()
        eventually { compose.onNodeWithText("Metformin").assertExists() }
        eventually { compose.onNodeWithText("1 due now", substring = true).assertExists() }
        assertTrue("no dose was posted: the alarm has not fired", h.doseEvents(reminderId).isEmpty())
    }

    @Test
    fun `a slot whose alarm has moved on to tomorrow is not brought back`() {
        // A reminder made after today's time arms tomorrow: today's slot was never owed.
        val slot = slotMinutesAgo(3)
        val metformin = h.medicine("Metformin")
        h.reminder(metformin, ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0))
        val since = System.currentTimeMillis()
        h.container.occurrenceProjectionCache.invalidate()
        passSettledSince(since)
        showToday()
        eventually { compose.onNodeWithText("Nothing scheduled yet", substring = true).assertExists() }
        compose.onNodeWithText("Metformin").assertDoesNotExist()
    }
}
