package com.hazia.emivita.feature.medicines

import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.scheduling.ProjectedOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** The Medicines tab's card state and ordering (spec §9 #24). Pure. */
class MedicineListStateTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val today: LocalDate = LocalDate.of(2026, 9, 12)
    private val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = LocalDateTime.of(2026, 9, 12, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(h: Int) = LocalDateTime.of(2026, 9, 12, h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun medicine(id: Long, name: String = "M$id", sortOrder: Double = id.toDouble(), stock: StockInfo = StockInfo()) =
        Medicine(id = id, profileId = 1, name = name, iconKey = "pill", stockInfo = stock, sortOrder = sortOrder, createdAtEpochMillis = 0)

    private fun event(medicineId: Long, status: DoseStatus, scheduled: Long) =
        DoseEvent(reminderId = 9L, medicineId = medicineId, scheduledEpochMillis = scheduled, doseAmount = 1.0, status = status, createdAtEpochMillis = 0)

    private fun state(m: Medicine, reminders: List<Reminder> = emptyList(), events: List<DoseEvent> = emptyList(),
                      projected: List<ProjectedOccurrence> = emptyList(), runOut: LocalDate? = null) =
        cardStateFor(m, reminders, events, projected, runOut, today, now, dayStart, dayEnd)

    @Test
    fun `today status -- due beats done, done needs nothing left, upcoming when only later doses remain`() {
        val m = medicine(1)
        assertEquals(TodayStatus.NONE, state(m).today)
        assertEquals(TodayStatus.DUE, state(m, events = listOf(event(1, DoseStatus.PENDING, at(8)))).today)
        assertEquals(TodayStatus.DONE, state(m, events = listOf(event(1, DoseStatus.TAKEN, at(8)))).today)
        val later = ProjectedOccurrence(reminderId = 9, medicineId = 1, epochMillis = at(20), doseAmount = 1.0)
        assertEquals(TodayStatus.UPCOMING, state(m, events = listOf(event(1, DoseStatus.TAKEN, at(8))), projected = listOf(later)).today)
        assertEquals(TodayStatus.UPCOMING, state(m, projected = listOf(later)).today)
    }

    @Test
    fun `a projected slot already past is one the phone is holding, and due (R140)`() {
        val m = medicine(1)
        val held = ProjectedOccurrence(reminderId = 9, medicineId = 1, epochMillis = at(8), doseAmount = 1.0)
        assertEquals(TodayStatus.DUE, state(m, projected = listOf(held)).today)
        val yesterday = held.copy(epochMillis = dayStart - 60_000L)
        assertEquals("yesterday's is not today's", TodayStatus.NONE, state(m, projected = listOf(yesterday)).today)
        assertEquals("an estimate is not held", TodayStatus.NONE, state(m, projected = listOf(held.copy(estimated = true))).today)
    }

    @Test
    fun `a PRN dose does not count as today's scheduled answer`() {
        val prn = DoseEvent(reminderId = null, medicineId = 1, scheduledEpochMillis = at(9), doseAmount = 1.0, status = DoseStatus.TAKEN, createdAtEpochMillis = 0)
        assertEquals(TodayStatus.NONE, state(medicine(1), events = listOf(prn)).today)
    }

    @Test
    fun `empty means tracked and zero -- an untracked medicine is never empty`() {
        assertTrue(state(medicine(1, stock = StockInfo(quantity = 0.0, unit = "tablet"))).empty)
        assertFalse(state(medicine(1, stock = StockInfo(quantity = 0.0, unit = null))).empty)
        assertFalse(state(medicine(1, stock = StockInfo(quantity = 3.0, unit = "tablet"))).empty)
    }

    @Test
    fun `expired is strictly before today and runs-out only within the week`() {
        assertTrue(state(medicine(1, stock = StockInfo(expirationDateEpochDay = today.minusDays(1).toEpochDay()))).expired)
        assertFalse(state(medicine(1, stock = StockInfo(expirationDateEpochDay = today.toEpochDay()))).expired)
        // §9 #27: opened + shelf life makes an in-date pack expired
        assertTrue(state(medicine(1, stock = StockInfo(expirationDateEpochDay = today.plusDays(300).toEpochDay(), openedDateEpochDay = today.minusDays(30).toEpochDay(), useWithinDaysAfterOpening = 28))).expired)
        assertEquals(3L, state(medicine(1), runOut = today.plusDays(3)).runsOutInDays)
        assertEquals(0L, state(medicine(1), runOut = today).runsOutInDays)
        assertNull(state(medicine(1), runOut = today.plusDays(RUN_OUT_SOON_DAYS + 1)).runsOutInDays)
        assertNull(state(medicine(1), runOut = null).runsOutInDays)
    }

    @Test
    fun `course state -- a future start wins, then an end date, then the cycle day`() {
        fun fixed(from: Long? = null, until: Long? = null, pattern: RepeatPattern = RepeatPattern.Daily) = Reminder(
            medicineId = 1, type = ReminderType.FIXED_TIME,
            configJson = encodeReminderConfig(ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0, repeatPattern = pattern)),
            activeFromEpochDay = from, activeUntilEpochDay = until, createdAtEpochMillis = 0,
        )
        assertEquals(CourseState.None, courseStateFor(emptyList(), today))
        assertEquals(CourseState.StartsOn(today.plusDays(2)), courseStateFor(listOf(fixed(from = today.plusDays(2).toEpochDay())), today))
        assertEquals(CourseState.Until(today.plusDays(5)), courseStateFor(listOf(fixed(until = today.plusDays(5).toEpochDay())), today))
        // a range that already ended says nothing
        assertEquals(CourseState.None, courseStateFor(listOf(fixed(until = today.minusDays(1).toEpochDay())), today))
        // 21 on / 7 off started 30 days ago: day 31 of the cycle wraps to day 3
        val cyc = RepeatPattern.CyclicActivePause(firstCycleStartEpochDay = today.minusDays(30).toEpochDay(), activeDays = 21, pauseDays = 7)
        assertEquals(CourseState.CycleDay(3, 21), courseStateFor(listOf(fixed(pattern = cyc)), today))
        // a paused reminder does not report
        assertEquals(CourseState.None, courseStateFor(listOf(fixed(until = today.plusDays(5).toEpochDay()).copy(enabled = false)), today))
    }

    private fun entry(m: Medicine, next: Long? = null, empty: Boolean = false, runsOut: Long? = null) =
        MedicineListEntry(m, emptyList(), MedicineCardState(TodayStatus.NONE, expired = false, empty = empty, runsOutInDays = runsOut, course = CourseState.None, nextDoseEpochMillis = next))

    private fun tagged(m: Medicine, vararg tags: String) = MedicineListEntry(m, tags.toList(), MedicineCardState(TodayStatus.NONE, expired = false, empty = false, runsOutInDays = null, course = CourseState.None, nextDoseEpochMillis = null))

    @Test
    fun `grouping -- one section per tag in collation order, a two-tag medicine under both, untagged last`() {
        val a = tagged(medicine(1, "Aspirin", sortOrder = 2.0), "morning", "travel")
        val b = tagged(medicine(2, "Bisoprolol", sortOrder = 1.0), "morning")
        val c = tagged(medicine(3, "Cetirizine", sortOrder = 3.0))
        val d = tagged(medicine(4, "Élan", sortOrder = 4.0), "évening")
        val sections = groupByTag(sortEntries(listOf(a, b, c, d), MedicinesSort.GROUP_BY_TAG, Locale.US), Locale.US)
        assertEquals(listOf("évening", "morning", "travel", null), sections.map { it.name })
        assertEquals("manual order inside a section", listOf(2L, 1L), sections[1].entries.map { it.medicine.id })
        assertEquals(listOf(1L), sections[2].entries.map { it.medicine.id })
        assertEquals(listOf(3L), sections.last().entries.map { it.medicine.id })
        assertTrue("no untagged bucket when nothing is untagged", groupByTag(listOf(a, b), Locale.US).none { it.name == null })
    }

    @Test
    fun `filtering -- the query reaches name, strength, indication and tags, case-insensitively`() {
        val a = tagged(medicine(1, "Sertraline").copy(strength = "50 mg", indication = "mood"), "morning")
        val b = tagged(medicine(2, "Ibuprofen").copy(strength = "400 mg"), "pain")
        val all = listOf(a, b)
        assertEquals(listOf(1L), filterEntries(all, "sert", emptySet()).map { it.medicine.id })
        assertEquals(listOf(2L), filterEntries(all, "400", emptySet()).map { it.medicine.id })
        assertEquals(listOf(1L), filterEntries(all, "MOOD", emptySet()).map { it.medicine.id })
        assertEquals(listOf(2L), filterEntries(all, " pain ", emptySet()).map { it.medicine.id })
        assertEquals("blank passes everything", 2, filterEntries(all, "   ", emptySet()).size)
        assertTrue(filterEntries(all, "zzz", emptySet()).isEmpty())
    }

    @Test
    fun `filtering -- selected tags pass a medicine carrying any of them, and combine with the query`() {
        val a = tagged(medicine(1, "Aspirin"), "morning", "travel")
        val b = tagged(medicine(2, "Bisoprolol"), "morning")
        val c = tagged(medicine(3, "Cetirizine"))
        val all = listOf(a, b, c)
        assertEquals(listOf(1L, 2L), filterEntries(all, "", setOf("morning")).map { it.medicine.id })
        assertEquals(listOf(1L), filterEntries(all, "", setOf("travel")).map { it.medicine.id })
        assertEquals("any, not all", listOf(1L, 2L), filterEntries(all, "", setOf("travel", "morning")).map { it.medicine.id })
        assertEquals(listOf(2L), filterEntries(all, "biso", setOf("morning")).map { it.medicine.id })
        assertTrue("a tag nobody carries hides everything", filterEntries(all, "", setOf("gone")).isEmpty())
    }

    @Test
    fun `sorting -- manual by sortOrder, name by collator, next dose soonest first with none last, stock by need`() {
        val a = entry(medicine(1, "Zoloft", sortOrder = 2.0), next = at(18))
        val b = entry(medicine(2, "aspirin", sortOrder = 1.0), next = at(12))
        val c = entry(medicine(3, "Élan", sortOrder = 3.0), next = null, empty = true)
        val d = entry(medicine(4, "Bisoprolol", sortOrder = 4.0), next = null, runsOut = 2)
        val all = listOf(a, b, c, d)
        assertEquals(listOf(2L, 1L, 3L, 4L), sortEntries(all, MedicinesSort.MANUAL, Locale.US).map { it.medicine.id })
        assertEquals(listOf("aspirin", "Bisoprolol", "Élan", "Zoloft"), sortEntries(all, MedicinesSort.NAME, Locale.US).map { it.medicine.name })
        assertEquals(listOf(2L, 1L, 3L, 4L), sortEntries(all, MedicinesSort.NEXT_DOSE, Locale.US).map { it.medicine.id })
        assertEquals(listOf(3L, 4L, 2L, 1L), sortEntries(all, MedicinesSort.EXPIRY_STOCK, Locale.US).map { it.medicine.id })
    }

    @Test
    fun `a moved item takes the midpoint of its new neighbours, or one past the end`() {
        // list after the move, moved item at index 1 with its OLD value 9.0
        assertEquals(1.5, sortOrderForMove(listOf(1.0, 9.0, 2.0), 1), 0.0)
        assertEquals(0.0, sortOrderForMove(listOf(9.0, 1.0, 2.0), 0), 0.0)
        assertEquals(3.0, sortOrderForMove(listOf(1.0, 2.0, 9.0), 2), 0.0)
        assertEquals(9.0, sortOrderForMove(listOf(9.0), 0), 0.0)
    }
}
