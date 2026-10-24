package com.hazia.emivita.feature.reminders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.ReminderRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.annotation.PluralsRes
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextOverflow
import com.hazia.emivita.core.data.config.nextDates
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.ui.form.FieldHelpLine
import com.hazia.emivita.ui.form.MonthDayGrid
import com.hazia.emivita.R
import com.hazia.emivita.core.format.labelRes
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.ui.components.rememberDirty
import com.hazia.emivita.ui.form.FieldPair
import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.ui.form.FormTimeField
import com.hazia.emivita.ui.form.GridToggleCell
import com.hazia.emivita.ui.form.SpanGrid
import com.hazia.emivita.ui.form.WeekdayGrid

@Composable
private fun DoseField(dose: String, doseUnit: String? = null, onChange: (String) -> Unit) {
    val value = parseDecimal(dose)
    FormTextField(
        dose, onChange, stringResource(R.string.common_dose_amount), suffix = doseUnit, keyboardType = KeyboardType.Decimal,
        error = if (value == null || value <= 0) stringResource(R.string.quick_add_dose_invalid) else null, reserve = true,
    )
}

@Composable
private fun rememberAdvancedReminderFieldsState(initialReminder: Reminder?): AdvancedReminderFieldsState =
    rememberSaveable(initialReminder, saver = AdvancedReminderFieldsState.Saver) { AdvancedReminderFieldsState(initialReminder) }

@Composable
private fun StartBehaviorPicker(value: IntervalStartBehavior, onChange: (IntervalStartBehavior) -> Unit) {
    Column {
        Text(stringResource(R.string.add_reminder_interval_starts), style = MaterialTheme.typography.labelLarge)
        RadioRow(stringResource(R.string.add_reminder_when_reminded), value == IntervalStartBehavior.ON_REMINDED) { onChange(IntervalStartBehavior.ON_REMINDED) }
        RadioRow(stringResource(R.string.add_reminder_when_taken_or_skipped), value == IntervalStartBehavior.ON_RESOLVED) { onChange(IntervalStartBehavior.ON_RESOLVED) }
    }
}

/**
 * The repeat kinds of a fixed-time reminder, in the order of their cells. Daily and every N days
 * stand alone; the days of the week, the cycle and the days of the month combine, every one chosen
 * having to hold (§7 R127, A-10 + A-13 V2).
 */
private enum class RepeatKind(val label: Int, val combines: Boolean) {
    DAILY(R.string.common_daily, false),
    WEEKDAYS(R.string.add_reminder_specific_days, true),
    EVERY_N_DAYS(R.string.add_reminder_every_n_days, false),
    CYCLE(R.string.add_reminder_active_pause_cycle, true),
    DAYS_OF_MONTH(R.string.add_reminder_days_of_month, true),
}

/** The kinds an existing pattern shows as chosen. */
private fun kindsOf(pattern: RepeatPattern): Set<RepeatKind> = when (pattern) {
    RepeatPattern.Daily -> setOf(RepeatKind.DAILY)
    is RepeatPattern.SpecificDaysOfWeek -> setOf(RepeatKind.WEEKDAYS)
    is RepeatPattern.EveryNDays -> setOf(RepeatKind.EVERY_N_DAYS)
    is RepeatPattern.CyclicActivePause -> setOf(RepeatKind.CYCLE)
    is RepeatPattern.DayRules -> setOfNotNull(
        pattern.weekdays?.let { RepeatKind.WEEKDAYS },
        pattern.cycle?.let { RepeatKind.CYCLE },
        pattern.daysOfMonth?.let { RepeatKind.DAYS_OF_MONTH },
    ).ifEmpty { setOf(RepeatKind.DAILY) }
}

/** A tap on [k]: a kind that stands alone replaces the rest; a rule toggles among the rules, and none left is Daily. */
private fun Set<RepeatKind>.tapped(k: RepeatKind): Set<RepeatKind> {
    if (!k.combines) return setOf(k)
    val rules = filter { it.combines }.toSet()
    return (if (k in rules) rules - k else rules + k).ifEmpty { setOf(RepeatKind.DAILY) }
}

/**
 * The pattern the chosen rules make: one rule alone keeps its own stored shape (so a weekdays-only
 * or cycle-only reminder is stored exactly as before), two or more are [RepeatPattern.DayRules].
 */
