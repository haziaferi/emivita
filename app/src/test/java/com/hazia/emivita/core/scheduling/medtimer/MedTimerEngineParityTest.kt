package com.hazia.emivita.core.scheduling.medtimer

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.scheduling.AlarmScheduler
import com.hazia.emivita.core.scheduling.NextOccurrence
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.RealSchedulingCoordinator
import com.hazia.emivita.core.scheduling.SchedulingEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The medTimer scheduler cases that need dose history or the arming path,
 * ported onto [SchedulingEngine] and [RealSchedulingCoordinator] (spec §7
 * R60, Appendix E). Same calendar and conventions as
 * [MedTimerCalculatorParityTest]; see its header for the three outcomes.
 */
class MedTimerEngineParityTest {

    private val zone: ZoneId = ZoneId.of("Z")
    private fun on(day: Long, minutes: Long): Long = ((day - 1) * 86_400 + minutes * 60) * 1_000

    private fun assertAt(expected: Long, actual: NextOccurrence?) {
        assertEquals(
            Instant.ofEpochMilli(expected).atZone(zone).toLocalDateTime(),
            actual?.epochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() },
        )
    }

    private fun fakeDao(
        lastResolved: Map<Long, DoseEvent> = emptyMap(),
        mostRecent: Map<Long, DoseEvent> = emptyMap(),
    ): DoseEventDao = mockk<DoseEventDao>().also { dao ->
        coEvery { dao.getLastResolved(any()) } answers { lastResolved[firstArg<Long>()] }
        coEvery { dao.getMostRecentForReminder(any()) } answers { mostRecent[firstArg<Long>()] }
    }

    private fun fakeSettings(): SettingsRepository =
        mockk<SettingsRepository>().also { every { it.weekendModeEnabled } returns flowOf(false) }

    private fun engine(dao: DoseEventDao = fakeDao(), now: Long = on(1, 0)) = SchedulingEngine(
        doseEventDao = dao,
        medicineDao = mockk<MedicineDao>().also { coEvery { it.getById(any()) } returns null },
        settingsRepository = fakeSettings(),
        zone = zone,
        nowEpochMillis = { now },
    )

    private fun event(reminderId: Long, scheduled: Long, resolvedAt: Long? = null) = DoseEvent(
        reminderId = reminderId,
        medicineId = 1L,
        scheduledEpochMillis = scheduled,
        doseAmount = 1.0,
        status = if (resolvedAt == null) DoseStatus.PENDING else DoseStatus.TAKEN,
        resolvedAtEpochMillis = resolvedAt,
        createdAtEpochMillis = 0L,
    )

    private fun reminder(
        id: Long,
        config: ReminderConfig,
        type: ReminderType,
        parentReminderId: Long? = null,
        activeFromEpochDay: Long? = null,
        activeUntilEpochDay: Long? = null,
    ) = Reminder(
        id = id,
        medicineId = 1L,
        parentReminderId = parentReminderId,
        type = type,
        configJson = encodeReminderConfig(config),
        activeFromEpochDay = activeFromEpochDay,
        activeUntilEpochDay = activeUntilEpochDay,
        createdAtEpochMillis = 0L,
    )

    private fun interval(minutes: Int, anchor: Long, start: IntervalStartBehavior) =
        ReminderConfig.RepeatingInterval(intervalMinutes = minutes, dose = 1.0, startBehavior = start, anchorEpochMillis = anchor)

    private fun window(minutes: Int, start: LocalTime, end: LocalTime, behavior: IntervalStartBehavior) =
        ReminderConfig.DailyWindow(
            intervalMinutes = minutes, dose = 1.0,
            windowStartHour = start.hour, windowStartMinute = start.minute,
            windowEndHour = end.hour, windowEndMinute = end.minute,
            startBehavior = behavior,
        )

    // ---- ReminderSchedulerIntervalUnitTest / WindowedIntervalUnitTest --------

    @Test
    fun `intervalStartsFromProcessedDoesNotLoopOnUnprocessedEvent`() = runTest {
        // medTimer: "An unprocessed event at the next interval must block
        // re-scheduling, otherwise the notification loop would fire
        // continuously." Emivita anchored on getLastResolved -- which skips
        // PENDING -- and never asked whether a dose was outstanding, so
        // rescheduleAll() at boot, or any reminder edit, armed a second alarm
        // at prevResolved + k*interval and materialised a second PENDING dose
        // (R60 finding). Now: an outstanding dose blocks the alarm path.
        val r = reminder(1L, interval(60, on(1, 0), IntervalStartBehavior.ON_RESOLVED), ReminderType.REPEATING_INTERVAL)
        val processed = event(1L, on(1, 120), resolvedAt = on(1, 120))
        val engineAfterProcessed = engine(fakeDao(lastResolved = mapOf(1L to processed), mostRecent = mapOf(1L to processed)))
        assertAt(on(1, 180), engineAfterProcessed.nextOccurrenceFor(r, on(1, 121)))

        val unprocessed = event(1L, on(1, 180))
        val engineWithOutstanding = engine(fakeDao(lastResolved = mapOf(1L to processed), mostRecent = mapOf(1L to unprocessed)))
        assertNull(engineWithOutstanding.nextOccurrenceFor(r, on(1, 181)))
    }

    @Test
    fun `windowedIntervalStartsFromProcessedDoesNotLoopOnUnprocessedEvent`() = runTest {
        val r = reminder(
            1L, window(60, LocalTime.of(0, 0), LocalTime.of(23, 59), IntervalStartBehavior.ON_RESOLVED), ReminderType.DAILY_WINDOW,
        )
        val processed = event(1L, on(1, 120), resolvedAt = on(1, 120))
        assertAt(on(1, 180), engine(fakeDao(mapOf(1L to processed), mapOf(1L to processed))).nextOccurrenceFor(r, on(1, 121)))
        val unprocessed = event(1L, on(1, 180))
        assertNull(engine(fakeDao(mapOf(1L to processed), mapOf(1L to unprocessed))).nextOccurrenceFor(r, on(1, 181)))
    }

    @Test
    fun `an outstanding dose does not blank the projection -- it is assumed resolved on time`() = runTest {
        // medTimer's SchedulingSimulator treats an unprocessed event as
        // processed at its reminded time (LastEventsPerReminder). Same here:
        // the alarm path blocks, the projection walk carries on from the
        // pending dose's slot, so Today's "upcoming" and the stock run-out
        // date do not jump while a dose sits unanswered.
        val r = reminder(1L, interval(60, on(1, 0), IntervalStartBehavior.ON_RESOLVED), ReminderType.REPEATING_INTERVAL)
        val processed = event(1L, on(1, 120), resolvedAt = on(1, 120))
        val unprocessed = event(1L, on(1, 180))
        val walk = engine(fakeDao(mapOf(1L to processed), mapOf(1L to unprocessed)))
            .occurrencesFor(r, on(1, 181), on(1, 400))
        assertEquals(listOf(on(1, 240), on(1, 300), on(1, 360)), walk.map { it.epochMillis })
    }

    // ---- ReminderSchedulerLinkedUnitTest ------------------------------------

    @Test
    fun `scheduleLinkedReminder -- child follows the parent's resolution, once`() = runTest {
        // medTimer: parent 08:00 raised, not processed -> no child; processed
        // 08:01 -> child 09:01; child's own event processed -> no child until the
        // parent is processed again (day 2 08:04 -> 09:04).
        val child = reminder(
            2L, ReminderConfig.LinkedFollowing(delayMinutes = 60, dose = 2.0, startBehavior = IntervalStartBehavior.ON_RESOLVED),
            ReminderType.LINKED_FOLLOWING, parentReminderId = 1L,
        )
        val raisedOnly = event(1L, on(1, 480))
        assertNull(engine(fakeDao(mostRecent = mapOf(1L to raisedOnly))).nextOccurrenceFor(child, on(1, 481)))

        val processed = event(1L, on(1, 480), resolvedAt = on(1, 481))
        assertAt(on(1, 541), engine(fakeDao(mapOf(1L to processed), mapOf(1L to processed))).nextOccurrenceFor(child, on(1, 481)))
        // after the child's own slot has passed there is nothing until the parent resolves again
        assertNull(engine(fakeDao(mapOf(1L to processed), mapOf(1L to processed))).nextOccurrenceFor(child, on(1, 543)))
        val processedDay2 = event(1L, on(2, 480), resolvedAt = on(2, 484))
        assertAt(on(2, 544), engine(fakeDao(mapOf(1L to processedDay2), mapOf(1L to processedDay2))).nextOccurrenceFor(child, on(2, 484)))
    }

    @Test
    fun `scheduleTwoLinkedReminders -- a grandchild waits for its own parent, DESIGN divergence`() = runTest {
        // medTimer computes the whole chain in one pass from the grandparent's
        // event: child at 09:01, grandchild at 10:02. Emivita's cascade is one
        // level per event (ReminderAlarmReceiver / RealSchedulingCoordinator):
        // the grandchild is armed only once the child fires or resolves, so
        // with the child unfired it has no next occurrence yet. Appendix E row
        // A-23 -- same 24 h cap and unbounded depth on both sides.
        val child = reminder(
            2L, ReminderConfig.LinkedFollowing(delayMinutes = 60, dose = 2.0, startBehavior = IntervalStartBehavior.ON_RESOLVED),
            ReminderType.LINKED_FOLLOWING, parentReminderId = 1L,
        )
        val grandchild = reminder(
            3L, ReminderConfig.LinkedFollowing(delayMinutes = 60, dose = 2.0, startBehavior = IntervalStartBehavior.ON_RESOLVED),
            ReminderType.LINKED_FOLLOWING, parentReminderId = 2L,
        )
        val parentProcessed = event(1L, on(1, 480), resolvedAt = on(1, 481))
        val e = engine(fakeDao(mapOf(1L to parentProcessed), mapOf(1L to parentProcessed)))
        assertAt(on(1, 541), e.nextOccurrenceFor(child, on(1, 481)))
        assertNull(e.nextOccurrenceFor(grandchild, on(1, 481)))
        // once the child has resolved, the grandchild follows it
        val childProcessed = event(2L, on(1, 541), resolvedAt = on(1, 542))
        val e2 = engine(fakeDao(mapOf(1L to parentProcessed, 2L to childProcessed), mapOf(1L to parentProcessed, 2L to childProcessed)))
        assertAt(on(1, 602), e2.nextOccurrenceFor(grandchild, on(1, 542)))
    }

    // ---- ReminderSchedulerActivePeriodUnitTest ------------------------------

    @Test
    fun `scheduleActiveInterval -- an interval clamped to a period keeps its grid`() = runTest {
        // medTimer: 8 h from 00:01 day 1, period epochDay 3..4 -> day 4 00:01, day 5 00:01, then nothing
        val r = reminder(
            1L, interval(480, on(1, 1), IntervalStartBehavior.ON_REMINDED), ReminderType.REPEATING_INTERVAL,
            activeFromEpochDay = 3, activeUntilEpochDay = 4,
        )
        assertAt(on(4, 1), engine().nextOccurrenceFor(r, on(1, 0)))
        assertAt(on(5, 1), engine().nextOccurrenceFor(r, on(5, 0)))
        assertNull(engine().nextOccurrenceFor(r, on(6, 0)))
    }

    @Test
    fun `scheduleActiveWindowedInterval -- a window clamped to a period`() = runTest {
        val r = reminder(
            1L, window(480, LocalTime.of(2, 0), LocalTime.of(11, 40), IntervalStartBehavior.ON_REMINDED), ReminderType.DAILY_WINDOW,
            activeFromEpochDay = 3, activeUntilEpochDay = 4,
        )
        assertAt(on(4, 120), engine().nextOccurrenceFor(r, on(1, 0)))
        assertAt(on(5, 120), engine().nextOccurrenceFor(r, on(5, 0)))
        assertNull(engine().nextOccurrenceFor(r, on(6, 0)))
    }

    // ---- The arming path: medTimer's `raisedToday` / `adjustToToday` guards -

    @Test
    fun `a late-delivered alarm arms today's next slot, not a replay of every missed one`() = runTest {
        // medTimer bounds catch-up to today (StandardScheduling `raisedToday`,
        // IntervalScheduling `adjustToToday`) and re-bases on TIME_SET. Emivita's
        // receiver rescheduled from `scheduled + 1 min` with no floor, and
        // AlarmManager fires a past instant immediately, so a clock set forward
        // three days replayed every missed slot as a notification chain (R60
        // finding). The floor lives in the coordinator so every caller gets it.
        val r = reminder(1L, ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0), ReminderType.FIXED_TIME)
        val reminderDao = mockk<ReminderDao>().also { coEvery { it.getById(1L) } returns r }
        val alarms = mockk<AlarmScheduler>(relaxed = true)
        val cache = mockk<OccurrenceProjectionCache>(relaxed = true)
        val now = on(4, 600) // day 4, 10:00 -- the alarm for day 1 08:00 was delivered three days late
        val coordinator = RealSchedulingCoordinator(reminderDao, engine(now = now), alarms, cache, nowEpochMillis = { now })

        coordinator.onReminderChanged(1L, on(1, 481))

        coVerify(exactly = 1) { alarms.schedule(1L, on(5, 480)) }
    }
}
