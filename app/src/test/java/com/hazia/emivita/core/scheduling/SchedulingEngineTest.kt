package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Coverage for the layer that joins the pure math in ReminderScheduling.kt to
 * real reminder and dose-event data: type dispatch, the anchor lookups that
 * only the engine can do, the activeFrom/activeUntil clamp, where weekend mode
 * sits in the order of operations, and the projection walk.
 *
 * The pure calculators themselves are covered in [ReminderSchedulingTest] --
 * the cases here are about what the engine adds on top, so each one is chosen
 * to fail if a wire is crossed rather than to re-check the arithmetic.
 *
 * Same fixed zone and calendar as that suite: 2026-06-01 is a Monday, so
 * 06-06 is a Saturday and 06-07 a Sunday.
 */
class SchedulingEngineTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")

    private fun at(isoLocal: String): Long =
        LocalDateTime.parse(isoLocal).atZone(zone).toInstant().toEpochMilli()

    private fun Long.asLocal(): LocalDateTime =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

    private fun epochDay(isoDate: String): Long = LocalDate.parse(isoDate).toEpochDay()

    private fun assertOccursAt(expectedIsoLocal: String, actual: NextOccurrence?) {
        assertEquals(LocalDateTime.parse(expectedIsoLocal), actual?.epochMillis?.asLocal())
    }

    // ---- Fakes -------------------------------------------------------------

    /** Dose history keyed by reminder id, so a LinkedFollowing test can give
     *  the parent a history and the child none. */
    private fun fakeDao(
        lastResolved: Map<Long, DoseEvent> = emptyMap(),
        mostRecent: Map<Long, DoseEvent> = emptyMap(),
    ): DoseEventDao = mockk<DoseEventDao>().also { dao ->
        coEvery { dao.getLastResolved(any()) } answers { lastResolved[firstArg<Long>()] }
        coEvery { dao.getMostRecentForReminder(any()) } answers { mostRecent[firstArg<Long>()] }
    }

    private fun fakeSettings(mode: WeekendMode = WeekendMode.Off): SettingsRepository =
        mockk<SettingsRepository>().also { settings ->
            when (mode) {
                is WeekendMode.Off -> every { settings.weekendModeEnabled } returns flowOf(false)
                is WeekendMode.On -> {
                    every { settings.weekendModeEnabled } returns flowOf(true)
                    every { settings.weekendDays } returns flowOf(mode.days)
                    every { settings.weekendStartTime } returns flowOf(mode.start)
                    every { settings.weekendEndTime } returns flowOf(mode.end)
                }
            }
        }

    private fun engine(
        dao: DoseEventDao = fakeDao(),
        settings: SettingsRepository = fakeSettings(),
    ) = SchedulingEngine(
        doseEventDao = dao,
        settingsRepository = settings,
        zone = zone,
        nowEpochMillis = { at("2026-06-01T00:00") },
    )

    private fun doseEvent(
        scheduledEpochMillis: Long = 0L,
        resolvedAtEpochMillis: Long? = null,
    ) = DoseEvent(
        reminderId = PARENT_ID,
        medicineId = 1L,
        scheduledEpochMillis = scheduledEpochMillis,
        doseAmount = 1.0,
        resolvedAtEpochMillis = resolvedAtEpochMillis,
        createdAtEpochMillis = 0L,
    )

    private fun reminder(
        config: ReminderConfig,
        type: ReminderType,
        id: Long = REMINDER_ID,
        enabled: Boolean = true,
        parentReminderId: Long? = null,
        activeFromEpochDay: Long? = null,
        activeUntilEpochDay: Long? = null,
        createdAtEpochMillis: Long = 0L,
    ) = Reminder(
        id = id,
        medicineId = 1L,
        parentReminderId = parentReminderId,
        type = type,
        configJson = encodeReminderConfig(config),
        enabled = enabled,
        activeFromEpochDay = activeFromEpochDay,
        activeUntilEpochDay = activeUntilEpochDay,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private val dailyAt8 = ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0, repeatPattern = RepeatPattern.Daily)

    private fun fixedTimeReminder(
        activeFromEpochDay: Long? = null,
        activeUntilEpochDay: Long? = null,
        enabled: Boolean = true,
        config: ReminderConfig.FixedTime = dailyAt8,
    ) = reminder(
        config = config,
        type = ReminderType.FIXED_TIME,
        enabled = enabled,
        activeFromEpochDay = activeFromEpochDay,
        activeUntilEpochDay = activeUntilEpochDay,
    )

    // ---- Gates -------------------------------------------------------------

    @Test
    fun `a disabled reminder has no next occurrence`() = runTest {
        val next = engine().nextOccurrenceFor(fixedTimeReminder(enabled = false), at("2026-06-01T06:00"))
        assertNull(next)
    }

    @Test
    fun `an alert-type reminder is skipped rather than scheduled`() = runTest {
        // LOW_STOCK_ALERT and EXPIRATION_ALERT deliberately have no
        // next-occurrence chain -- LowStockExpirationAlertEvaluator handles
        // them on the cache's own recompute pass instead.
        val alert = reminder(
            config = ReminderConfig.LowStockAlert(threshold = 5.0, triggerMode = LowStockTriggerMode.ONCE_WHEN_BELOW),
            type = ReminderType.LOW_STOCK_ALERT,
        )
        assertNull(engine().nextOccurrenceFor(alert, at("2026-06-01T06:00")))
    }

    @Test
    fun `a fixed-time reminder resolves through the pure calculator`() = runTest {
        val next = engine().nextOccurrenceFor(fixedTimeReminder(), at("2026-06-01T06:00"))
        assertOccursAt("2026-06-01T08:00", next)
        assertEquals(1.0, next!!.doseAmount, 0.0)
    }

    // ---- activeFrom / activeUntil clamp ------------------------------------

    @Test
    fun `a candidate before the active range opens is clamped away`() = runTest {
        val reminder = fixedTimeReminder(activeFromEpochDay = epochDay("2026-06-10"))
        assertNull(engine().nextOccurrenceFor(reminder, at("2026-06-01T06:00")))
    }

    @Test
    fun `a candidate after the active range closes is clamped away`() = runTest {
        val reminder = fixedTimeReminder(activeUntilEpochDay = epochDay("2026-05-31"))
        assertNull(engine().nextOccurrenceFor(reminder, at("2026-06-01T06:00")))
    }

    @Test
    fun `a candidate inside the active range survives the clamp`() = runTest {
        val reminder = fixedTimeReminder(
            activeFromEpochDay = epochDay("2026-06-01"),
            activeUntilEpochDay = epochDay("2026-06-30"),
        )
        assertOccursAt("2026-06-01T08:00", engine().nextOccurrenceFor(reminder, at("2026-06-01T06:00")))
    }

    @Test
    fun `weekend mode is applied after the clamp, so a push can land past activeUntil`() = runTest {
        // Pins spec section 4's stated ordering: calculator, then the
        // activeFrom/activeUntil clamp, then weekend mode. The consequence is
        // visible here -- a Saturday 23:00 slot passes a clamp that closes on
        // Saturday, and the overnight weekend window then pushes it into
        // Sunday, one day outside the range. Ordering the clamp last would
        // instead drop the dose entirely. Recorded as behavior, not endorsed
        // as the obviously right call.
        val engine = engine(
            settings = fakeSettings(
                WeekendMode.On(days = setOf(6, 7), start = LocalTime.of(22, 0), end = LocalTime.of(6, 0)),
            ),
        )
        val reminder = fixedTimeReminder(
            config = ReminderConfig.FixedTime(hour = 23, minute = 0, dose = 1.0, repeatPattern = RepeatPattern.Daily),
            activeUntilEpochDay = epochDay("2026-06-06"),
        )
        assertOccursAt("2026-06-07T06:00", engine.nextOccurrenceFor(reminder, at("2026-06-06T12:00")))
    }

    // ---- RepeatingInterval anchors -----------------------------------------

    private fun intervalReminder(startBehavior: IntervalStartBehavior, anchor: Long, createdAt: Long = 0L) = reminder(
        config = ReminderConfig.RepeatingInterval(
            intervalMinutes = 480,
            dose = 1.0,
            startBehavior = startBehavior,
            anchorEpochMillis = anchor,
        ),
        type = ReminderType.REPEATING_INTERVAL,
        createdAtEpochMillis = createdAt,
    )

    @Test
    fun `an on-resolved interval anchors on the last resolved dose`() = runTest {
        val dao = fakeDao(
            lastResolved = mapOf(REMINDER_ID to doseEvent(resolvedAtEpochMillis = at("2026-06-01T10:00"))),
        )
        val reminder = intervalReminder(IntervalStartBehavior.ON_RESOLVED, anchor = at("2026-06-01T00:00"))
        assertOccursAt("2026-06-01T18:00", engine(dao = dao).nextOccurrenceFor(reminder, at("2026-06-01T11:00")))
    }

    @Test
    fun `an on-resolved interval with no dose history falls back to the creation time`() = runTest {
        val reminder = intervalReminder(
            IntervalStartBehavior.ON_RESOLVED,
            anchor = at("2026-06-01T05:00"), // must be ignored in favour of createdAt
            createdAt = at("2026-06-01T00:00"),
        )
        assertOccursAt("2026-06-01T16:00", engine().nextOccurrenceFor(reminder, at("2026-06-01T11:00")))
    }

    @Test
    fun `an on-reminded interval ignores dose history and stays on the config anchor`() = runTest {
        // The dao has a resolution at 10:00; honouring it would yield 18:00.
        val dao = fakeDao(
            lastResolved = mapOf(REMINDER_ID to doseEvent(resolvedAtEpochMillis = at("2026-06-01T10:00"))),
        )
        val reminder = intervalReminder(IntervalStartBehavior.ON_REMINDED, anchor = at("2026-06-01T09:00"))
        assertOccursAt("2026-06-01T17:00", engine(dao = dao).nextOccurrenceFor(reminder, at("2026-06-01T11:00")))
    }

    // ---- LinkedFollowing ----------------------------------------------------

    private fun linkedReminder(startBehavior: IntervalStartBehavior, parentReminderId: Long?) = reminder(
        config = ReminderConfig.LinkedFollowing(delayMinutes = 30, dose = 1.0, startBehavior = startBehavior),
        type = ReminderType.LINKED_FOLLOWING,
        parentReminderId = parentReminderId,
    )

    @Test
    fun `an orphaned linked-following child has no next occurrence`() = runTest {
        // parentReminderId is nulled by the FK's ON DELETE SET_NULL. Today no
        // call site reaches it (ReminderRepository.delete() is unwired), which
        // is exactly why this needs pinning before one is added -- see the
        // open FK question recorded in spec section 7 R23.
        val child = linkedReminder(IntervalStartBehavior.ON_REMINDED, parentReminderId = null)
        assertNull(engine().nextOccurrenceFor(child, at("2026-06-01T06:00")))
    }

    @Test
    fun `an on-reminded child follows the most recent firing of its parent`() = runTest {
        val dao = fakeDao(mostRecent = mapOf(PARENT_ID to doseEvent(scheduledEpochMillis = at("2026-06-01T08:00"))))
        val child = linkedReminder(IntervalStartBehavior.ON_REMINDED, parentReminderId = PARENT_ID)
        assertOccursAt("2026-06-01T08:30", engine(dao = dao).nextOccurrenceFor(child, at("2026-06-01T06:00")))
    }

    @Test
    fun `an on-resolved child follows the last resolution of its parent`() = runTest {
        val dao = fakeDao(lastResolved = mapOf(PARENT_ID to doseEvent(resolvedAtEpochMillis = at("2026-06-01T09:15"))))
        val child = linkedReminder(IntervalStartBehavior.ON_RESOLVED, parentReminderId = PARENT_ID)
        assertOccursAt("2026-06-01T09:45", engine(dao = dao).nextOccurrenceFor(child, at("2026-06-01T06:00")))
    }

    @Test
    fun `a child whose parent has never fired has no next occurrence yet`() = runTest {
        val child = linkedReminder(IntervalStartBehavior.ON_REMINDED, parentReminderId = PARENT_ID)
        assertNull(engine().nextOccurrenceFor(child, at("2026-06-01T06:00")))
    }

    // ---- Projection walk ----------------------------------------------------

    @Test
    fun `occurrencesFor walks every slot in the range`() = runTest {
        val slots = engine().occurrencesFor(
            fixedTimeReminder(),
            fromEpochMillis = at("2026-06-01T00:00"),
            toEpochMillis = at("2026-06-04T00:00"),
        )
        assertEquals(
            listOf("2026-06-01T08:00", "2026-06-02T08:00", "2026-06-03T08:00").map(LocalDateTime::parse),
            slots.map { it.epochMillis.asLocal() },
        )
    }

    @Test
    fun `occurrencesFor honours maxOccurrences`() = runTest {
        val slots = engine().occurrencesFor(
            fixedTimeReminder(),
            fromEpochMillis = at("2026-06-01T00:00"),
            toEpochMillis = at("2026-12-31T00:00"),
            maxOccurrences = 2,
        )
        assertEquals(2, slots.size)
    }

    @Test
    fun `occurrencesFor yields at most one linked-following occurrence`() = runTest {
        // Without the LINKED_FOLLOWING break, advancing notBefore would return
        // the same parent-anchored instant up to maxOccurrences times.
        val dao = fakeDao(mostRecent = mapOf(PARENT_ID to doseEvent(scheduledEpochMillis = at("2026-06-01T08:00"))))
        val slots = engine(dao = dao).occurrencesFor(
            linkedReminder(IntervalStartBehavior.ON_REMINDED, parentReminderId = PARENT_ID),
            fromEpochMillis = at("2026-06-01T00:00"),
            toEpochMillis = at("2026-06-05T00:00"),
        )
        assertEquals(1, slots.size)
        assertOccursAt("2026-06-01T08:30", slots.single())
    }

    @Test
    fun `occurrencesFor reads the weekend preferences once for the whole walk`() = runTest {
        // The reason readWeekendMode() exists at all: each Flow read is a
        // fresh cold-flow collection, and the walk can call nextOccurrenceFor
        // up to maxOccurrences times per reminder per recompute.
        val settings = fakeSettings()
        engine(settings = settings).occurrencesFor(
            fixedTimeReminder(),
            fromEpochMillis = at("2026-06-01T00:00"),
            toEpochMillis = at("2026-06-10T00:00"),
        )
        verify(exactly = 1) { settings.weekendModeEnabled }
    }

    private companion object {
        const val REMINDER_ID = 1L
        const val PARENT_ID = 42L
    }
}