private fun rulesPattern(kinds: Set<RepeatKind>, weekdays: Set<Int>, cycle: RepeatPattern.CyclicActivePause, daysOfMonth: Set<Int>): RepeatPattern {
    val rules = RepeatPattern.DayRules(
        weekdays = weekdays.takeIf { RepeatKind.WEEKDAYS in kinds },
        cycle = cycle.takeIf { RepeatKind.CYCLE in kinds },
        daysOfMonth = daysOfMonth.takeIf { RepeatKind.DAYS_OF_MONTH in kinds },
    )
    return when (kinds) {
        setOf(RepeatKind.WEEKDAYS) -> RepeatPattern.SpecificDaysOfWeek(weekdays)
        setOf(RepeatKind.CYCLE) -> cycle
        else -> rules
    }
}

/** The dates the rules give, as [RepeatPattern.DayRules] whatever shape they are stored in. */
private fun asRules(pattern: RepeatPattern): RepeatPattern.DayRules? = when (pattern) {
    is RepeatPattern.DayRules -> pattern
    is RepeatPattern.SpecificDaysOfWeek -> RepeatPattern.DayRules(weekdays = pattern.days)
    is RepeatPattern.CyclicActivePause -> RepeatPattern.DayRules(cycle = pattern)
    else -> null
}

/**
 * "Prossimi: 1 nov, 15 nov, 1 dic", or in the error colour that the rules never meet (§7 R127, A-10 V2).
 * Under the cycle's fields it is their help line, at their text inset; under a grid it starts on the edge.
 */
