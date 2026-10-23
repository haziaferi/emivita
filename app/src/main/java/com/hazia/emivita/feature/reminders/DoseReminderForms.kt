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
import androidx.compose.ui.res.stringResource
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

/** The repeat kinds of a fixed-time reminder, in the order of their cells. */
private enum class RepeatKind(val label: Int) {
    DAILY(R.string.common_daily),
    WEEKDAYS(R.string.add_reminder_specific_days),
    EVERY_N_DAYS(R.string.add_reminder_every_n_days),
    CYCLE(R.string.add_reminder_active_pause_cycle),
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
    var kind by rememberSaveable {
        mutableStateOf(
            when (initialPattern) {
                is RepeatPattern.SpecificDaysOfWeek -> RepeatKind.WEEKDAYS
                is RepeatPattern.EveryNDays -> RepeatKind.EVERY_N_DAYS
                is RepeatPattern.CyclicActivePause -> RepeatKind.CYCLE
                RepeatPattern.Daily -> RepeatKind.DAILY
            }
        )
    }
    var selectedDays by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.SpecificDaysOfWeek)?.days ?: setOf()) }
    var everyN by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.EveryNDays)?.n?.toString() ?: "2") }
    val everyNStartEpochDay = (initialPattern as? RepeatPattern.EveryNDays)?.startEpochDay ?: LocalDate.now().toEpochDay()
    var activeDays by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.CyclicActivePause)?.activeDays?.toString() ?: "21") }
    var pauseDays by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.CyclicActivePause)?.pauseDays?.toString() ?: "7") }
    // The first cycle's anchor is not editable: today for a new reminder, kept as it is when
    // editing one, as EveryNDays' own startEpochDay is above.
    val firstCycleStartEpochDay = (initialPattern as? RepeatPattern.CyclicActivePause)?.firstCycleStartEpochDay ?: LocalDate.now().toEpochDay()
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

    val dirty = rememberDirty(Unit, hour, minute, dose, kind, selectedDays, everyN, activeDays, pauseDays, advanced.snapshot())
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = !timeBad && doseValue != null && doseValue > 0 &&
            (kind != RepeatKind.WEEKDAYS || selectedDays.isNotEmpty()) &&
            (kind != RepeatKind.EVERY_N_DAYS || everyNOk) &&
            (kind != RepeatKind.CYCLE || (activeOk && pauseOk)) &&
            advanced.errors().isEmpty(),
        onSave = {
            val pattern = when (kind) {
                RepeatKind.WEEKDAYS -> RepeatPattern.SpecificDaysOfWeek(selectedDays)
                RepeatKind.EVERY_N_DAYS -> RepeatPattern.EveryNDays(everyNValue ?: 2, everyNStartEpochDay)
                RepeatKind.CYCLE -> RepeatPattern.CyclicActivePause(firstCycleStartEpochDay, activeDaysValue ?: 21, pauseDaysValue ?: 7)
                RepeatKind.DAILY -> RepeatPattern.Daily
            }
            scope.launch {
                onSave(ReminderConfig.FixedTime(hour, minute, doseValue ?: 1.0, pattern), advanced.snapshot())
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
            RepeatKind.entries.forEach { k -> GridToggleCell(stringResource(k.label), kind == k) { kind = k } }
        }
        if (kind == RepeatKind.WEEKDAYS) {
            Spacer(Modifier.height(16.dp))
            WeekdayGrid(selectedDays) { selectedDays = it }
        }
        if (kind == RepeatKind.EVERY_N_DAYS) {
            Spacer(Modifier.height(16.dp))
            FormTextField(
                everyN, { everyN = it }, stringResource(R.string.add_reminder_every_n_days), keyboardType = KeyboardType.Number,
                error = if (everyNOk) null else stringResource(R.string.form_min_one), reserve = true,
            )
        }
        if (kind == RepeatKind.CYCLE) {
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
                        error = if (activeOk) null else stringResource(R.string.form_min_one), reserve = true)
                },
                second = { m ->
                    FormTextField(pauseDays, { pauseDays = it }, p, m, keyboardType = KeyboardType.Number,
                        error = if (pauseOk) null else stringResource(R.string.form_min_zero), reserve = true)
                },
            )
        }
        AdvancedReminderFieldsSection(advanced)
    }
}

@Composable
private fun IntervalHoursField(intervalHours: String, minutes: Int?, onChange: (String) -> Unit) = FormTextField(
    intervalHours, onChange, stringResource(R.string.add_reminder_every_hours), keyboardType = KeyboardType.Decimal,
    error = if ((minutes ?: 0) < 1) stringResource(R.string.form_min_one_minute) else null, reserve = true,
)

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
    var intervalHours by rememberSaveable { mutableStateOf(formatHours(initial.intervalMinutes)) }
    var dose by rememberSaveable { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    var startBehavior by rememberSaveable { mutableStateOf(initial.startBehavior) }
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    val hoursValue = parseDecimal(intervalHours)
    // Guard the value actually stored, not the raw hours: 0.01 passes `hoursValue > 0` but
    // truncates to 0 minutes, a reminder that silently never fires (docs/SPEC.md §7 R23, R33).
    val intervalMinutesValue = hoursValue?.let { (it * 60).toInt() }

    val dirty = rememberDirty(Unit, intervalHours, dose, startBehavior, advanced.snapshot())
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
        IntervalHoursField(intervalHours, intervalMinutesValue) { intervalHours = it }
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
    var intervalHours by rememberSaveable { mutableStateOf(formatHours(initial.intervalMinutes)) }
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
    val hoursValue = parseDecimal(intervalHours)
    // The stored minutes are guarded, not the raw hours, as in RepeatingIntervalFormBody (§7 R23).
    val intervalMinutesValue = hoursValue?.let { (it * 60).toInt() }

    val dirty = rememberDirty(Unit, intervalHours, dose, startHour, startMinute, endHour, endMinute, startBehavior, advanced.snapshot())
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
        IntervalHoursField(intervalHours, intervalMinutesValue) { intervalHours = it }
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
