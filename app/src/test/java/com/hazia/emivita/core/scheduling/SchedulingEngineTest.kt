package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.StockInfo
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

    /** Alert scheduling reads the medicine's stock and expiry (spec §7 R37),
     *  so the engine now needs a MedicineDao. Defaults to "no such medicine",
     *  which every dose-type test is indifferent to. */
    private fun fakeMedicineDao(medicine: Medicine? = null): MedicineDao =
        mockk<MedicineDao>().also { dao -> coEvery { dao.getById(any()) } returns medicine }

    private fun medicine(
        quantity: Double = 0.0,
        unit: String? = null,
        expirationDateEpochDay: Long? = null,
    ) = Medicine(
        id = 1L,
        profileId = 1L,
        name = "Test",
        iconKey = "pill",
        stockInfo = StockInfo(
            quantity = quantity,
            unit = unit,
            expirationDateEpochDay = expirationDateEpochDay,
        ),
        sortOrder = 0.0,
        createdAtEpochMillis = 0L,
    )

    private fun engine(
        dao: DoseEventDao = fakeDao(),
        settings: SettingsRepository = fakeSettings(),
        medicines: MedicineDao = fakeMedicineDao(),
    ) = SchedulingEngine(
        doseEventDao = dao,
        medicineDao = medicines,
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
        weekendModeOptIn: Boolean = false,
    ) = Reminder(
        id = id,
        medicineId = 1L,
        parentReminderId = parentReminderId,
        type = type,
        configJson = encodeReminderConfig(config),
        enabled = enabled,
        activeFromEpochDay = activeFromEpochDay,
        activeUntilEpochDay = activeUntilEpochDay,
        weekendModeOptIn = weekendModeOptIn,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private val dailyAt8 = ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0, repeatPattern = RepeatPattern.Daily)

    private fun fixedTimeReminder(
        activeFromEpochDay: Long? = null,
        activeUntilEpochDay: Long? = null,
        enabled: Boolean = true,
        config: ReminderConfig.FixedTime = dailyAt8,
        weekendModeOptIn: Boolean = false,
    ) = reminder(
        config = config,
        type = ReminderType.FIXED_TIME,
        enabled = enabled,
        activeFromEpochDay = activeFromEpochDay,
        activeUntilEpochDay = activeUntilEpochDay,
        weekendModeOptIn = weekendModeOptIn,
    )

    // ---- Gates -------------------------------------------------------------

    @Test
    fun `a disabled reminder has no next occurrence`() = runTest {
        val next = engine().nextOccurrenceFor(fixedTimeReminder(enabled = false), at("2026-06-01T06:00"))
        assertNull(next)
    }

    // ---- Alert types (spec §7 R37) -----------------------------------------

    private fun lowStockReminder(
        triggerMode: LowStockTriggerMode,
        threshold: Double = 5.0,
        lastNotifiedEpochDay: Long? = null,
    ) = reminder(
        config = ReminderConfig.LowStockAlert(threshold, triggerMode, hour = 9, minute = 0),
        type = ReminderType.LOW_STOCK_ALERT,
    ).copy(lastAlertNotifiedEpochDay = lastNotifiedEpochDay)

    private fun expirationReminder(
        frequency: ExpirationTriggerFrequency,
        leadDays: Int = 7,
        lastNotifiedEpochDay: Long? = null,
    ) = reminder(
        config = ReminderConfig.ExpirationAlert(leadDays, frequency, hour = 9, minute = 0),
        type = ReminderType.EXPIRATION_ALERT,
    ).copy(lastAlertNotifiedEpochDay = lastNotifiedEpochDay)

    @Test
    fun `a daily low-stock alert below its threshold is scheduled`() = runTest {
        // The whole point of R37: this used to assert null, because both alert
        // types were excluded from supportedTypes and nothing drove them from
        // a clock.
        val engine = engine(medicines = fakeMedicineDao(medicine(quantity = 2.0, unit = "tablets")))
        assertOccursAt(
            "2026-06-01T09:00",
            engine.nextOccurrenceFor(lowStockReminder(LowStockTriggerMode.DAILY_WHEN_BELOW), at("2026-06-01T06:00")),
        )
    }

    @Test
    fun `a daily low-stock alert already announced today waits for tomorrow`() = runTest {
        val engine = engine(medicines = fakeMedicineDao(medicine(quantity = 2.0, unit = "tablets")))
        val alert = lowStockReminder(LowStockTriggerMode.DAILY_WHEN_BELOW, lastNotifiedEpochDay = epochDay("2026-06-01"))
        assertOccursAt("2026-06-02T09:00", engine.nextOccurrenceFor(alert, at("2026-06-01T06:00")))
    }

    @Test
    fun `a low-stock alert above its threshold has nothing to arm`() = runTest {
        val engine = engine(medicines = fakeMedicineDao(medicine(quantity = 50.0, unit = "tablets")))
        assertNull(engine.nextOccurrenceFor(lowStockReminder(LowStockTriggerMode.DAILY_WHEN_BELOW), at("2026-06-01T06:00")))
    }

    @Test
    fun `a low-stock alert on an untracked medicine has nothing to arm`() = runTest {
        // unit == null is the app-wide "stock tracking is off" flag, so there
        // is no threshold to be below however small the quantity reads.
        val engine = engine(medicines = fakeMedicineDao(medicine(quantity = 0.0, unit = null)))
        assertNull(engine.nextOccurrenceFor(lowStockReminder(LowStockTriggerMode.DAILY_WHEN_BELOW), at("2026-06-01T06:00")))
    }

    @Test
    fun `once and always low-stock modes stay event-driven, not scheduled`() = runTest {
        // Matches medTimer: OutOfStockScheduling returns null unless the mode
        // is DAILY, and StockHandlingProcessor announces the other two inline
        // the moment stock crosses. An alarm cannot express "the instant this
        // changes"; LowStockExpirationAlertEvaluator still owns these.
        val engine = engine(medicines = fakeMedicineDao(medicine(quantity = 2.0, unit = "tablets")))
        assertNull(engine.nextOccurrenceFor(lowStockReminder(LowStockTriggerMode.ONCE_WHEN_BELOW), at("2026-06-01T06:00")))
        assertNull(engine.nextOccurrenceFor(lowStockReminder(LowStockTriggerMode.ALWAYS_WHEN_BELOW), at("2026-06-01T06:00")))
    }

    @Test
    fun `an expiration alert waits until its lead window opens`() = runTest {
        // Expires 2026-06-20, 7 days lead, so the first alert is on 06-13 --
        // not today, even though the reminder exists today.
        val engine = engine(medicines = fakeMedicineDao(medicine(expirationDateEpochDay = epochDay("2026-06-20"))))
        assertOccursAt(
            "2026-06-13T09:00",
            engine.nextOccurrenceFor(expirationReminder(ExpirationTriggerFrequency.DAILY), at("2026-06-01T06:00")),
        )
    }

    @Test
    fun `a daily expiration alert keeps firing past the expiry date`() = runTest {
        // An expired medicine is more worth mentioning than one about to
        // expire, not less.
        val engine = engine(medicines = fakeMedicineDao(medicine(expirationDateEpochDay = epochDay("2026-05-01"))))
        assertOccursAt(
            "2026-06-01T09:00",
            engine.nextOccurrenceFor(expirationReminder(ExpirationTriggerFrequency.DAILY), at("2026-06-01T06:00")),
        )
    }

    @Test
    fun `a once expiration alert that already fired is never scheduled again`() = runTest {
        // Unlike low stock there is no restock to rearm from -- an expiry date
        // does not un-pass.
        val engine = engine(medicines = fakeMedicineDao(medicine(expirationDateEpochDay = epochDay("2026-06-05"))))
        val alert = expirationReminder(ExpirationTriggerFrequency.ONCE, lastNotifiedEpochDay = epochDay("2026-05-30"))
        assertNull(engine.nextOccurrenceFor(alert, at("2026-06-01T06:00")))
    }

    @Test
    fun `an expiration alert on a medicine with no expiry date has nothing to arm`() = runTest {
        val engine = engine(medicines = fakeMedicineDao(medicine(expirationDateEpochDay = null)))
        assertNull(engine.nextOccurrenceFor(expirationReminder(ExpirationTriggerFrequency.DAILY), at("2026-06-01T06:00")))
    }

    @Test
    fun `an alert whose slot has passed today rolls to tomorrow`() = runTest {
        val engine = engine(medicines = fakeMedicineDao(medicine(quantity = 2.0, unit = "tablets")))
        assertOccursAt(
            "2026-06-02T09:00",
            engine.nextOccurrenceFor(lowStockReminder(LowStockTriggerMode.DAILY_WHEN_BELOW), at("2026-06-01T18:00")),
        )
    }

    @Test
    fun `a fixed-time reminder resolves through the pure calculator`() = runTest {
        val next = engine().nextOccurrenceFor(fixedTimeReminder(), at("2026-06-01T06:00"))
        assertOccursAt("2026-06-01T08:00", next)
        assertEquals(1.0, next!!.doseAmount, 0.0)
    }

    // ---- activeFrom / activeUntil clamp ------------------------------------

    @Test
    fun `a range that has not opened yet defers to its first day rather than never firing`() = runTest {
        // This used to assert null, pinning the bug rather than the rule: a
        // future activeFrom means "not yet", not "never", and nothing in the
        // app re-arms a reminder on the passage of time alone, so cancelling
        // here cancelled it permanently. A course or taper -- which spec §4
        // says this clamp is the whole mechanism for -- could only ever fire
        // the segment that was already in range.
        val reminder = fixedTimeReminder(activeFromEpochDay = epochDay("2026-06-10"))
        assertOccursAt("2026-06-10T08:00", engine().nextOccurrenceFor(reminder, at("2026-06-01T06:00")))
    }

    @Test
    fun `a deferred range still respects the far end`() = runTest {
        // Deferring forward must not walk past activeUntil: a range whose two
        // bounds cannot both be satisfied has no occurrence at all.
        val reminder = fixedTimeReminder(
            activeFromEpochDay = epochDay("2026-06-10"),
            activeUntilEpochDay = epochDay("2026-06-09"),
        )
        assertNull(engine().nextOccurrenceFor(reminder, at("2026-06-01T06:00")))
    }

    @Test
    fun `a projection walk covers a course that starts inside the window`() = runTest {
        // The consumer-visible half of the same fix: Today, History and both
        // widgets read occurrencesFor, which returned an empty list for every
        // not-yet-started course.
        val slots = engine().occurrencesFor(
            fixedTimeReminder(
                activeFromEpochDay = epochDay("2026-06-03"),
                activeUntilEpochDay = epochDay("2026-06-05"),
            ),
            fromEpochMillis = at("2026-06-01T00:00"),
            toEpochMillis = at("2026-06-30T00:00"),
        )
        assertEquals(
            listOf("2026-06-03T08:00", "2026-06-04T08:00", "2026-06-05T08:00"),
            slots.map { it.epochMillis.asLocal().toString() },
        )
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
            weekendModeOptIn = true,
        )
        assertOccursAt("2026-06-07T06:00", engine.nextOccurrenceFor(reminder, at("2026-06-06T12:00")))
    }

    @Test
    fun `weekend mode leaves a reminder that has not opted in alone`() = runTest {
        // The gate R36 added. Same engine, same settings, same slot as the
        // test above -- only the opt-in differs, so a regression that reverts
        // to the old global behaviour fails here and nowhere else.
        val engine = engine(
            settings = fakeSettings(
                WeekendMode.On(days = setOf(6, 7), start = LocalTime.of(22, 0), end = LocalTime.of(6, 0)),
            ),
        )
        val reminder = fixedTimeReminder(
            config = ReminderConfig.FixedTime(hour = 23, minute = 0, dose = 1.0, repeatPattern = RepeatPattern.Daily),
        )
        assertOccursAt("2026-06-06T23:00", engine.nextOccurrenceFor(reminder, at("2026-06-06T12:00")))
    }

    @Test
    fun `an opted-in reminder is still untouched while the global toggle is off`() = runTest {
        // Both switches are required, not either. The per-reminder flag says
        // "shift me by the weekend window"; there is no window to shift by
        // until the global side defines one.
        val reminder = fixedTimeReminder(
            config = ReminderConfig.FixedTime(hour = 23, minute = 0, dose = 1.0, repeatPattern = RepeatPattern.Daily),
            weekendModeOptIn = true,
        )
        assertOccursAt("2026-06-06T23:00", engine().nextOccurrenceFor(reminder, at("2026-06-06T12:00")))
    }

    @Test
    fun `a reminder that has not opted in never reads the weekend preferences`() = runTest {
        // The cost half of the gate: readWeekendMode() is a fresh cold-flow
        // collection per call, and the walk can call nextOccurrenceFor up to
        // maxOccurrences times. Opting out should cost nothing, not one read.
        val settings = fakeSettings()
        engine(settings = settings).nextOccurrenceFor(fixedTimeReminder(), at("2026-06-01T06:00"))
        verify(exactly = 0) { settings.weekendModeEnabled }
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
    fun `a child whose slot has already passed has no next occurrence`() = runTest {
        // The anchor is the parent's *last* event, so a child's computed slot
        // goes stale on its own once delayMinutes has elapsed. Returning it
        // anyway broke nextOccurrenceFor's own at/after contract, and the two
        // things that consume it: RealSchedulingCoordinator armed an alarm in
        // the past (AlarmManager fires those immediately) on every reschedule
        // after the child's dose was answered, and the shared projection kept
        // publishing the stale slot until the parent fired again.
        val dao = fakeDao(mostRecent = mapOf(PARENT_ID to doseEvent(scheduledEpochMillis = at("2026-06-01T08:00"))))
        val child = linkedReminder(IntervalStartBehavior.ON_REMINDED, parentReminderId = PARENT_ID)
        assertNull(engine(dao = dao).nextOccurrenceFor(child, at("2026-06-01T09:00")))
    }

    @Test
    fun `a child whose slot is still ahead survives the notBefore floor`() = runTest {
        // The boundary the guard above must not overshoot: exactly on the slot
        // still counts as due, the same rule the fixed-time calculator uses.
        val dao = fakeDao(mostRecent = mapOf(PARENT_ID to doseEvent(scheduledEpochMillis = at("2026-06-01T08:00"))))
        val child = linkedReminder(IntervalStartBehavior.ON_REMINDED, parentReminderId = PARENT_ID)
        assertOccursAt("2026-06-01T08:30", engine(dao = dao).nextOccurrenceFor(child, at("2026-06-01T08:30")))
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
    fun `a three-step taper is three fixed-time reminders with adjacent ranges`() = runTest {
        // Makes §4's claim executable instead of asserted. §4 says "a taper
        // schedule is just several ordinary FixedTime reminders with adjacent,
        // non-overlapping ranges -- no new ReminderConfig type needed, this
        // clamp is the whole mechanism", while §9's parking lot says tapering
        // needs "a new `Tapering` ReminderConfig variant (a list of
        // {dateRange, dose} steps)". The spec endorsed both at once.
        //
        // This pins the side that turns out to be right. Note it could not have
        // passed before §7 R30: the activeFrom clamp used to return null for a
        // range that had not opened yet, so steps 2 and 3 below -- whose ranges
        // begin in the future relative to the walk's start -- would each have
        // projected nothing, and a taper would have delivered its first dose
        // level forever. See docs/SPEC.md §7 R43.
        val engine = engine()
        val taper = listOf(
            // 10mg for three days, then 7.5mg for three, then 5mg for three.
            Triple(10.0, "2026-06-01", "2026-06-03"),
            Triple(7.5, "2026-06-04", "2026-06-06"),
            Triple(5.0, "2026-06-07", "2026-06-09"),
        ).map { (dose, from, until) ->
            fixedTimeReminder(
                config = ReminderConfig.FixedTime(hour = 8, minute = 0, dose = dose, repeatPattern = RepeatPattern.Daily),
                activeFromEpochDay = epochDay(from),
                activeUntilEpochDay = epochDay(until),
            )
        }

        // One walk per reminder over the whole taper, merged the way the
        // projection cache merges a medicine's reminders.
        val schedule = taper
            .flatMap { engine.occurrencesFor(it, at("2026-06-01T00:00"), at("2026-06-15T00:00")) }
            .sortedBy { it.epochMillis }
            .map { it.epochMillis.asLocal().toLocalDate().toString() to it.doseAmount }

        assertEquals(
            listOf(
                "2026-06-01" to 10.0, "2026-06-02" to 10.0, "2026-06-03" to 10.0,
                "2026-06-04" to 7.5, "2026-06-05" to 7.5, "2026-06-06" to 7.5,
                "2026-06-07" to 5.0, "2026-06-08" to 5.0, "2026-06-09" to 5.0,
            ),
            schedule,
        )
    }

    @Test
    fun `adjacent taper ranges neither gap nor overlap at the handover`() = runTest {
        // The failure mode the mechanism actually has. Ranges are inclusive on
        // both ends, so a step ending 06-03 and the next starting 06-04 hand
        // over cleanly -- exactly one dose on each of those days, at one dose
        // level each. Off-by-one in either direction is a missed dose or a
        // doubled one, which is why this is pinned separately from the happy
        // path above rather than left implicit in it.
        val engine = engine()
        val stepOne = fixedTimeReminder(
            config = ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 10.0, repeatPattern = RepeatPattern.Daily),
            activeUntilEpochDay = epochDay("2026-06-03"),
        )
        val stepTwo = fixedTimeReminder(
            config = ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 7.5, repeatPattern = RepeatPattern.Daily),
            activeFromEpochDay = epochDay("2026-06-04"),
        )
        val handover = (
            engine.occurrencesFor(stepOne, at("2026-06-03T00:00"), at("2026-06-05T00:00")) +
                engine.occurrencesFor(stepTwo, at("2026-06-03T00:00"), at("2026-06-05T00:00"))
            )
            .sortedBy { it.epochMillis }
            .map { it.epochMillis.asLocal().toString() to it.doseAmount }

        assertEquals(
            listOf("2026-06-03T08:00" to 10.0, "2026-06-04T08:00" to 7.5),
            handover,
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