@Composable
private fun NextDatesLine(dates: List<LocalDate>, underField: Boolean) {
    val text = stringResource(R.string.add_reminder_next_dates, dates.joinToString(", ") { LocaleFormats.monthDay(it) })
    val never = stringResource(R.string.add_reminder_rules_never).takeIf { dates.isEmpty() }
    if (underField) {
        FieldHelpLine(text, never)
    } else {
        Text(
            never ?: text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (never != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Shared by AddReminderScreen (create) and EditReminderScreen (update) --
 *  differs only in [title]/[saveLabel]/[initial] and what [onSave] does with
 *  the resulting config. */
@Composable
internal fun FixedTimeFormBody(
    title: String,
    saveLabel: String,
    initial: ReminderConfig.FixedTime,
    initialReminder: Reminder? = null,
    doseUnit: String? = null,
    onSave: suspend (ReminderConfig.FixedTime, AdvancedFields) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val advanced = rememberAdvancedReminderFieldsState(initialReminder)
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var timeBad by remember { mutableStateOf(false) }
    var dose by rememberSaveable { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    val initialPattern = initial.repeatPattern
    val initialRules = asRules(initialPattern)
    var kinds by rememberSaveable { mutableStateOf(kindsOf(initialPattern)) }
    var selectedDays by rememberSaveable { mutableStateOf(initialRules?.weekdays ?: setOf()) }
    var daysOfMonth by rememberSaveable { mutableStateOf(initialRules?.daysOfMonth ?: setOf()) }
    var everyN by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.EveryNDays)?.n?.toString() ?: "2") }
    val everyNStartEpochDay = (initialPattern as? RepeatPattern.EveryNDays)?.startEpochDay ?: LocalDate.now().toEpochDay()
    var activeDays by rememberSaveable { mutableStateOf(initialRules?.cycle?.activeDays?.toString() ?: "21") }
    var pauseDays by rememberSaveable { mutableStateOf(initialRules?.cycle?.pauseDays?.toString() ?: "7") }
    // The first cycle's anchor is not editable: today for a new reminder, kept as it is when
    // editing one, as EveryNDays' own startEpochDay is above.
    val firstCycleStartEpochDay = initialRules?.cycle?.firstCycleStartEpochDay ?: LocalDate.now().toEpochDay()
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    val activeDaysValue = activeDays.toIntOrNull()
    val pauseDaysValue = pauseDays.toIntOrNull()
    // "0".toIntOrNull() is 0, not null: an unguarded Every-N-days field let n = 0 reach a
    // committed row, and matchingDateOnOrAfter's require(n >= 1) then threw on every projection
    // recompute (docs/SPEC.md §7 R23).
    val everyNValue = everyN.toIntOrNull()
    val everyNOk = everyNValue != null && everyNValue >= 1
    val activeOk = activeDaysValue != null && activeDaysValue >= 1
    // Zero pause days is a cycle with no pause, which the scheduler accepts (isActiveOn's require,
    // matchingDateOnOrAfter's guard): the field refuses only a negative or missing number (§7 R124).
    val pauseOk = pauseDaysValue != null && pauseDaysValue >= 0

    val showWeekdays = RepeatKind.WEEKDAYS in kinds
    val showCycle = RepeatKind.CYCLE in kinds
    val showDaysOfMonth = RepeatKind.DAYS_OF_MONTH in kinds
    val rulesOk = (!showWeekdays || selectedDays.isNotEmpty()) && (!showCycle || (activeOk && pauseOk)) && (!showDaysOfMonth || daysOfMonth.isNotEmpty())
    val cycle = RepeatPattern.CyclicActivePause(firstCycleStartEpochDay, activeDaysValue ?: 21, pauseDaysValue ?: 7)
    val anyRule = kinds.any { it.combines }
    // The next three dates the rules give, empty when they never meet; read only while every rule is complete.
    val pattern = rulesPattern(kinds, selectedDays, cycle, daysOfMonth)
    val nextDates = if (anyRule && rulesOk) asRules(pattern)?.nextDates(LocalDate.now(), 3) else null
    val never = nextDates?.isEmpty() == true

    val dirty = rememberDirty(Unit, hour, minute, dose, kinds, selectedDays, daysOfMonth, everyN, activeDays, pauseDays, advanced.snapshot())
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = !timeBad && doseValue != null && doseValue > 0 && rulesOk && !never &&
            (RepeatKind.EVERY_N_DAYS !in kinds || everyNOk) &&
            advanced.errors().isEmpty(),
        onSave = {
            val saved = when {
                RepeatKind.EVERY_N_DAYS in kinds -> RepeatPattern.EveryNDays(everyNValue ?: 2, everyNStartEpochDay)
                anyRule -> pattern
                else -> RepeatPattern.Daily
            }
            scope.launch {
                onSave(ReminderConfig.FixedTime(hour, minute, doseValue ?: 1.0, saved), advanced.snapshot())
            }
        },
        onCancel = onCancel,
    ) {
        FormTimeField(stringResource(R.string.add_reminder_time), hour, minute, onError = { timeBad = it }) { h, m -> hour = h; minute = m }
        Spacer(Modifier.height(8.dp))
        DoseField(dose, doseUnit) { dose = it }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.add_reminder_repeats), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        // The kinds on the day toggles' 7-column grid (§7 R124, C6): each takes the columns its
        // one line needs and every row ends on the right edge.
        SpanGrid {
            RepeatKind.entries.forEach { k -> GridToggleCell(stringResource(k.label), k in kinds) { kinds = kinds.tapped(k) } }
        }
        if (kinds.count { it.combines } > 1) {
            Text(
                stringResource(R.string.add_reminder_rules_combine),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (showWeekdays) {
            Spacer(Modifier.height(16.dp))
            WeekdayGrid(selectedDays) { selectedDays = it }
        }
        if (RepeatKind.EVERY_N_DAYS in kinds) {
            Spacer(Modifier.height(16.dp))
            FormTextField(
                everyN, { everyN = it }, stringResource(R.string.add_reminder_every_n_days), keyboardType = KeyboardType.Number,
                error = if (everyNOk) null else stringResource(R.string.form_min_one), reserve = true,
            )
        }
        if (showCycle) {
            Spacer(Modifier.height(16.dp))
            val a = stringResource(R.string.add_reminder_active_days)
            val p = stringResource(R.string.add_reminder_pause_days)
            // Said only while it is true: an existing cycle keeps the anchor it started on.
            val startsToday = firstCycleStartEpochDay == LocalDate.now().toEpochDay()
            val cycleHelp = stringResource(R.string.add_reminder_cycle_help).takeIf { startsToday }
            FieldPair(
                listOfNotNull(a, p, cycleHelp),
                first = { m ->
                    FormTextField(activeDays, { activeDays = it }, a, m, help = cycleHelp, keyboardType = KeyboardType.Number,
                        error = if (activeOk) null else stringResource(R.string.form_min_one), reserve = true, flagError = never)
                },
                second = { m ->
                    FormTextField(pauseDays, { pauseDays = it }, p, m, keyboardType = KeyboardType.Number,
                        error = if (pauseOk) null else stringResource(R.string.form_min_zero), reserve = true, flagError = never)
                },
            )
        }
        if (showDaysOfMonth) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.add_reminder_days_of_month), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            MonthDayGrid(daysOfMonth) { daysOfMonth = it }
        }
        if (nextDates != null) NextDatesLine(nextDates, underField = showCycle && !showDaysOfMonth)
        AdvancedReminderFieldsSection(advanced)
    }
}

