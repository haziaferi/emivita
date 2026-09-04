package com.hazia.emivita.core.scheduling

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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Covers the shared-window arithmetic recorded in spec §7 R28.
 *
 * The projection is shared: Today, Stock & Expiration and both widgets read
 * one [ProjectionState]. A [OccurrenceProjectionCache.requestWindow]
 * registration therefore has to mean "I need at least this many days", never
 * "this many is all anyone needs" -- and until R28 the minimum applied only
 * when no consumer had registered at all, so Today's 2-day request collapsed
 * the window for every other reader the moment Today composed.
 *
 * No Robolectric: the cache touches no Android types. The dispatcher is
 * injected so the 1s debounce runs on virtual time rather than really
 * sleeping, and the cache is launched into [TestScope.backgroundScope]
 * because its trigger loop never terminates and would otherwise hang the
 * test at teardown.
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

    private fun TestScope.buildCache(medicines: List<MedicineWithReminders>): OccurrenceProjectionCache {
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
            zone = zone,
            nowEpochMillis = { now },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
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
}
