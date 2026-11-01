package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.dao.DoseEventDao
import com.hazia.emivita.core.data.dao.MedicineDao
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Covers the shared-window arithmetic recorded in spec §7 R28: every screen
 * and widget reads one [ProjectionState], so a
 * [OccurrenceProjectionCache.requestWindow] registration means "at least this
 * many days" and Today's 2-day request must never shrink anyone else's window.
 *
 * The dispatcher is injected so the 1s debounce runs on virtual time, and the
 * cache runs in [TestScope.backgroundScope] because its trigger loop never
 * terminates and would otherwise hang the test at teardown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OccurrenceProjectionCacheTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val now: Long = LocalDateTime.parse("2026-06-01T00:00").atZone(zone).toInstant().toEpochMilli()

    private fun medicineWithDailyDose(
        medicineId: Long = 1L,
        stockQuantity: Double,
        doseAmount: Double,
        unit: String? = "tablet",
    ) = MedicineWithReminders(
        medicine = Medicine(
            id = medicineId,
            profileId = 1L,
            name = "Ibuprofen",
            iconKey = "pill",
            stockInfo = StockInfo(quantity = stockQuantity, unit = unit),
            sortOrder = 0.0,
            createdAtEpochMillis = 0,
        ),
        reminders = listOf(
            Reminder(
                id = 1L,
                medicineId = medicineId,
                type = ReminderType.FIXED_TIME,
                configJson = encodeReminderConfig(
                    ReminderConfig.FixedTime(hour = 8, minute = 0, dose = doseAmount, repeatPattern = RepeatPattern.Daily),
                ),
                createdAtEpochMillis = 0,
            ),
        ),
    )

    // Scopes the cache is launched into. Its trigger loop never terminates,
    // so it has to be cancelled explicitly rather than awaited. Not
    // TestScope.backgroundScope: that turned out not to execute under
    // advanceUntilIdle() here (proved with the control test below), whereas a
    // scope sharing the same testScheduler does.
    private val cacheScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        cacheScopes.forEach { it.cancel() }
    }

    private fun TestScope.buildCache(
        medicines: List<MedicineWithReminders>,
        // Before [clock]: tests pass the clock as a trailing lambda.
        onPublished: () -> Unit = {},
        answeredSlots: Set<Pair<Long, Long>> = emptySet(),
        armedSlots: Map<Long, Long> = emptyMap(),
        clock: () -> Long = { now },
    ): OccurrenceProjectionCache {
        val medicineDao = mockk<MedicineDao>()
        coEvery { medicineDao.getAllWithReminders() } returns medicines
        // Empty rather than a real Flow: the reactive watcher is not what these
        // tests are about, and a live Flow would add triggers of its own.
        every { medicineDao.observeAllWithReminders() } returns emptyFlow()

        val doseEventDao = mockk<DoseEventDao>()
        coEvery { doseEventDao.getLastResolved(any()) } returns null
        coEvery { doseEventDao.getMostRecentForReminder(any()) } returns null

        val settings = mockk<SettingsRepository>()
        every { settings.weekendModeEnabled } returns flowOf(false)

        return OccurrenceProjectionCache(
            medicineDao = medicineDao,
            schedulingEngine = SchedulingEngine(doseEventDao, medicineDao, settings, zone) { now },
            alertEvaluator = mockk(relaxed = true),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()).also { cacheScopes += it },
            fixedZone = zone,
            nowEpochMillis = clock,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            onPublished = onPublished,
            answeredSlots = { _, _ -> answeredSlots },
            armedSlots = { armedSlots },
        )
    }

    // ---- Readers outside core (§7 R104) --------------------------------------

    @Test
    fun `each finished pass is reported once, the states published mid-pass are not`() = runTest {
        var finished = 0
        val cache = buildCache(
            listOf(medicineWithDailyDose(1L, stockQuantity = 30.0, doseAmount = 1.0), medicineWithDailyDose(2L, stockQuantity = 30.0, doseAmount = 1.0)),
            onPublished = { finished++ },
        )
        advanceUntilIdle()
        assertEquals(1, finished)
        assertFalse(cache.state.value.inProgress)

        cache.invalidate()
        advanceUntilIdle()
        assertEquals(2, finished)
    }

    @Test
    fun `a slot already answered is not projected, nor counted against the stock again`() = runTest {
        // Census A-38: a dose taken early with "Take now" stayed on the widget as
        // upcoming, and the run-out walk subtracted it a second time.
        val plain = buildCache(listOf(medicineWithDailyDose(stockQuantity = 10.0, doseAmount = 1.0)))
        advanceUntilIdle()
        val first = plain.state.value.occurrences.first()
        assertEquals(LocalDate.parse("2026-06-10"), plain.state.value.stockRunOutDates[1L])

        val answered = buildCache(
            listOf(medicineWithDailyDose(stockQuantity = 10.0, doseAmount = 1.0)),
            answeredSlots = setOf(first.reminderId to first.epochMillis),
        )
        advanceUntilIdle()
        assertFalse(answered.state.value.occurrences.any { it.epochMillis == first.epochMillis })
        assertEquals(LocalDate.parse("2026-06-11"), answered.state.value.stockRunOutDates[1L])
    }

    @Test
    fun `a slot past its time whose alarm is still armed is projected, one moved on is not`() = runTest {
        // §7 R140 (W4): OxygenOS held a 00:05 alarm to 00:11, and a pass in between walked from now.
        val eight = LocalDateTime.parse("2026-06-01T08:00").atZone(zone).toInstant().toEpochMilli()
        val sevenPast = eight + 7 * 60_000L
        val medicines = listOf(medicineWithDailyDose(stockQuantity = 10.0, doseAmount = 1.0))
        val held = buildCache(medicines, armedSlots = mapOf(1L to eight)) { sevenPast }
        val movedOn = buildCache(medicines, armedSlots = mapOf(1L to eight + 24 * 60 * 60_000L)) { sevenPast }
        advanceUntilIdle()
        assertEquals(eight, held.state.value.occurrences.first().epochMillis)
        assertEquals("the held dose is still owed by the stock", LocalDate.parse("2026-06-10"), held.state.value.stockRunOutDates[1L])
        assertFalse(movedOn.state.value.occurrences.any { it.epochMillis == eight })
        assertEquals(LocalDate.parse("2026-06-11"), movedOn.state.value.stockRunOutDates[1L])
    }

    // ---- Window arithmetic --------------------------------------------------

    @Test
    fun `with no registrations the window is the floor`() = runTest {
        val cache = buildCache(emptyList())
        advanceUntilIdle()
        // Guards against a vacuous pass: ProjectionState()'s own default
        // windowDays is already the floor, so asserting 28 alone would go
        // green even if no recompute ever ran -- which is exactly how the
        // first version of this suite fooled itself.
        assertEquals("no recompute ran", now, cache.state.value.computedAtEpochMillis)
        assertEquals(28L, cache.state.value.windowDays)
    }

    @Test
    fun `a consumer asking for fewer days than the floor does not shrink the window`() = runTest {
        // The R28 regression. Today registers 2; before the fix the effective
        // window became 2 for every reader of the shared projection.
        val cache = buildCache(emptyList())
        advanceUntilIdle()

        cache.requestWindow("today-screen", 2L)
        advanceUntilIdle()

        assertEquals(28L, cache.state.value.windowDays)
    }

    @Test
    fun `a consumer asking for more than the floor grows the window`() = runTest {
        val cache = buildCache(emptyList())
        advanceUntilIdle()

        cache.requestWindow("calendar", 90L)
        advanceUntilIdle()

        assertEquals(90L, cache.state.value.windowDays)
    }

    @Test
    fun `the largest registration wins regardless of order`() = runTest {
        val cache = buildCache(emptyList())
        advanceUntilIdle()

        cache.requestWindow("today-screen", 2L)
        cache.requestWindow("calendar", 90L)
        advanceUntilIdle()
        assertEquals(90L, cache.state.value.windowDays)

        cache.requestWindow("widget", 7L)
        advanceUntilIdle()
        assertEquals(90L, cache.state.value.windowDays)
    }

    @Test
    fun `releasing the largest registration returns to the floor on the next recompute`() = runTest {
        val cache = buildCache(emptyList())
        cache.requestWindow("calendar", 90L)
        advanceUntilIdle()
        assertEquals(90L, cache.state.value.windowDays)

        // releaseWindow deliberately does not force a recompute -- shrinking
        // isn't urgent -- so the next ordinary trigger is what applies it.
        cache.releaseWindow("calendar")
        cache.invalidate()
        advanceUntilIdle()

        assertEquals(28L, cache.state.value.windowDays)
    }

    // ---- What the shrinking actually cost -----------------------------------

    @Test
    fun `a stock run-out beyond the Today window is still projected`() = runTest {
        // 10 units, one unit a day: the run-out lands 10 days out, well past
        // Today's 2-day registration and comfortably inside the 28-day floor.
        // Before R28 this map was empty whenever Today was on screen, so
        // Stock & Expiration and the stock widget showed no run-out date at
        // all for a medicine about to run out.
        val cache = buildCache(listOf(medicineWithDailyDose(stockQuantity = 10.0, doseAmount = 1.0)))
        cache.requestWindow("today-screen", 2L)
        advanceUntilIdle()

        assertEquals(LocalDate.parse("2026-06-10"), cache.state.value.stockRunOutDates[1L])
    }

    @Test
    fun `a medicine that outlasts the window has no projected run-out`() = runTest {
        val cache = buildCache(listOf(medicineWithDailyDose(stockQuantity = 500.0, doseAmount = 1.0)))
        advanceUntilIdle()

        assertNull(cache.state.value.stockRunOutDates[1L])
    }

    @Test
    fun `an untracked medicine never gets a run-out date`() = runTest {
        val cache = buildCache(listOf(medicineWithDailyDose(stockQuantity = 1.0, doseAmount = 1.0, unit = null)))
        advanceUntilIdle()

        assertNull(cache.state.value.stockRunOutDates[1L])
    }


    // ---- Linked children (§9 #31, A-24) ------------------------------------

    private fun linkedPair(childEnabled: Boolean = true, behavior: IntervalStartBehavior = IntervalStartBehavior.ON_REMINDED) = MedicineWithReminders(
        medicine = Medicine(id = 1L, profileId = 1L, name = "Ibuprofen", iconKey = "pill", stockInfo = StockInfo(quantity = 10.0, unit = "tablet"), sortOrder = 0.0, createdAtEpochMillis = 0),
        reminders = listOf(
            Reminder(id = 1L, medicineId = 1L, type = ReminderType.FIXED_TIME, configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0)), createdAtEpochMillis = 0),
            Reminder(
                id = 2L, medicineId = 1L, parentReminderId = 1L, type = ReminderType.LINKED_FOLLOWING, enabled = childEnabled,
                configJson = encodeReminderConfig(ReminderConfig.LinkedFollowing(delayMinutes = 90, dose = 1.0, startBehavior = behavior)), createdAtEpochMillis = 0,
            ),
        ),
    )

    @Test
    fun `a linked child is estimated ninety minutes after each projected parent slot`() = runTest {
        val cache = buildCache(listOf(linkedPair()))
        advanceUntilIdle()
        val child = cache.state.value.occurrences.filter { it.reminderId == 2L }
        val parent = cache.state.value.occurrences.filter { it.reminderId == 1L }
        assertEquals(parent.size, child.size)
        assertEquals(parent.map { it.epochMillis + 90 * 60_000L }, child.map { it.epochMillis })
        assertEquals(parent.map { it.epochMillis }, child.map { it.afterEpochMillis })
        assertEquals(true, child.all { it.estimated })
    }

    @Test
    fun `a disabled child projects nothing, estimated or not`() = runTest {
        val cache = buildCache(listOf(linkedPair(childEnabled = false)))
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), cache.state.value.occurrences.filter { it.reminderId == 2L })
    }

    @Test
    fun `an on-resolved child is estimated from the parent's slot too -- the resolution time is unknown`() = runTest {
        val cache = buildCache(listOf(linkedPair(behavior = IntervalStartBehavior.ON_RESOLVED)))
        advanceUntilIdle()
        val child = cache.state.value.occurrences.filter { it.reminderId == 2L }
        assertEquals(true, child.isNotEmpty() && child.all { it.estimated })
    }

    // ---- Budget per reminder, emission per medicine (§9 #31, A-41 / A-45) --

    private fun twoDailyReminders(medicineId: Long) = MedicineWithReminders(
        medicine = Medicine(id = medicineId, profileId = 1L, name = "M$medicineId", iconKey = "pill", sortOrder = 0.0, createdAtEpochMillis = 0),
        reminders = listOf(8, 20).mapIndexed { i, hour ->
            Reminder(id = medicineId * 10 + i, medicineId = medicineId, type = ReminderType.FIXED_TIME, configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = hour, minute = 0, dose = 1.0)), createdAtEpochMillis = 0)
        },
    )

    @Test
    fun `the budget is checked before each reminder -- the second of a medicine's two is the one cut off`() = runTest {
        // Clock: the pass starts at `now`; the first reminder's check is within
        // budget, the second's is past it. The first reminder's slots survive.
        val readings = ArrayDeque(listOf(now, now, now + 3_000L))
        val cache = buildCache(listOf(twoDailyReminders(1))) { readings.removeFirstOrNull() ?: (now + 3_000L) }
        advanceUntilIdle()
        val state = cache.state.value
        // The first reminder's slots survive the trip: a partial answer, not a lost one.
        assertEquals(setOf(10L), state.occurrences.map { it.reminderId }.toSet())
        assertEquals(true, state.truncated)
        assertEquals(false, state.inProgress)
    }

    @Test
    fun `the state is published after each medicine, in progress, then once complete`() = runTest {
        val seen = mutableListOf<ProjectionState>()
        val cache = buildCache(listOf(twoDailyReminders(1), twoDailyReminders(2), twoDailyReminders(3)))
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { cache.state.collect { seen += it } }
        advanceUntilIdle()
        collector.cancel()
        val published = seen.filter { it.computedAtEpochMillis == now }
        assertEquals(listOf(true, true, true, false), published.map { it.inProgress })
        assertEquals(listOf(1, 2, 3, 3), published.map { s -> s.occurrences.map { it.medicineId }.distinct().size })
    }
}