/** The interval's units (§7 R127, D-12; medTimer's), each in minutes. */
internal enum class IntervalUnit(val minutes: Int, @PluralsRes val label: Int) {
    MINUTES(1, R.plurals.interval_unit_minutes),
    HOURS(60, R.plurals.interval_unit_hours),
    DAYS(1440, R.plurals.interval_unit_days),
    WEEKS(10080, R.plurals.interval_unit_weeks),
}

/** medTimer's `getUnit()`: the largest unit [minutes] is a whole number of, so 10080 reads "1 week". */
internal fun largestExactUnit(minutes: Int): IntervalUnit =
    IntervalUnit.entries.reversed().firstOrNull { minutes > 0 && minutes % it.minutes == 0 } ?: IntervalUnit.MINUTES

/** The minutes a typed count of [unit] stores, or null when it is not a whole number that fits. */
internal fun intervalMinutes(count: String, unit: IntervalUnit): Int? =
    count.trim().toLongOrNull()?.times(unit.minutes)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

/** The interval as typed: its count and its unit, opened in the largest exact unit of what is stored. */
private class IntervalInput(minutes: Int) {
    var unit by mutableStateOf(largestExactUnit(minutes))
    var count by mutableStateOf((minutes / largestExactUnit(minutes).minutes).toString())
    val minutes get() = intervalMinutes(count, unit)

    companion object {
        val Saver: Saver<IntervalInput, Any> = listSaver(
            save = { listOf(it.unit.name, it.count) },
            restore = { v -> IntervalInput(1).apply { unit = IntervalUnit.valueOf(v[0]); count = v[1] } },
        )
    }
}

/**
 * "Ogni" and its unit on the grid (§7 R127, D-12 V1): the stored value stays minutes. Under an hour
 * the field asks whether that is right, and Salva still saves (the owner: "Warn under 1 hour").
 */
@Composable
private fun IntervalField(input: IntervalInput) {
    val minutes = input.minutes
    val count = input.count.trim().toIntOrNull() ?: 0
    FormTextField(
        input.count, { input.count = it }, stringResource(R.string.add_reminder_every), keyboardType = KeyboardType.Number,
        help = minutes?.takeIf { it in 1..59 }?.let { pluralStringResource(R.plurals.add_reminder_interval_minutes_check, it, it) },
        error = if (minutes == null || minutes < 1) stringResource(R.string.form_min_one) else null, reserve = true,
    )
    Spacer(Modifier.height(8.dp))
    SpanGrid {
        IntervalUnit.entries.forEach { u -> GridToggleCell(pluralStringResource(u.label, count, count), input.unit == u) { input.unit = u } }
    }
}

@Composable
internal fun RepeatingIntervalFormBody(
    title: String,
    saveLabel: String,
    initial: ReminderConfig.RepeatingInterval,
    initialReminder: Reminder? = null,
    doseUnit: String? = null,
    onSave: suspend (ReminderConfig.RepeatingInterval, AdvancedFields) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val advanced = rememberAdvancedReminderFieldsState(initialReminder)
    val interval = rememberSaveable(saver = IntervalInput.Saver) { IntervalInput(initial.intervalMinutes) }
    var dose by rememberSaveable { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    var startBehavior by rememberSaveable { mutableStateOf(initial.startBehavior) }
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    // Guard the value actually stored: a zero is a reminder that silently never fires (docs/SPEC.md §7 R23, R33).
    val intervalMinutesValue = interval.minutes

    val dirty = rememberDirty(Unit, interval.unit, interval.count, dose, startBehavior, advanced.snapshot())
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = doseValue != null && doseValue > 0 &&
            intervalMinutesValue != null && intervalMinutesValue >= 1 && advanced.errors().isEmpty(),
        onSave = {
            scope.launch {
                onSave(
                    ReminderConfig.RepeatingInterval(
                        intervalMinutes = intervalMinutesValue ?: 480,
                        dose = doseValue ?: 1.0,
                        startBehavior = startBehavior,
                        // Editing doesn't reset the clock -- keep the original anchor
                        // rather than re-anchoring to "now" every time the form is
                        // opened and saved without a real schedule change.
                        anchorEpochMillis = initial.anchorEpochMillis,
                    ),
                    advanced.snapshot(),
                )
            }
        },
        onCancel = onCancel,
    ) {
        IntervalField(interval)
        Spacer(Modifier.height(8.dp))
        DoseField(dose, doseUnit) { dose = it }
        Spacer(Modifier.height(8.dp))
        StartBehaviorPicker(startBehavior) { startBehavior = it }
        AdvancedReminderFieldsSection(advanced)
    }
}

