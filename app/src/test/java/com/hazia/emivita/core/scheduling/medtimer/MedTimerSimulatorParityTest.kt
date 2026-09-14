package com.hazia.emivita.core.scheduling.medtimer

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.ProjectionState
import com.hazia.emivita.core.scheduling.SchedulingEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * medTimer's `SchedulingSimulatorTest` -- the eleven cases R60 deselected --
 * ported onto the forward walk it is the model for: [SchedulingEngine.occurrencesFor]
 * per reminder, and [OccurrenceProjectionCache] where a case is about the
 * merged view or the stock arithmetic (spec §9 #30; Appendix E group 7).
 * Same calendar as [MedTimerEngineParityTest]: zone Z, day 1 is 1970-01-01
 * (medTimer's `LocalDate.EPOCH`), `on(day, minutes)`.
 *
 * Three outcomes, as in R60: **parity** (same slots for the same history);
 * **divergence pinned** as Emivita's behaviour with the row that decided it
 * (A-38, A-46; A-20 and A-24 were pinned here by R70 and flipped to parity by
 * §9 #31, R72); **none** for `singleDays`, whose
 * days-of-month pattern Emivita does not have (A-13) -- no test can be
 * honestly written for a feature that is absent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedTimerSimulatorParityTest {

    private val zone: ZoneId = ZoneId.of("Z")
    private fun on(day: Long, minutes: Long): Long = ((day - 1) * 86_400 + minutes * 60) * 1_000
    private val now = on(1, 0)

    private fun at(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()
    private fun assertSlots(expected: List<Long>, actual: List<Long>) = assertEquals(expected.map(::at), actual.map(::at))

    private fun medicine(id: Long, stock: Double? = null) = Medicine(
        id = id, profileId = 1L, name = "Test $id", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0L,
        stockInfo = if (stock == null) StockInfo() else StockInfo(quantity = stock, unit = "tablet"),
    )

    private fun reminder(id: Long, medicineId: Long, config: ReminderConfig, parentReminderId: Long? = null) = Reminder(
        id = id, medicineId = medicineId, parentReminderId = parentReminderId, configJson = encodeReminderConfig(config), createdAtEpochMillis = 0L,
        type = when (config) {
            is ReminderConfig.FixedTime -> ReminderType.FIXED_TIME
            is ReminderConfig.RepeatingInterval -> ReminderType.REPEATING_INTERVAL
            is ReminderConfig.DailyWindow -> ReminderType.DAILY_WINDOW
            is ReminderConfig.LinkedFollowing -> ReminderType.LINKED_FOLLOWING
            is ReminderConfig.LowStockAlert -> ReminderType.LOW_STOCK_ALERT
            is ReminderConfig.ExpirationAlert -> ReminderType.EXPIRATION_ALERT
        },
    )

    private fun daily(hour: Int, minute: Int = 0, dose: Double = 1.0) = ReminderConfig.FixedTime(hour = hour, minute = minute, dose = dose)
    private fun interval(minutes: Int, anchor: Long, start: IntervalStartBehavior) =
        ReminderConfig.RepeatingInterval(intervalMinutes = minutes, dose = 1.0, startBehavior = start, anchorEpochMillis = anchor)
    private fun window(minutes: Int, start: LocalTime, end: LocalTime) = ReminderConfig.DailyWindow(
        intervalMinutes = minutes, dose = 1.0, windowStartHour = start.hour, windowStartMinute = start.minute,
        windowEndHour = end.hour, windowEndMinute = end.minute, startBehavior = IntervalStartBehavior.ON_REMINDED,
    )

    private fun event(reminderId: Long, scheduled: Long, resolvedAt: Long? = null) = DoseEvent(
        reminderId = reminderId, medicineId = 1L, scheduledEpochMillis = scheduled, doseAmount = 1.0,
        status = if (resolvedAt == null) DoseStatus.PENDING else DoseStatus.TAKEN, resolvedAtEpochMillis = resolvedAt, createdAtEpochMillis = 0L,
    )

    private fun doseDao(lastResolved: Map<Long, DoseEvent> = emptyMap(), mostRecent: Map<Long, DoseEvent> = emptyMap()) =
        mockk<DoseEventDao>().also { dao ->
            coEvery { dao.getLastResolved(any()) } answers { lastResolved[firstArg<Long>()] }
            coEvery { dao.getMostRecentForReminder(any()) } answers { mostRecent[firstArg<Long>()] }
        }

    private fun settings() = mockk<SettingsRepository>().also { every { it.weekendModeEnabled } returns flowOf(false) }

    private fun engine(dao: DoseEventDao = doseDao()) = SchedulingEngine(
        doseEventDao = dao, medicineDao = mockk<MedicineDao>().also { coEvery { it.getById(any()) } returns null },
        settingsRepository = settings(), fixedZone = zone, nowEpochMillis = { now },
    )

    /** medTimer's `simulate` consumer stops after N reminders; the walk is
     *  bounded by a window instead, and the first N are compared. */
    private fun walk(reminder: Reminder, from: Long = now, dao: DoseEventDao = doseDao(), take: Int): List<Long> = kotlinx.coroutines.runBlocking {
        engine(dao).occurrencesFor(reminder, from, on(40, 0), maxOccurrences = take).map { it.epochMillis }
    }

    private val cacheScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = cacheScopes.forEach { it.cancel() }

    private fun TestScope.project(medicines: List<MedicineWithReminders>, dao: DoseEventDao = doseDao()): ProjectionState {
        val medicineDao = mockk<MedicineDao>()
        coEvery { medicineDao.getAllWithReminders() } returns medicines
        every { medicineDao.observeAllWithReminders() } returns emptyFlow()
        val cache = OccurrenceProjectionCache(
            medicineDao = medicineDao,
            schedulingEngine = SchedulingEngine(dao, medicineDao, settings(), zone) { now },
            alertEvaluator = mockk(relaxed = true),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()).also { cacheScopes += it },
            fixedZone = zone, nowEpochMillis = { now }, dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        advanceUntilIdle()
        return cache.state.value
    }

    // ---- SchedulingSimulatorTest ---------------------------------------------

    @Test
    fun `standard -- two medicines interleave day by day in the merged projection`() = runTest {
        val state = project(
            listOf(
                MedicineWithReminders(medicine(1), listOf(reminder(1, 1, daily(1)))),
                MedicineWithReminders(medicine(2), listOf(reminder(2, 2, daily(2)))),
            ),
        )
        val first = state.occurrences.take(5)
        assertSlots(listOf(on(1, 60), on(1, 120), on(2, 60), on(2, 120), on(3, 60)), first.map { it.epochMillis })
        assertEquals(listOf(1L, 2L, 1L, 2L, 1L), first.map { it.medicineId })
        assertEquals("untracked stock projects no run-out (medTimer: stockAfter 0.0)", emptyMap<Long, LocalDate>(), state.stockRunOutDates)
    }

    @Test
    fun `interval -- a 10-hour interval crosses midnight into day 2`() {
        val r = reminder(1, 1, interval(600, on(1, 600), IntervalStartBehavior.ON_REMINDED))
        assertSlots(listOf(on(1, 600), on(1, 1200), on(2, 360)), walk(r, take = 3))
    }

    @Test
    fun `intervalStartsFromProcessed -- the walk bases on the outstanding dose, taken as answered when it was due`() {
        // medTimer: a processed event (reminded 10:00, processed 11:40) and a later
        // unprocessed one (13:20); the simulator treats the unprocessed as processed
        // and continues from its reminded time: 23:20, then 09:20 and 19:20 on day 2.
        // Emivita's walk assumes an outstanding dose resolved at its scheduled time
        // (`assumeOutstandingResolved`) -- the same anchor, so the same three slots.
        val r = reminder(1, 1, interval(600, on(1, 600), IntervalStartBehavior.ON_RESOLVED))
        val dao = doseDao(
            lastResolved = mapOf(1L to event(1, on(1, 600), resolvedAt = on(1, 700))),
            mostRecent = mapOf(1L to event(1, on(1, 800))),
        )
        assertSlots(listOf(on(1, 1400), on(2, 560), on(2, 1160)), walk(r, from = on(1, 801), dao = dao, take = 3))
    }

    @Test
    fun `windowedInterval -- 02_00, 10_00, then 02_00 next day`() {
        val r = reminder(1, 1, window(480, LocalTime.of(2, 0), LocalTime.of(11, 40)))
        assertSlots(listOf(on(1, 120), on(1, 600), on(2, 120)), walk(r, take = 3))
    }

    @Test
    fun `dailyReminders -- the same window with its interval start in the past is the same three slots`() {
        // medTimer's case differs from `windowedInterval` only in `intervalStart`,
        // which Emivita's DailyWindow does not have: the window is the anchor.
        val r = reminder(1, 1, window(480, LocalTime.of(2, 0), LocalTime.of(11, 40)))
        assertSlots(listOf(on(1, 120), on(1, 600), on(2, 120)), walk(r, from = on(1, 60), take = 3))
    }

    @Test
    fun `windowedIntervalReversed -- an overnight window crosses midnight and comes back the next evening`() {
        // Parity since §9 #31 (A-20) on the chain: 11:40, 00:01, 11:40, 00:01 ...
        // R70 pinned the empty walk this used to be. The walk begins at day 1 00:00,
        // inside the window that started at 11:40 the day before; Emivita fires its
        // remaining slot at 00:01 (A-06's rule -- a DailyWindow has no start anchor),
        // where medTimer's simulator starts its day loop at day 1 and its reminder did
        // not exist the evening before. Same five-slot rhythm, one slot earlier.
        val r = reminder(1, 1, window(741, LocalTime.of(11, 40), LocalTime.of(2, 0)))
        assertSlots(listOf(on(1, 1), on(1, 700), on(2, 1), on(2, 700), on(3, 1)), walk(r, take = 5))
    }

    @Test
    fun `linkedAndAmount -- A-24 pinned, the child is absent until its parent has fired, and then one slot`() = runTest {
        // medTimer simulates parent and child every day: 12 -> 11 -> 9 -> 8 -> 6,
        // so 12 tablets last four days. Emivita's LinkedFollowing is anchored to
        // the parent's most recent *real* event, never to a projected one, so
        // with no history the projection carries the parent alone and the
        // run-out is twelve days out. §9 #31's A-24 changes this.
        val parent = reminder(1, 1, daily(8, dose = 1.0))
        val child = reminder(2, 1, ReminderConfig.LinkedFollowing(delayMinutes = 60, dose = 2.0, startBehavior = IntervalStartBehavior.ON_REMINDED), parentReminderId = 1)
        val noHistory = project(listOf(MedicineWithReminders(medicine(1, stock = 12.0), listOf(parent, child))))
        assertEquals(setOf(1L), noHistory.occurrences.map { it.reminderId }.toSet())
        assertEquals(LocalDate.parse("1970-01-12"), noHistory.stockRunOutDates[1L])

        // Once the parent has a materialized dose, the child yields exactly one
        // occurrence per walk, an hour after it (`occurrencesFor` breaks after it).
        val afterParentFired = project(
            listOf(MedicineWithReminders(medicine(1, stock = 12.0), listOf(parent, child))),
            dao = doseDao(mostRecent = mapOf(1L to event(1, on(1, 480)))),
        )
        assertSlots(listOf(on(1, 540)), afterParentFired.occurrences.filter { it.reminderId == 2L }.map { it.epochMillis })
    }

    @Test
    fun `noReminders -- a medicine with no reminders projects nothing, at the window floor`() = runTest {
        val state = project(listOf(MedicineWithReminders(medicine(1, stock = 5.0), emptyList())))
        assertEquals(emptyList<Any>(), state.occurrences)
        assertEquals(emptyMap<Long, LocalDate>(), state.stockRunOutDates)
        assertEquals(28L, state.windowDays)
    }

    @Test
    fun `outOfStock -- A-46 pinned, an alert is not an entry in the projection -- the run-out date is`() = runTest {
        // medTimer's simulator emits the OUT_OF_STOCK reminder itself as a
        // simulated entry on day 3 (12 -> 9 -> 6, threshold 6). Emivita keeps
        // alerts out of the dose projection by design (A-46): the alert fires
        // through its own reminder's alarm, and the projection answers the
        // stock question directly -- three a day from twelve runs out on day 4.
        val state = project(
            listOf(
                MedicineWithReminders(
                    medicine(1, stock = 12.0),
                    listOf(
                        reminder(1, 1, daily(8, dose = 3.0)),
                        reminder(2, 1, ReminderConfig.LowStockAlert(threshold = 6.0, triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW)),
                    ),
                ),
            ),
        )
        assertEquals(setOf(1L), state.occurrences.map { it.reminderId }.toSet())
        assertEquals(LocalDate.parse("1970-01-04"), state.stockRunOutDates[1L])
    }

    @Test
    fun `twoFutureEventsNotDuplicated -- A-38 pinned, the walk does not read future real events`() {
        // medTimer: real events already on days 2 and 3 suppress those days, so
        // the simulation yields day 1 and day 4. Emivita's FixedTime walk is
        // pure arithmetic over the pattern; a materialized future dose is
        // Today's business (it shows the row), not the projection's.
        val r = reminder(1, 1, daily(8))
        val dao = doseDao(mostRecent = mapOf(1L to event(1, on(3, 480))))
        assertSlots(listOf(on(1, 480), on(2, 480), on(3, 480), on(4, 480)), walk(r, dao = dao, take = 4))
    }

    // `singleDays` (days of month 5 and 8): no RepeatPattern for it (A-13) -- not portable.
}
