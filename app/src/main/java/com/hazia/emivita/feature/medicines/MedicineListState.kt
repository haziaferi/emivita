package com.hazia.emivita.feature.medicines

import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.scheduling.ProjectedOccurrence
import java.time.LocalDate

/**
 * What the Medicines tab shows and how it orders it (spec §9 #24, owner's
 * choices; Appendix F DT-04, HM-06/07/08, DS-08 and the owner's own "about
 * to run out"). Pure functions over plain values so the rules are tested on
 * the JVM; `MedicinesScreen` only renders what these return.
 */

/** Sort keys the owner picked. Stored by name (§9 #24). */
enum class MedicinesSort { MANUAL, NAME, NEXT_DOSE, EXPIRY_STOCK, GROUP_BY_TAG }

/** Today's answer for one medicine, from its materialised dose events and
 *  the projection: nothing scheduled today, a dose still due, all answered,
 *  or doses only later today. */
enum class TodayStatus { NONE, DUE, DONE, UPCOMING }

/** How far a course has got, for the card's one-line state (DS-08). */
sealed interface CourseState {
    data class StartsOn(val date: LocalDate) : CourseState
    data class Until(val date: LocalDate) : CourseState
    data class CycleDay(val day: Int, val activeDays: Int) : CourseState
    data object None : CourseState
}

data class MedicineCardState(
    val today: TodayStatus,
    val expired: Boolean,
    val empty: Boolean,
    /** Days until the projected run-out when it is within [RUN_OUT_SOON_DAYS]; null otherwise. */
    val runsOutInDays: Long?,
    val course: CourseState,
    /** The soonest projected dose at or after now; what NEXT_DOSE sorts on. */
    val nextDoseEpochMillis: Long?,
)

const val RUN_OUT_SOON_DAYS = 7L

fun cardStateFor(
    medicine: Medicine,
    reminders: List<Reminder>,
    todayEvents: List<DoseEvent>,
    projected: List<ProjectedOccurrence>,
    runOutDate: LocalDate?,
    today: LocalDate,
    nowEpochMillis: Long,
    todayStartEpochMillis: Long,
    todayEndEpochMillis: Long,
): MedicineCardState {
    val mine = todayEvents.filter { it.medicineId == medicine.id && it.planned }
    val projectedToday = projected.any {
        it.medicineId == medicine.id && it.epochMillis >= nowEpochMillis && it.epochMillis in todayStartEpochMillis until todayEndEpochMillis
    }
    val todayStatus = when {
        mine.any { it.status == DoseStatus.PENDING } -> TodayStatus.DUE
        mine.isNotEmpty() && !projectedToday -> TodayStatus.DONE
        mine.isNotEmpty() || projectedToday -> TodayStatus.UPCOMING
        else -> TodayStatus.NONE
    }
    val tracked = medicine.stockInfo.unit != null
    val expired = medicine.stockInfo.effectiveExpirationEpochDay?.let { it < today.toEpochDay() } == true
    val empty = tracked && medicine.stockInfo.quantity <= 0.0
    val runsOutInDays = runOutDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }?.takeIf { it in 0..RUN_OUT_SOON_DAYS }
    val nextDose = projected.filter { it.medicineId == medicine.id && it.epochMillis >= nowEpochMillis }.minOfOrNull { it.epochMillis }
    return MedicineCardState(
        today = todayStatus,
        expired = expired,
        empty = empty,
        runsOutInDays = runsOutInDays,
        course = courseStateFor(reminders, today),
        nextDoseEpochMillis = nextDose,
    )
}

/** The dosing reminders' range and cycle, folded to one line: a future
 *  start wins (nothing is happening yet), then an end date, then a cycle. */
fun courseStateFor(reminders: List<Reminder>, today: LocalDate): CourseState {
    val dosing = reminders.filter { it.enabled && it.type != ReminderType.LOW_STOCK_ALERT && it.type != ReminderType.EXPIRATION_ALERT }
    if (dosing.isEmpty()) return CourseState.None
    val epochToday = today.toEpochDay()
    dosing.mapNotNull { it.activeFromEpochDay }.filter { it > epochToday }.minOrNull()
        ?.let { return CourseState.StartsOn(LocalDate.ofEpochDay(it)) }
    dosing.mapNotNull { it.activeUntilEpochDay }.filter { it >= epochToday }.maxOrNull()
        ?.let { return CourseState.Until(LocalDate.ofEpochDay(it)) }
    for (r in dosing) {
        val cfg = runCatching { decodeReminderConfig(r.configJson) }.getOrNull() as? ReminderConfig.FixedTime ?: continue
        val cyc = cfg.repeatPattern as? RepeatPattern.CyclicActivePause ?: continue
        val length = cyc.activeDays + cyc.pauseDays
        if (length <= 0) continue
        val day = Math.floorMod(epochToday - cyc.firstCycleStartEpochDay, length.toLong()).toInt() + 1
        return CourseState.CycleDay(day, cyc.activeDays)
    }
    return CourseState.None
}