@Composable
internal fun DailyWindowFormBody(
    title: String,
    saveLabel: String,
    initial: ReminderConfig.DailyWindow,
    initialReminder: Reminder? = null,
    doseUnit: String? = null,
    onSave: suspend (ReminderConfig.DailyWindow, AdvancedFields) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val advanced = rememberAdvancedReminderFieldsState(initialReminder)
    val interval = rememberSaveable(saver = IntervalInput.Saver) { IntervalInput(initial.intervalMinutes) }
    var dose by rememberSaveable { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    var startHour by rememberSaveable { mutableIntStateOf(initial.windowStartHour) }
    var startMinute by rememberSaveable { mutableIntStateOf(initial.windowStartMinute) }
    var endHour by rememberSaveable { mutableIntStateOf(initial.windowEndHour) }
    var endMinute by rememberSaveable { mutableIntStateOf(initial.windowEndMinute) }
    var startBad by remember { mutableStateOf(false) }
    var endBad by remember { mutableStateOf(false) }
    var startBehavior by rememberSaveable { mutableStateOf(initial.startBehavior) }
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    // The stored minutes are guarded, as in RepeatingIntervalFormBody (§7 R23).
    val intervalMinutesValue = interval.minutes

    val dirty = rememberDirty(Unit, interval.unit, interval.count, dose, startHour, startMinute, endHour, endMinute, startBehavior, advanced.snapshot())
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = doseValue != null && doseValue > 0 && !startBad && !endBad &&
            intervalMinutesValue != null && intervalMinutesValue >= 1 && advanced.errors().isEmpty(),
        onSave = {
            scope.launch {
                onSave(
                    ReminderConfig.DailyWindow(
                        intervalMinutes = intervalMinutesValue ?: 240,
                        dose = doseValue ?: 1.0,
                        windowStartHour = startHour,
                        windowStartMinute = startMinute,
                        windowEndHour = endHour,
                        windowEndMinute = endMinute,
                        startBehavior = startBehavior,
                    ),
                    advanced.snapshot(),
                )
            }
        },
        onCancel = onCancel,
    ) {
        IntervalField(interval)
        Spacer(Modifier.height(8.dp))
        DoseField(dose, doseUnit) { dose = it }
        Spacer(Modifier.height(8.dp))
        val ws = stringResource(R.string.common_window_start)
        val we = stringResource(R.string.common_window_end)
        FieldPair(
            listOf(ws, we, "00:00"), iconDp = 48.dp,
            first = { m -> FormTimeField(ws, startHour, startMinute, m, onError = { startBad = it }) { h, mi -> startHour = h; startMinute = mi } },
            second = { m -> FormTimeField(we, endHour, endMinute, m, onError = { endBad = it }) { h, mi -> endHour = h; endMinute = mi } },
        )
        WindowEndsNextDayNote(startHour, startMinute, endHour, endMinute)
        Spacer(Modifier.height(8.dp))
        StartBehaviorPicker(startBehavior) { startBehavior = it }
        AdvancedReminderFieldsSection(advanced)
    }
}

