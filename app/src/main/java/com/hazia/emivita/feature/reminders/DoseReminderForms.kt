package com.hazia.emivita.feature.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.hazia.emivita.ui.components.TimeOfDayField
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import com.hazia.emivita.core.format.labelRes
import androidx.compose.ui.text.intl.Locale
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.format.formatDecimalLocalized
import androidx.compose.foundation.selection.toggleable
import com.hazia.emivita.ui.components.rememberDirty

@Composable
private fun DoseField(dose: String, doseUnit: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = dose,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.common_dose_amount)) },
        suffix = doseUnit?.let { unit -> { Text(unit) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun rememberAdvancedReminderFieldsState(initialReminder: Reminder?): AdvancedReminderFieldsState =
    rememberSaveable(initialReminder, saver = AdvancedReminderFieldsState.Saver) { AdvancedReminderFieldsState(initialReminder) }

@Composable
private fun StartBehaviorPicker(value: IntervalStartBehavior, onChange: (IntervalStartBehavior) -> Unit) {
    Column {
        Text(stringResource(R.string.add_reminder_interval_starts), style = MaterialTheme.typography.labelLarge)
        Row(
            // One selectable node per option (§9 #19 Stage B): the row carries
            // the role and the state, the RadioButton is display-only, and the
            // label is read as the option's name rather than as a second control.
            Modifier.selectable(selected = value == IntervalStartBehavior.ON_REMINDED, role = Role.RadioButton) { onChange(IntervalStartBehavior.ON_REMINDED) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = value == IntervalStartBehavior.ON_REMINDED, onClick = null)
            Text(stringResource(R.string.add_reminder_when_reminded))
        }
        Row(
            // One selectable node per option (§9 #19 Stage B): the row carries
            // the role and the state, the RadioButton is display-only, and the
            // label is read as the option's name rather than as a second control.
            Modifier.selectable(selected = value == IntervalStartBehavior.ON_RESOLVED, role = Role.RadioButton) { onChange(IntervalStartBehavior.ON_RESOLVED) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = value == IntervalStartBehavior.ON_RESOLVED, onClick = null)
            Text(stringResource(R.string.add_reminder_when_taken_or_skipped))
        }
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
    var dose by rememberSaveable { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    val initialPattern = initial.repeatPattern
    var patternKind by rememberSaveable {
        mutableIntStateOf(
            when (initialPattern) {
                is RepeatPattern.SpecificDaysOfWeek -> 1
                is RepeatPattern.EveryNDays -> 2
                is RepeatPattern.CyclicActivePause -> 3
                RepeatPattern.Daily -> 0
            }
        )
    }
    var selectedDays by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.SpecificDaysOfWeek)?.days ?: setOf()) }
    var everyN by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.EveryNDays)?.n?.toString() ?: "2") }
    val everyNStartEpochDay = (initialPattern as? RepeatPattern.EveryNDays)?.startEpochDay ?: LocalDate.now().toEpochDay()
    var activeDays by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.CyclicActivePause)?.activeDays?.toString() ?: "21") }
    var pauseDays by rememberSaveable { mutableStateOf((initialPattern as? RepeatPattern.CyclicActivePause)?.pauseDays?.toString() ?: "7") }
    // First-cycle anchor isn't editable in this pass (spec doesn't call for a
    // date-picker UI beyond the day-chip/stepper controls the other patterns
    // already get) -- defaults to today for a new reminder, preserved as-is
    // when editing an existing one, matching how EveryNDays' own startEpochDay
    // is handled above.
    val firstCycleStartEpochDay = (initialPattern as? RepeatPattern.CyclicActivePause)?.firstCycleStartEpochDay ?: LocalDate.now().toEpochDay()
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    val activeDaysValue = activeDays.toIntOrNull()
    val pauseDaysValue = pauseDays.toIntOrNull()
    // "0".toIntOrNull() is 0, not null, so the `?: 2` fallback below never
    // catches it -- an unguarded Every-N-days field let n = 0 (or negative)
    // reach a committed row, and matchingDateOnOrAfter's require(n >= 1) then
    // threw on every projection recompute. Validated here like the other two
    // patterns already are. See docs/SPEC.md §7 R23.
    val everyNValue = everyN.toIntOrNull()

    val dirty = rememberDirty(Unit, hour, minute, dose, patternKind, selectedDays, everyN, activeDays, pauseDays, advanced.snapshot())
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = doseValue != null && doseValue > 0 &&
            (patternKind != 1 || selectedDays.isNotEmpty()) &&
            (patternKind != 2 || (everyNValue != null && everyNValue >= 1)) &&
            (patternKind != 3 || (activeDaysValue != null && activeDaysValue >= 1 && pauseDaysValue != null && pauseDaysValue >= 0)),
        onSave = {
            val pattern = when (patternKind) {
                1 -> RepeatPattern.SpecificDaysOfWeek(selectedDays)
                2 -> RepeatPattern.EveryNDays(everyNValue ?: 2, everyNStartEpochDay)
                3 -> RepeatPattern.CyclicActivePause(firstCycleStartEpochDay, activeDaysValue ?: 21, pauseDaysValue ?: 7)
                else -> RepeatPattern.Daily
            }
            scope.launch {
                onSave(ReminderConfig.FixedTime(hour, minute, doseValue ?: 1.0, pattern), advanced.snapshot())
            }
        },
        onCancel = onCancel,
    ) {
        TimeOfDayField(stringResource(R.string.add_reminder_time), hour, minute) { h, m -> hour = h; minute = m }
        Spacer(Modifier.height(12.dp))
        DoseField(dose, doseUnit) { dose = it }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.add_reminder_repeats), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = patternKind == 0, onClick = { patternKind = 0 }, label = { Text(stringResource(R.string.common_daily)) })
            FilterChip(selected = patternKind == 1, onClick = { patternKind = 1 }, label = { Text(stringResource(R.string.add_reminder_specific_days)) })
            FilterChip(selected = patternKind == 2, onClick = { patternKind = 2 }, label = { Text(stringResource(R.string.add_reminder_every_n_days)) })
            FilterChip(selected = patternKind == 3, onClick = { patternKind = 3 }, label = { Text(stringResource(R.string.add_reminder_active_pause_cycle)) })
        }
        if (patternKind == 1) {
            Spacer(Modifier.height(8.dp))
            FlowRow {
                (1..7).forEach { dayNum ->
                    val label = DayOfWeek.of(dayNum).getDisplayName(TextStyle.SHORT, Locale.current.platformLocale)
                    // One toggleable node per day (Stage B pattern): TalkBack reads "lun, casella di controllo".
                    Row(
                        Modifier.toggleable(value = dayNum in selectedDays, role = Role.Checkbox) { checked ->
                            selectedDays = if (checked) selectedDays + dayNum else selectedDays - dayNum
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = dayNum in selectedDays, onCheckedChange = null)
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (patternKind == 2) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = everyN,
                onValueChange = { everyN = it },
                label = { Text(stringResource(R.string.add_reminder_every_n_days)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
        if (patternKind == 3) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = activeDays,
                onValueChange = { activeDays = it },
                label = { Text(stringResource(R.string.add_reminder_active_days)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pauseDays,
                onValueChange = { pauseDays = it },
                label = { Text(stringResource(R.string.add_reminder_pause_days)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.add_reminder_cycle_starts_today_the_anchor_date_isn_t),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AdvancedReminderFieldsSection(advanced)
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
    var intervalHours by rememberSaveable { mutableStateOf(formatHours(initial.intervalMinutes)) }
    var dose by rememberSaveable { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    var startBehavior by rememberSaveable { mutableStateOf(initial.startBehavior) }
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    val hoursValue = parseDecimal(intervalHours)
    // Guard the value actually stored, not the raw hours: 0.01 passes
    // `hoursValue > 0` but truncates to 0 minutes (docs/SPEC.md §7 R23). A zero
    // that reaches the database is a reminder that silently never fires, worse
    // than a disabled save button, so this stays even though the calculator
    // returns null (R33) for the BackupRepository.restoreSnapshot path.
    val intervalMinutesValue = hoursValue?.let { (it * 60).toInt() }

    val dirty = rememberDirty(Unit, intervalHours, dose, startBehavior, advanced.snapshot())
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = doseValue != null && doseValue > 0 &&
            intervalMinutesValue != null && intervalMinutesValue >= 1,
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
        OutlinedTextField(
            value = intervalHours,
            onValueChange = { intervalHours = it },
            label = { Text(stringResource(R.string.add_reminder_every_hours)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        DoseField(dose, doseUnit) { dose = it }
        Spacer(Modifier.height(16.dp))
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
    var startBehavior by rememberSaveable { mutableStateOf(initial.startBehavior) }
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    val hoursValue = parseDecimal(intervalHours)
    // Guard the value actually stored, not the raw hours: 0.01 passes
    // `hoursValue > 0` but truncates to 0 minutes (docs/SPEC.md §7 R23). A zero
    // that reaches the database is a reminder that silently never fires, worse
    // than a disabled save button, so this stays even though the calculator
    // returns null (R33) for the BackupRepository.restoreSnapshot path.
    val intervalMinutesValue = hoursValue?.let { (it * 60).toInt() }

    val dirty = rememberDirty(Unit, intervalHours, dose, startHour, startMinute, endHour, endMinute, startBehavior, advanced.snapshot())
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = doseValue != null && doseValue > 0 &&
            intervalMinutesValue != null && intervalMinutesValue >= 1,
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
        OutlinedTextField(
            value = intervalHours,
            onValueChange = { intervalHours = it },
            label = { Text(stringResource(R.string.add_reminder_every_hours)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        DoseField(dose, doseUnit) { dose = it }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeOfDayField(stringResource(R.string.common_window_start), startHour, startMinute) { h, m -> startHour = h; startMinute = m }
            TimeOfDayField(stringResource(R.string.common_window_end), endHour, endMinute) { h, m -> endHour = h; endMinute = m }
        }
        WindowEndsNextDayNote(startHour, startMinute, endHour, endMinute)
        Spacer(Modifier.height(16.dp))
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
        saveEnabled = selectedParent != null && delayValue != null && delayValue in 1..1440 && doseValue != null && doseValue > 0,
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
                    OutlinedTextField(
                        value = selectedParent?.let { reminderLabel(it) } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.add_reminder_follows)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
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
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = delayMinutes,
                    onValueChange = { delayMinutes = it },
                    label = { Text(stringResource(R.string.add_reminder_minutes_after_parent_1_1440)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                DoseField(dose, doseUnit) { dose = it }
                Spacer(Modifier.height(16.dp))
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