/** One list entry with everything the card and the sort need. */
data class MedicineListEntry(
    val medicine: Medicine,
    val tagNames: List<String>,
    val state: MedicineCardState,
)

fun sortEntries(entries: List<MedicineListEntry>, sort: MedicinesSort, locale: java.util.Locale): List<MedicineListEntry> = when (sort) {
    // Grouped mode keeps manual order inside each section; the sections
    // themselves come from [groupByTag].
    MedicinesSort.MANUAL, MedicinesSort.GROUP_BY_TAG -> entries.sortedBy { it.medicine.sortOrder }
    MedicinesSort.NAME -> {
        val collator = java.text.Collator.getInstance(locale)
        entries.sortedWith(compareBy(collator) { it.medicine.name })
    }
    // Soonest next dose first; a medicine with nothing projected goes last,
    // in manual order among themselves so the tail is stable.
    MedicinesSort.NEXT_DOSE -> entries.sortedWith(
        compareBy<MedicineListEntry> { it.state.nextDoseEpochMillis == null }
            .thenBy { it.state.nextDoseEpochMillis ?: Long.MAX_VALUE }
            .thenBy { it.medicine.sortOrder },
    )
    // What needs attention first: empty, then soonest run-out, then soonest
    // expiry; untracked and undated last (HM-06, DS-07).
    MedicinesSort.EXPIRY_STOCK -> entries.sortedWith(
        compareBy<MedicineListEntry> { !it.state.empty }
            .thenBy { it.state.runsOutInDays ?: Long.MAX_VALUE }
            .thenBy { it.medicine.stockInfo.effectiveExpirationEpochDay ?: Long.MAX_VALUE }
            .thenBy { it.medicine.sortOrder },
    )
}

/** medTimer's `MedicineRepositoryImpl.move()` shape, re-implemented (MIT, an
 *  idea small enough to state in one sentence): a dragged item takes the
 *  midpoint of its new neighbours' sort orders, or ±1 past the end, so one
 *  row changes and nothing is renumbered. Returns the new sortOrder for the
 *  item now at [toIndex] in [orderedSortOrders] (the list AFTER the move,
 *  with the moved item's old value still in place). */
fun sortOrderForMove(orderedSortOrders: List<Double>, toIndex: Int): Double {
    val prev = orderedSortOrders.getOrNull(toIndex - 1)
    val next = orderedSortOrders.getOrNull(toIndex + 1)
    return when {
        prev == null && next == null -> orderedSortOrders.getOrElse(toIndex) { 0.0 }
        prev == null -> next!! - 1.0
        next == null -> prev + 1.0
        else -> (prev + next) / 2.0
    }
}

/** One section of the grouped list (§9 #27; HM-01's kits, on tags): [name]
 *  null is the "Untagged" bucket. */
data class TagSection(val name: String?, val entries: List<MedicineListEntry>)

/**
 * Tags as kits: one section per tag in the locale's collation order, a
 * medicine with two tags appearing under both (a kit is a place a medicine
 * *can* be found, not the one place it *is*), the untagged bucket last.
 * Entries keep the order they arrive in, so callers sort first.
 */
fun groupByTag(entries: List<MedicineListEntry>, locale: java.util.Locale): List<TagSection> {
    val collator = java.text.Collator.getInstance(locale)
    val byTag = linkedMapOf<String, MutableList<MedicineListEntry>>()
    val untagged = mutableListOf<MedicineListEntry>()
    for (entry in entries) {
        if (entry.tagNames.isEmpty()) untagged += entry
        for (tag in entry.tagNames) byTag.getOrPut(tag) { mutableListOf() } += entry
    }
    val sections = byTag.keys.sortedWith(collator).map { TagSection(it, byTag.getValue(it)) }
    return if (untagged.isEmpty()) sections else sections + TagSection(null, untagged)
}

/**
 * The live search and the tag filter (§9 #27, HM-13 without FTS, HM-02):
 * a query matches case-insensitively anywhere in the name, the strength,
 * the indication or a tag name, and a selected tag set passes an entry that
 * carries **any** of them ("show me the morning kit and the travel kit").
 * A blank query and an empty set pass everything. The query is trimmed but
 * the tag comparison is exact, since tags are chosen, not typed.
 */
fun filterEntries(entries: List<MedicineListEntry>, query: String, selectedTags: Set<String>): List<MedicineListEntry> {
    val q = query.trim()
    return entries.filter { e ->
        val tagOk = selectedTags.isEmpty() || e.tagNames.any { it in selectedTags }
        val queryOk = q.isEmpty() || listOfNotNull(e.medicine.name, e.medicine.strength, e.medicine.indication)
            .plus(e.tagNames)
            .any { it.contains(q, ignoreCase = true) }
        tagOk && queryOk
    }
}