/** Chained "following dose" reminders (spec §3/§9 #14). No RepeatPattern
 *  selector like Fixed Time's -- there's nothing to repeat here, the
 *  schedule is entirely
 *  derived from whichever sibling reminder is picked as the parent. The
 *  parent picker is scoped to the same medicine's own reminders (including
 *  other LinkedFollowing ones -- chains of arbitrary length compose for
 *  free, spec §3) rather than a global picker across every medicine, since
 *  a follow-up dose almost always means "after this same medicine's own
 *  dose" and a cross-medicine picker would need a medicine selector on top
 *  of a reminder selector for a case that hasn't come up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkedFollowingFormBody(
    title: String,
    saveLabel: String,
    medicineId: Long,
    excludeReminderId: Long?,
    reminderRepository: ReminderRepository,
    initial: ReminderConfig.LinkedFollowing,
    initialParentReminderId: Long?,
    initialReminder: Reminder? = null,
    doseUnit: String? = null,
    onSave: suspend (parentReminderId: Long, ReminderConfig.LinkedFollowing, AdvancedFields) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val advanced = rememberAdvancedReminderFieldsState(initialReminder)
    var siblings by remember { mutableStateOf<List<Reminder>?>(null) }
    // The parent the person picked, by id so it survives a rotation (§7 R83 (a));
    // null until they pick, and the default below stands in.
    var chosenParentId by rememberSaveable { mutableStateOf<Long?>(null) }
    var delayMinutes by rememberSaveable { mutableStateOf(initial.delayMinutes.toString()) }
    var dose by rememberSaveable { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    var startBehavior by rememberSaveable { mutableStateOf(initial.startBehavior) }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(medicineId) {
        reminderRepository.observeForMedicine(medicineId).collect { list ->
            // Alert reminders are excluded, not just the reminder being
            // edited: SchedulingEngine.supportedTypes leaves both out, so
            // neither ever produces a DoseEvent, so a following dose anchored
            // to one has no anchor to read and never fires. They were offered
            // here and, being oldest-first, could even be preselected as the
            // default parent. See docs/SPEC.md §7 R23.
            val candidates = list.filter {
                it.id != excludeReminderId &&
                    it.type != ReminderType.LOW_STOCK_ALERT &&
                    it.type != ReminderType.EXPIRATION_ALERT
            }
            siblings = candidates
        }
    }

    val delayValue = delayMinutes.toIntOrNull()
    val delayOk = delayValue != null && delayValue in 1..1440
    val doseValue = parseDecimal(dose)
    val currentSiblings = siblings
    val selectedParent = currentSiblings?.let { list ->
        list.firstOrNull { it.id == chosenParentId } ?: list.firstOrNull { it.id == initialParentReminderId } ?: list.firstOrNull()
    }

    val dirty = rememberDirty(Unit, chosenParentId, delayMinutes, dose, startBehavior, advanced.snapshot())
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = selectedParent != null && delayOk && doseValue != null && doseValue > 0 && advanced.errors().isEmpty(),
        onSave = {
            val parent = selectedParent
            val delay = delayValue
            val doseAmount = doseValue
            if (parent != null && delay != null && doseAmount != null) {
                scope.launch { onSave(parent.id, ReminderConfig.LinkedFollowing(delay, doseAmount, startBehavior), advanced.snapshot()) }
            }
        },
        onCancel = onCancel,
    ) {
        when {
            currentSiblings == null -> Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
            currentSiblings.isEmpty() -> Text(
                stringResource(R.string.add_reminder_this_medicine_needs_another_reminder_first_a),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
                    FormTextField(
                        selectedParent?.let { reminderLabel(it) } ?: "", {}, stringResource(R.string.add_reminder_follows),
                        Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                        readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    )
                    ExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        currentSiblings.forEach { sibling ->
                            DropdownMenuItem(
                                text = { Text(reminderLabel(sibling)) },
                                onClick = {
                                    chosenParentId = sibling.id
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FormTextField(
                    delayMinutes, { delayMinutes = it }, stringResource(R.string.add_reminder_minutes_after), keyboardType = KeyboardType.Number,
                    error = if (delayOk) null else stringResource(R.string.add_reminder_minutes_after_range), reserve = true,
                )
                Spacer(Modifier.height(8.dp))
                DoseField(dose, doseUnit) { dose = it }
                Spacer(Modifier.height(8.dp))
                StartBehaviorPicker(startBehavior) { startBehavior = it }
                AdvancedReminderFieldsSection(advanced)
            }
        }
    }
}

@Composable
private fun reminderLabel(reminder: Reminder): String {
    val name = stringResource(reminder.type.labelRes())
    return when (val config = decodeReminderConfig(reminder.configJson)) {
        is ReminderConfig.FixedTime ->
            stringResource(R.string.reminder_label_at, name, "${"%02d".format(config.hour)}:${"%02d".format(config.minute)}")
        is ReminderConfig.RepeatingInterval -> stringResource(R.string.reminder_label_every_hours, name, formatHours(config.intervalMinutes))
        is ReminderConfig.DailyWindow -> stringResource(R.string.reminder_label_every_hours, name, formatHours(config.intervalMinutes))
        is ReminderConfig.LinkedFollowing -> stringResource(R.string.reminder_label_plus_minutes, name, config.delayMinutes)
        is ReminderConfig.LowStockAlert, is ReminderConfig.ExpirationAlert -> name
    }
}

private fun formatHours(intervalMinutes: Int): String = formatDecimalLocalized(intervalMinutes / 60.0)
