package com.hazia.emivita.feature.today

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.scheduling.receivers.ReceiverHarness
import com.hazia.emivita.core.scheduling.receivers.TestEmivitaApplication
import com.hazia.emivita.core.scheduling.receivers.closedAfterCompose
import com.hazia.emivita.ui.theme.EmivitaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    /** Today's minute [minutesAgo] back, or today's first minute when that would be yesterday's, so no hour skips the test. */
    private fun slotMinutesAgo(minutesAgo: Long): LocalDateTime {
        val now = LocalDateTime.now(zone)
        return maxOf(now.minusMinutes(minutesAgo), now.toLocalDate().atStartOfDay()).withSecond(0).withNano(0)
    }

    /** The minute [minutesAgo] back, for a test that needs today from then to [aheadMinutes] on; skipped when either end is not today. */
    private fun spanOfToday(minutesAgo: Long, aheadMinutes: Long = 0): LocalDateTime {
        val now = LocalDateTime.now(zone)
        val at = now.minusMinutes(minutesAgo).withSecond(0).withNano(0)
        assumeTrue("the span would leave today", at.toLocalDate() == now.toLocalDate() && now.plusMinutes(aheadMinutes).toLocalDate() == now.toLocalDate())
        return at
    }

    private fun epochOf(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    private fun showToday(nowEpochMillis: () -> Long = System::currentTimeMillis) {
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
                    nowEpochMillis = nowEpochMillis,
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

    /** A reminder at [slot] whose alarm was armed for it and has not fired: the phone is holding it. */
    private fun heldReminder(name: String, slot: LocalDateTime): Long {
        val reminderId = h.reminder(h.medicine(name), ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0))
        h.container.alarmScheduler.schedule(reminderId, slot.atZone(zone).toInstant().toEpochMilli())
        return reminderId
    }

    private fun showTodayAfterAPass(nowEpochMillis: () -> Long = System::currentTimeMillis) {
        val since = System.currentTimeMillis()
        h.container.occurrenceProjectionCache.invalidate()
        passSettledSince(since)
        showToday(nowEpochMillis)
    }

    private fun due(name: String) = hasText(name) and hasStateDescription("Due")

    /** An interval counted from the answer (every [minutes] "when taken or skipped"), whose alarm for [fired] has rung
     *  and left its dose unanswered: it is not armed again until that dose is answered. */
    private fun firedWhenTakenReminder(name: String, fired: LocalDateTime, minutes: Int): Long {
        val at = epochOf(fired)
        val reminderId = h.reminder(h.medicine(name), ReminderConfig.RepeatingInterval(minutes, 1.0, IntervalStartBehavior.ON_RESOLVED, at))
        h.container.alarmScheduler.schedule(reminderId, at)
        h.fire(h.regularAlarms(reminderId).single { h.scheduledAtOf(it) == at })
        return reminderId
    }

    @Test
    fun `a "when taken" dose left unanswered is due once, not again at each interval since`() {
        // §7 R143 (C1 of the PR #62 review): the alarm stays armed for the slot that rang, the pass walked on from it
        // taking the open dose as answered there, and the slot an interval later read as a second dose due, with
        // Take and Skip on a dose no alarm would post.
        val fired = spanOfToday(35)
        firedWhenTakenReminder("Metformin", fired, minutes = 30)
        showTodayAfterAPass()
        eventually { compose.onNode(due("Metformin")).assertExists() }
        settle()
        compose.onAllNodes(due("Metformin")).assertCountEquals(1)
        compose.onNodeWithText("1 due now", substring = true).assertExists()
    }

    @Test
    fun `a "when taken" slot still ahead at the last pass does not turn due when its time comes`() {
        // §7 R143: no alarm is armed for it while the open dose waits, so passing its time leaves it a guess; the
        // minute's tick read it as one the phone is holding.
        val fired = spanOfToday(1, aheadMinutes = 12)
        firedWhenTakenReminder("Metformin", fired, minutes = 10)
        showTodayAfterAPass { System.currentTimeMillis() + 12 * 60_000L }
        eventually { compose.onNode(due("Metformin")).assertExists() }
        settle()
        compose.onAllNodes(due("Metformin")).assertCountEquals(1)
    }

    @Test
    fun `an armed slot turns due while Today stays open`() {
        // §7 R142's claim, enforced once the rows' clock could be set: the minute's tick turns the row.
        val slot = LocalDateTime.now(zone).plusMinutes(2).withSecond(0).withNano(0)
        spanOfToday(0, aheadMinutes = 3)
        h.reminder(h.medicine("Metformin"), ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0))
        var clock = System.currentTimeMillis()
        showTodayAfterAPass { clock }
        eventually { compose.onNode(hasText("Metformin") and hasStateDescription("Upcoming")).assertExists() }
        clock = epochOf(slot) + 30_000L
        compose.mainClock.advanceTimeBy(61_000)
        eventually { compose.onNode(due("Metformin")).assertExists() }
    }

    @Test
    fun `a held slot of a reminder that answers itself does not read due`() {
        // M7 of the review: an autoResolve reminder (another person's medicine, tracked silently) records its dose
        // taken when the alarm arrives, so it is never due, and its held slot is not either.
        val slot = slotMinutesAgo(3)
        val reminderId = runBlocking {
            h.container.reminderRepository.create(
                medicineId = h.medicine("Metformin"), groupId = null, type = ReminderType.FIXED_TIME,
                config = ReminderConfig.FixedTime(slot.hour, slot.minute, 1.0), autoResolve = true,
            )
        }
        h.container.alarmScheduler.schedule(reminderId, epochOf(slot))
        showTodayAfterAPass()
        eventually { compose.onNode(hasText("Metformin") and hasStateDescription("Upcoming")).assertExists() }
        compose.onNodeWithText("due now", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a held slot's row is a due one, and Skip answers that slot before its alarm arrives`() {
        // §7 R142, the owner's pick A: the line counted it as due while its row read "Upcoming" with Take now only.
        val slot = slotMinutesAgo(3)
        val reminderId = heldReminder("Metformin", slot)
        showTodayAfterAPass()
        eventually { compose.onNode(hasText("Metformin") and hasStateDescription("Due")).assertExists() }
        compose.onNodeWithText("Skip").performClick()
        eventually {
            val answered = h.doseEvents(reminderId).single()
            assertEquals(DoseStatus.SKIPPED, answered.status)
            assertEquals(slot.atZone(zone).toInstant().toEpochMilli(), answered.scheduledEpochMillis)
        }
    }

    @Test
    fun `two held slots at one time offer Take all, as two due doses do`() {
        val slot = slotMinutesAgo(3)
        val metformin = heldReminder("Metformin", slot)
        val ramipril = heldReminder("Ramipril", slot)
        showTodayAfterAPass()
        eventually { compose.onNodeWithText("Take all (2)").assertExists() }
        compose.onNodeWithText("Take all (2)").performClick()
        settle()
        compose.onNodeWithText("Take 2 doses").performClick()
        eventually {
            assertEquals(listOf(DoseStatus.TAKEN), h.doseEvents(metformin).map { it.status })
            assertEquals(listOf(DoseStatus.TAKEN), h.doseEvents(ramipril).map { it.status })
        }
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
