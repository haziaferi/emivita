package com.hazia.emivita.feature.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.hazia.emivita.core.format.LocaleFormats
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.ui.components.DateField
import com.hazia.emivita.ui.components.TimeOfDayField
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import androidx.annotation.StringRes
import com.hazia.emivita.core.format.labelRes
import androidx.compose.ui.text.intl.Locale
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.format.formatDecimalLocalized
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.selection.toggleable
import com.hazia.emivita.ui.components.unsavedChangesGuard
import com.hazia.emivita.ui.components.rememberDirty

// @StringRes: reminderTypeOptions is a top-level val (§9 #19).
private data class ReminderTypeOption(
    val type: ReminderType,
    val icon: ImageVector,
    @StringRes val description: Int,
) {
    val title: Int @StringRes get() = type.labelRes()
}

// LOW_STOCK_ALERT/EXPIRATION_ALERT deliberately excluded (spec §6/§9 #1:
// "Stock & expiration: one screen... one entry point, no duplicated fields
// elsewhere") -- StockExpirationScreen.kt is their sole creation path now.
// LowStockAlertFormBody/ExpirationAlertFormBody below stay wired into
// EditReminderScreen's `when`, so a reminder either screen creates can still
// be edited from wherever it's tapped -- one creation entry point, not two,
// but editing reuses the same field-level form either way (not duplication).
private val reminderTypeOptions = listOf(
    ReminderTypeOption(ReminderType.FIXED_TIME, Icons.Filled.Schedule, R.string.reminder_type_fixed_time_desc),
    ReminderTypeOption(ReminderType.REPEATING_INTERVAL, Icons.Filled.Repeat, R.string.reminder_type_repeating_interval_desc),
    ReminderTypeOption(ReminderType.DAILY_WINDOW, Icons.Filled.CalendarViewWeek, R.string.reminder_type_daily_window_desc),
    ReminderTypeOption(ReminderType.LINKED_FOLLOWING, Icons.Filled.Link, R.string.reminder_type_linked_following_desc),
)

/**
 * Type picker + the 6 sub-forms (spec §6/§9 items 1, 8, and 14).
 * LowStockAlert/ExpirationAlert get a real AlarmManager occurrence like every
 * other type as of spec §7 R37 -- which is why both their forms carry a time
 * of day. Only low stock's ONCE/ALWAYS modes are still announced by
 * LowStockExpirationAlertEvaluator on the projection cache's recompute pass,
 * because those two mean "the moment it crosses" and an alarm cannot say that.
 * CyclicActivePause
 * is a 4th RepeatPattern case inside Fixed Time's own form, not a top-level
 * type with a card of its own (R8, §7).
 */
@Composable
fun AddReminderScreen(
    medicineId: Long,
    reminderRepository: ReminderRepository,
    medicineRepository: MedicineRepository,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var selectedType by remember { mutableStateOf<ReminderType?>(null) }
    // One-time fetch, not a live collector -- the Measurement field is set on
    // the medicine's own screen, not editable from here; this form only
    // needs its current value as a display hint next to the dose field.
    val doseUnit by produceState<String?>(initialValue = null, medicineId) {
        value = medicineRepository.getById(medicineId)?.doseUnit
    }

    when (selectedType) {
        null -> ReminderTypePicker(onSelect = { selectedType = it }, onCancel = onCancel)
        ReminderType.FIXED_TIME -> FixedTimeFormBody(
            title = stringResource(R.string.reminder_fixed_time),
            saveLabel = stringResource(R.string.common_save),
            initial = ReminderConfig.FixedTime(hour = 8, minute = 0, dose = 1.0, repeatPattern = RepeatPattern.Daily),
            doseUnit = doseUnit,
            onSave = { config, advanced ->
                reminderRepository.create(
                    medicineId, null, ReminderType.FIXED_TIME, config,
                    autoResolve = advanced.autoResolve, activeFromEpochDay = advanced.activeFromEpochDay,
                    activeUntilEpochDay = advanced.activeUntilEpochDay, repeatOnUnanswered = advanced.repeatOnUnanswered,
                    weekendModeOptIn = advanced.weekendModeOptIn, preAlarmMinutes = advanced.preAlarmMinutes,
                    instructions = advanced.instructions, dosesRemaining = advanced.dosesRemaining,
                )
                onDone()
            },
            onCancel = { selectedType = null },
        )
        ReminderType.REPEATING_INTERVAL -> RepeatingIntervalFormBody(
            title = stringResource(R.string.reminder_repeating_interval),
            saveLabel = stringResource(R.string.common_save),
            initial = ReminderConfig.RepeatingInterval(
                intervalMinutes = 8 * 60,
                dose = 1.0,
                startBehavior = IntervalStartBehavior.ON_REMINDED,
                anchorEpochMillis = System.currentTimeMillis(),
            ),
            doseUnit = doseUnit,
            onSave = { config, advanced ->
                reminderRepository.create(
                    medicineId, null, ReminderType.REPEATING_INTERVAL, config,
                    autoResolve = advanced.autoResolve, activeFromEpochDay = advanced.activeFromEpochDay,
                    activeUntilEpochDay = advanced.activeUntilEpochDay, repeatOnUnanswered = advanced.repeatOnUnanswered,
                    weekendModeOptIn = advanced.weekendModeOptIn, preAlarmMinutes = advanced.preAlarmMinutes,
                    instructions = advanced.instructions, dosesRemaining = advanced.dosesRemaining,
                )
                onDone()
            },
            onCancel = { selectedType = null },
        )
        ReminderType.DAILY_WINDOW -> DailyWindowFormBody(
            title = stringResource(R.string.reminder_daily_window),
            saveLabel = stringResource(R.string.common_save),
            initial = ReminderConfig.DailyWindow(
                intervalMinutes = 4 * 60,
                dose = 1.0,
                windowStartHour = 8,
                windowStartMinute = 0,
                windowEndHour = 20,
                windowEndMinute = 0,
                startBehavior = IntervalStartBehavior.ON_REMINDED,
            ),
            doseUnit = doseUnit,
            onSave = { config, advanced ->
                reminderRepository.create(
                    medicineId, null, ReminderType.DAILY_WINDOW, config,
                    autoResolve = advanced.autoResolve, activeFromEpochDay = advanced.activeFromEpochDay,
                    activeUntilEpochDay = advanced.activeUntilEpochDay, repeatOnUnanswered = advanced.repeatOnUnanswered,
                    weekendModeOptIn = advanced.weekendModeOptIn, preAlarmMinutes = advanced.preAlarmMinutes,
                    instructions = advanced.instructions, dosesRemaining = advanced.dosesRemaining,
                )
                onDone()
            },
            onCancel = { selectedType = null },
        )
        // Unreachable: [reminderTypeOptions] excludes both, and the picker is
        // the only thing that ever sets selectedType. StockExpirationScreen is
        // their sole creation path (spec §6/§9 #1: "one entry point, no
        // duplicated fields elsewhere"). Kept as an explicit case rather than
        // an `else` so adding a 7th ReminderType still forces this file to be
        // revisited. EditReminderScreen below routes to their form bodies.
        ReminderType.LOW_STOCK_ALERT, ReminderType.EXPIRATION_ALERT -> Unit
        ReminderType.LINKED_FOLLOWING -> LinkedFollowingFormBody(
            title = stringResource(R.string.reminder_linked_following),
            saveLabel = stringResource(R.string.common_save),
            medicineId = medicineId,
            excludeReminderId = null,
            reminderRepository = reminderRepository,
            initial = ReminderConfig.LinkedFollowing(delayMinutes = 30, dose = 1.0, startBehavior = IntervalStartBehavior.ON_RESOLVED),
            initialParentReminderId = null,
            doseUnit = doseUnit,
            onSave = { parentId, config, advanced ->
                reminderRepository.create(
                    medicineId, null, ReminderType.LINKED_FOLLOWING, config, parentReminderId = parentId,
                    autoResolve = advanced.autoResolve, activeFromEpochDay = advanced.activeFromEpochDay,
                    activeUntilEpochDay = advanced.activeUntilEpochDay, repeatOnUnanswered = advanced.repeatOnUnanswered,
                    weekendModeOptIn = advanced.weekendModeOptIn, preAlarmMinutes = advanced.preAlarmMinutes,
                    instructions = advanced.instructions, dosesRemaining = advanced.dosesRemaining,
                )
                onDone()
            },
            onCancel = { selectedType = null },
        )
    }
}

/**
 * Edit Reminder (spec §9 item 1's "immediate follow-up"). Loads the existing
 * [Reminder], decodes its config, and routes straight into the matching
 * type-specific form body pre-filled -- no type picker, since changing a
 * reminder's type is really "delete this one, add a different one" (its
 * schema, its scheduling behavior, and its notification history all key off
 * `type`), not a field edit.
 */
@Composable
fun EditReminderScreen(
    reminderId: Long,
    reminderRepository: ReminderRepository,
    medicineRepository: MedicineRepository,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    var reminder by remember { mutableStateOf<Reminder?>(null) }
    var doseUnit by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reminderId) {
        val loadedReminder = reminderRepository.getById(reminderId)
        reminder = loadedReminder
        doseUnit = loadedReminder?.let { medicineRepository.getById(it.medicineId)?.doseUnit }
        loaded = true
    }

    val current = reminder
    if (!loaded || current == null) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(if (loaded) R.string.reminder_not_found else R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val onDelete: () -> Unit = { showDeleteConfirm = true }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.reminder_delete_title)) },
            text = { Text(stringResource(R.string.reminder_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        reminderRepository.delete(current)
                        onDone()
                    }
                }) { Text(stringResource(R.string.reminder_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    when (val config = reminderRepository.decodeConfig(current)) {
        is ReminderConfig.FixedTime -> FixedTimeFormBody(
            title = stringResource(R.string.reminder_edit_fixed_time),
            saveLabel = stringResource(R.string.common_save_changes),
            initial = config,
            initialReminder = current,
            doseUnit = doseUnit,
            onSave = { updated, advanced ->
                reminderRepository.updateConfigAndAdvancedFields(
                    current, updated, advanced.autoResolve, advanced.activeFromEpochDay,
                    advanced.activeUntilEpochDay, advanced.repeatOnUnanswered, advanced.weekendModeOptIn,
                    preAlarmMinutes = advanced.preAlarmMinutes, instructions = advanced.instructions, dosesRemaining = advanced.dosesRemaining,
                )
                onDone()
            },
            onCancel = onCancel,
            onDelete = onDelete,
        )
        is ReminderConfig.RepeatingInterval -> RepeatingIntervalFormBody(
            title = stringResource(R.string.reminder_edit_repeating_interval),
            saveLabel = stringResource(R.string.common_save_changes),
            initial = config,
            initialReminder = current,
            doseUnit = doseUnit,
            onSave = { updated, advanced ->
                reminderRepository.updateConfigAndAdvancedFields(
                    current, updated, advanced.autoResolve, advanced.activeFromEpochDay,
                    advanced.activeUntilEpochDay, advanced.repeatOnUnanswered, advanced.weekendModeOptIn,
                    preAlarmMinutes = advanced.preAlarmMinutes, instructions = advanced.instructions, dosesRemaining = advanced.dosesRemaining,
                )
                onDone()
            },
            onCancel = onCancel,
            onDelete = onDelete,
        )
        is ReminderConfig.DailyWindow -> DailyWindowFormBody(
            title = stringResource(R.string.reminder_edit_daily_window),
            saveLabel = stringResource(R.string.common_save_changes),
            initial = config,
            initialReminder = current,
            doseUnit = doseUnit,
            onSave = { updated, advanced ->
                reminderRepository.updateConfigAndAdvancedFields(
                    current, updated, advanced.autoResolve, advanced.activeFromEpochDay,
                    advanced.activeUntilEpochDay, advanced.repeatOnUnanswered, advanced.weekendModeOptIn,
                    preAlarmMinutes = advanced.preAlarmMinutes, instructions = advanced.instructions, dosesRemaining = advanced.dosesRemaining,
                )
                onDone()
            },
            onCancel = onCancel,
            onDelete = onDelete,
        )
        is ReminderConfig.LowStockAlert -> LowStockAlertFormBody(
            title = stringResource(R.string.reminder_edit_low_stock),
            saveLabel = stringResource(R.string.common_save_changes),
            initial = config,
            onSave = { updated -> reminderRepository.updateConfig(current, updated); onDone() },
            onCancel = onCancel,
            onDelete = onDelete,
        )
        is ReminderConfig.ExpirationAlert -> ExpirationAlertFormBody(
            title = stringResource(R.string.reminder_edit_expiration),
            saveLabel = stringResource(R.string.common_save_changes),
            initial = config,
            onSave = { updated -> reminderRepository.updateConfig(current, updated); onDone() },
            onCancel = onCancel,
            onDelete = onDelete,
        )
        is ReminderConfig.LinkedFollowing -> LinkedFollowingFormBody(
            title = stringResource(R.string.reminder_edit_linked_following),
            saveLabel = stringResource(R.string.common_save_changes),
            medicineId = current.medicineId,
            excludeReminderId = current.id,
            reminderRepository = reminderRepository,
            initial = config,
            initialParentReminderId = current.parentReminderId,
            initialReminder = current,
            doseUnit = doseUnit,
            onSave = { parentId, updated, advanced ->
                reminderRepository.updateConfigAndAdvancedFields(
                    current, updated, advanced.autoResolve, advanced.activeFromEpochDay,
                    advanced.activeUntilEpochDay, advanced.repeatOnUnanswered, advanced.weekendModeOptIn,
                    preAlarmMinutes = advanced.preAlarmMinutes, instructions = advanced.instructions, dosesRemaining = advanced.dosesRemaining,
                    parentReminderId = parentId,
                )
                onDone()
            },
            onCancel = onCancel,
            onDelete = onDelete,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTypePicker(onSelect: (ReminderType) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(title = { Text(stringResource(R.string.common_add_reminder)) })
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(reminderTypeOptions) { option ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable(role = Role.Button) { onSelect(option.type) },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(option.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(option.title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(option.description),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderFormScaffold(
    title: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    saveEnabled: Boolean,
    saveLabel: String,
    onDelete: (() -> Unit)? = null,
    dirty: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Back and Cancel both ask once the form has been touched (§9 #29, HM-40).
    val cancel = unsavedChangesGuard(dirty, onCancel)
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        content()
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, enabled = saveEnabled) { Text(saveLabel) }
            Button(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
        }
        // Edit only (§9 #24, owner's choice: delete lives at the bottom of the
        // edit form, pause on the detail row). Until R62 the only way to be
        // rid of a reminder was deleting its medicine -- Appendix E C-29/D F1.
        if (onDelete != null) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.reminder_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

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

/** Snapshot of the "Advanced" section's fields at save time, passed to each
 *  dose-producing form body's widened `onSave` -- see [ReminderRepository.create]/
 *  [ReminderRepository.updateAdvancedFields], the two places these four
 *  cross-cutting fields (spec §3/§4) actually get persisted. */
private data class AdvancedFields(
    val autoResolve: Boolean,
    val activeFromEpochDay: Long?,
    val activeUntilEpochDay: Long?,
    val repeatOnUnanswered: RepeatConfig?,
    val weekendModeOptIn: Boolean,
    val preAlarmMinutes: Int?,
    val instructions: String?,
    val dosesRemaining: Int?,
)

/** State holder for [AdvancedReminderFieldsSection] -- a plain class with
 *  Compose-backed `var`s (not a data class + copy()) since there are six
 *  independently-editable sub-fields; seeded from [initialReminder] when
 *  editing, all defaults when creating. */
private class AdvancedReminderFieldsState(initialReminder: Reminder?) {
    var autoResolve by mutableStateOf(initialReminder?.autoResolve ?: false)
    var activeFromEpochDay by mutableStateOf(initialReminder?.activeFromEpochDay)
    var activeUntilEpochDay by mutableStateOf(initialReminder?.activeUntilEpochDay)
    var nagEnabled by mutableStateOf(initialReminder?.repeatOnUnanswered != null)
    var nagDelayMinutes by mutableStateOf(initialReminder?.repeatOnUnanswered?.delayMinutes?.toString() ?: "30")
    var nagMaxRepeats by mutableStateOf(initialReminder?.repeatOnUnanswered?.maxRepeats?.toString() ?: "3")
    var weekendModeOptIn by mutableStateOf(initialReminder?.weekendModeOptIn ?: false)
    var preAlarmEnabled by mutableStateOf(initialReminder?.preAlarmMinutes != null)
    var preAlarmMinutes by mutableStateOf(initialReminder?.preAlarmMinutes?.toString() ?: "30")
    var instructions by mutableStateOf(initialReminder?.instructions ?: "")
    var courseEnabled by mutableStateOf(initialReminder?.dosesRemaining != null)
    var dosesRemaining by mutableStateOf(initialReminder?.dosesRemaining?.toString() ?: "10")

    fun snapshot(): AdvancedFields = AdvancedFields(
        autoResolve = autoResolve,
        activeFromEpochDay = activeFromEpochDay,
        activeUntilEpochDay = activeUntilEpochDay,
        repeatOnUnanswered = if (nagEnabled) {
            RepeatConfig(nagDelayMinutes.toIntOrNull() ?: 30, nagMaxRepeats.toIntOrNull() ?: 3)
        } else {
            null
        },
        weekendModeOptIn = weekendModeOptIn,
        preAlarmMinutes = if (preAlarmEnabled) preAlarmMinutes.toIntOrNull()?.takeIf { it > 0 } else null,
        instructions = instructions.trim().ifBlank { null },
        dosesRemaining = if (courseEnabled) dosesRemaining.toIntOrNull()?.takeIf { it >= 0 } else null,
    )
}

@Composable
private fun rememberAdvancedReminderFieldsState(initialReminder: Reminder?): AdvancedReminderFieldsState =
    remember(initialReminder) { AdvancedReminderFieldsState(initialReminder) }

/** Collapsed by default -- these are the "advanced" fields per their own
 *  schema doc comments (Reminder.kt), not something most reminders need.
 *  Shared across the four dose-producing form bodies (Fixed Time, Repeating
 *  Interval, Daily Window, Linked Following) -- LowStockAlert/ExpirationAlert
 *  don't include this section at all, since they never produce DoseEvents
 *  and none of these four fields mean anything without one. */
@Composable
private fun AdvancedReminderFieldsSection(state: AdvancedReminderFieldsState) {
    var expanded by remember { mutableStateOf(false) }

    Spacer(Modifier.height(16.dp))
    val expandedLabel = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = expandedLabel },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.add_reminder_advanced), style = MaterialTheme.typography.labelLarge)
        // An icon with no description, not a triangle glyph: the row above
        // already says expanded or collapsed, and TalkBack read the glyph as
        // "black up-pointing triangle" (§9 #19 Stage B).
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }

    if (!expanded) return

    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.add_reminder_auto_resolve), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.add_reminder_silently_mark_taken_no_notification_for),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.autoResolve, onCheckedChange = { state.autoResolve = it })
    }

    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.add_reminder_active_date_range), style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(R.string.add_reminder_leave_unset_for_no_bound_a_taper_is_just),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField(stringResource(R.string.add_reminder_from), state.activeFromEpochDay) { state.activeFromEpochDay = it }
        DateField(stringResource(R.string.add_reminder_until), state.activeUntilEpochDay) { state.activeUntilEpochDay = it }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.add_reminder_repeat_until_answered), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = state.nagEnabled, onCheckedChange = { state.nagEnabled = it })
    }
    if (state.nagEnabled) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.nagDelayMinutes,
                onValueChange = { state.nagDelayMinutes = it },
                label = { Text(stringResource(R.string.add_reminder_every_min)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.nagMaxRepeats,
                onValueChange = { state.nagMaxRepeats = it },
                label = { Text(stringResource(R.string.add_reminder_max_repeats)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }

    // Pre-alarm (§9 #25, HM-19): one toggleable node, then the lead field.
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().toggleable(value = state.preAlarmEnabled, role = Role.Switch) { state.preAlarmEnabled = it },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.add_reminder_pre_alarm), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.add_reminder_pre_alarm_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.preAlarmEnabled, onCheckedChange = null)
    }
    if (state.preAlarmEnabled) {
        OutlinedTextField(
            value = state.preAlarmMinutes,
            onValueChange = { state.preAlarmMinutes = it },
            label = { Text(stringResource(R.string.add_reminder_pre_alarm_minutes)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Instructions (§9 #26; medTimer): the one line that goes on the notification.
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.instructions,
        onValueChange = { state.instructions = it },
        label = { Text(stringResource(R.string.add_reminder_instructions)) },
        placeholder = { Text(stringResource(R.string.add_reminder_instructions_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    // Dose-count course (§9 #26; MediTrak MT-07): "N doses, then stop".
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().toggleable(value = state.courseEnabled, role = Role.Switch) { state.courseEnabled = it },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.add_reminder_course), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.add_reminder_course_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.courseEnabled, onCheckedChange = null)
    }
    if (state.courseEnabled) {
        OutlinedTextField(
            value = state.dosesRemaining,
            onValueChange = { state.dosesRemaining = it },
            label = { Text(stringResource(R.string.add_reminder_doses_remaining)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.add_reminder_follow_weekend_mode), style = MaterialTheme.typography.bodyMedium)
            // Says what it costs, not just what it does: this switch delays a
            // dose, and which days and how late are set once in Settings >
            // Weekend mode rather than per reminder. Off by default -- see
            // Reminder.weekendModeOptIn and docs/SPEC.md §7 R36.
            Text(
                stringResource(R.string.add_reminder_push_this_reminder_to_the_end_of_the_weekend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.weekendModeOptIn, onCheckedChange = { state.weekendModeOptIn = it })
    }
}

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
private fun FixedTimeFormBody(
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
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var dose by remember { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    val initialPattern = initial.repeatPattern
    var patternKind by remember {
        mutableIntStateOf(
            when (initialPattern) {
                is RepeatPattern.SpecificDaysOfWeek -> 1
                is RepeatPattern.EveryNDays -> 2
                is RepeatPattern.CyclicActivePause -> 3
                RepeatPattern.Daily -> 0
            }
        )
    }
    var selectedDays by remember { mutableStateOf((initialPattern as? RepeatPattern.SpecificDaysOfWeek)?.days ?: setOf()) }
    var everyN by remember { mutableStateOf((initialPattern as? RepeatPattern.EveryNDays)?.n?.toString() ?: "2") }
    val everyNStartEpochDay = (initialPattern as? RepeatPattern.EveryNDays)?.startEpochDay ?: LocalDate.now().toEpochDay()
    var activeDays by remember { mutableStateOf((initialPattern as? RepeatPattern.CyclicActivePause)?.activeDays?.toString() ?: "21") }
    var pauseDays by remember { mutableStateOf((initialPattern as? RepeatPattern.CyclicActivePause)?.pauseDays?.toString() ?: "7") }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = dayNum in selectedDays,
                            onCheckedChange = { checked ->
                                selectedDays = if (checked) selectedDays + dayNum else selectedDays - dayNum
                            },
                        )
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
private fun RepeatingIntervalFormBody(
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
    var intervalHours by remember { mutableStateOf(formatHours(initial.intervalMinutes)) }
    var dose by remember { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    var startBehavior by remember { mutableStateOf(initial.startBehavior) }
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    val hoursValue = parseDecimal(intervalHours)
    // Guard the value actually stored, not the raw hours: 0.01 passes
    // `hoursValue > 0` but truncates to 0 minutes, which the calculator then
    // rejects -- after the row would already have been committed. Same shape
    // as the Every-N-days guard above. See docs/SPEC.md §7 R23.
    //
    // Still the right place for this even though the calculator no longer
    // throws (R33 made it return null, matching the rest of the file): a zero
    // that reaches the database yields a reminder that silently never fires,
    // which is worse than a save button that stays disabled. The two guards
    // are belt and braces, not a duplicate -- the calculator's exists because
    // BackupRepository.restoreSnapshot bypasses this form entirely.
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
private fun DailyWindowFormBody(
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
    var intervalHours by remember { mutableStateOf(formatHours(initial.intervalMinutes)) }
    var dose by remember { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    var startHour by remember { mutableIntStateOf(initial.windowStartHour) }
    var startMinute by remember { mutableIntStateOf(initial.windowStartMinute) }
    var endHour by remember { mutableIntStateOf(initial.windowEndHour) }
    var endMinute by remember { mutableIntStateOf(initial.windowEndMinute) }
    var startBehavior by remember { mutableStateOf(initial.startBehavior) }
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(dose)
    val hoursValue = parseDecimal(intervalHours)
    // Guard the value actually stored, not the raw hours: 0.01 passes
    // `hoursValue > 0` but truncates to 0 minutes, which the calculator then
    // rejects -- after the row would already have been committed. Same shape
    // as the Every-N-days guard above. See docs/SPEC.md §7 R23.
    //
    // Still the right place for this even though the calculator no longer
    // throws (R33 made it return null, matching the rest of the file): a zero
    // that reaches the database yields a reminder that silently never fires,
    // which is worse than a save button that stays disabled. The two guards
    // are belt and braces, not a duplicate -- the calculator's exists because
    // BackupRepository.restoreSnapshot bypasses this form entirely.
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

/** The one line that makes an overnight window legible (§9 #31, A-20): an end
 *  before the start is accepted and read as "the next day", so the form says
 *  so rather than leaving the person to wonder whether 22:00-06:00 will fire.
 *  Absent for a same-day window. */
@Composable
internal fun WindowEndsNextDayNote(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
    val overnight = endHour * 60 + endMinute < startHour * 60 + startMinute
    if (!overnight) return
    val endMillis = java.time.LocalDate.now().atTime(endHour, endMinute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.reminder_window_ends_next_day, LocaleFormats.time(endMillis)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** No dose field -- unlike the other three types, a stock alert doesn't
 *  materialize a DoseEvent (spec §4's doc comment on doseAmountOrNull()), so
 *  there's no "how much" to ask for here, only "how low" and "how often". */
@Composable
private fun LowStockAlertFormBody(
    title: String,
    saveLabel: String,
    initial: ReminderConfig.LowStockAlert,
    onSave: suspend (ReminderConfig.LowStockAlert) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var threshold by remember { mutableStateOf(formatDecimalLocalized(initial.threshold)) }
    var triggerMode by remember { mutableStateOf(initial.triggerMode) }
    var hour by remember { mutableStateOf(initial.hour) }
    var minute by remember { mutableStateOf(initial.minute) }
    val scope = rememberCoroutineScope()
    val thresholdValue = parseDecimal(threshold)

    val dirty = rememberDirty(Unit, threshold, triggerMode, hour, minute)
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = thresholdValue != null && thresholdValue >= 0,
        onSave = {
            scope.launch {
                onSave(ReminderConfig.LowStockAlert(thresholdValue ?: 0.0, triggerMode, hour, minute))
            }
        },
        onCancel = onCancel,
    ) {
        OutlinedTextField(
            value = threshold,
            onValueChange = { threshold = it },
            label = { Text(stringResource(R.string.add_reminder_alert_when_stock_is_at_or_below)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.common_notify), style = MaterialTheme.typography.labelLarge)
        Column {
            Row(
                // One selectable node per option (§9 #19 Stage B): the row carries
                // the role and the state, the RadioButton is display-only, and the
                // label is read as the option's name rather than as a second control.
                Modifier.selectable(selected = triggerMode == LowStockTriggerMode.ONCE_WHEN_BELOW, role = Role.RadioButton) { triggerMode = LowStockTriggerMode.ONCE_WHEN_BELOW },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = triggerMode == LowStockTriggerMode.ONCE_WHEN_BELOW, onClick = null)
                Text(stringResource(R.string.add_reminder_once_until_restocked))
            }
            Row(
                // One selectable node per option (§9 #19 Stage B): the row carries
                // the role and the state, the RadioButton is display-only, and the
                // label is read as the option's name rather than as a second control.
                Modifier.selectable(selected = triggerMode == LowStockTriggerMode.DAILY_WHEN_BELOW, role = Role.RadioButton) { triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = triggerMode == LowStockTriggerMode.DAILY_WHEN_BELOW, onClick = null)
                Text(stringResource(R.string.add_reminder_once_a_day_while_low))
            }
            Row(
                // One selectable node per option (§9 #19 Stage B): the row carries
                // the role and the state, the RadioButton is display-only, and the
                // label is read as the option's name rather than as a second control.
                Modifier.selectable(selected = triggerMode == LowStockTriggerMode.ALWAYS_WHEN_BELOW, role = Role.RadioButton) { triggerMode = LowStockTriggerMode.ALWAYS_WHEN_BELOW },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = triggerMode == LowStockTriggerMode.ALWAYS_WHEN_BELOW, onClick = null)
                Text(stringResource(R.string.add_reminder_every_relevant_change_while_low))
            }
        }

        // Alert reminders carry a time of day since spec §7 R37 -- they are
        // real scheduled reminders now, and something has to say when the
        // alarm rings. Last in the form on purpose: the threshold or lead
        // time is what the user came here to set; the hour is a detail with
        // a sensible default.
        TimeOfDayField(stringResource(R.string.add_reminder_alert_at), hour, minute) { h, m -> hour = h; minute = m }
    }
}

/** Same "no dose field" reasoning as [LowStockAlertFormBody] -- an expiration
 *  alert isn't a dose either. */
@Composable
private fun ExpirationAlertFormBody(
    title: String,
    saveLabel: String,
    initial: ReminderConfig.ExpirationAlert,
    onSave: suspend (ReminderConfig.ExpirationAlert) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var leadDays by remember { mutableStateOf(initial.leadDays.toString()) }
    var frequency by remember { mutableStateOf(initial.frequency) }
    var hour by remember { mutableStateOf(initial.hour) }
    var minute by remember { mutableStateOf(initial.minute) }
    val scope = rememberCoroutineScope()
    val leadDaysValue = leadDays.toIntOrNull()

    val dirty = rememberDirty(Unit, leadDays, frequency, hour, minute)
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = leadDaysValue != null && leadDaysValue >= 0,
        onSave = {
            scope.launch {
                onSave(ReminderConfig.ExpirationAlert(leadDaysValue ?: 0, frequency, hour, minute))
            }
        },
        onCancel = onCancel,
    ) {
        OutlinedTextField(
            value = leadDays,
            onValueChange = { leadDays = it },
            label = { Text(stringResource(R.string.add_reminder_days_before_expiration_to_start_alerting)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.common_notify), style = MaterialTheme.typography.labelLarge)
        Column {
            Row(
                // One selectable node per option (§9 #19 Stage B): the row carries
                // the role and the state, the RadioButton is display-only, and the
                // label is read as the option's name rather than as a second control.
                Modifier.selectable(selected = frequency == ExpirationTriggerFrequency.ONCE, role = Role.RadioButton) { frequency = ExpirationTriggerFrequency.ONCE },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = frequency == ExpirationTriggerFrequency.ONCE, onClick = null)
                Text(stringResource(R.string.common_once))
            }
            Row(
                // One selectable node per option (§9 #19 Stage B): the row carries
                // the role and the state, the RadioButton is display-only, and the
                // label is read as the option's name rather than as a second control.
                Modifier.selectable(selected = frequency == ExpirationTriggerFrequency.DAILY, role = Role.RadioButton) { frequency = ExpirationTriggerFrequency.DAILY },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = frequency == ExpirationTriggerFrequency.DAILY, onClick = null)
                Text(stringResource(R.string.add_reminder_once_a_day))
            }
        }

        // See LowStockAlertFormBody's note: alert reminders own a real
        // alarm since spec §7 R37, so they need a time to ring at.
        TimeOfDayField(stringResource(R.string.add_reminder_alert_at), hour, minute) { h, m -> hour = h; minute = m }
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
private fun LinkedFollowingFormBody(
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
    var selectedParent by remember { mutableStateOf<Reminder?>(null) }
    var delayMinutes by remember { mutableStateOf(initial.delayMinutes.toString()) }
    var dose by remember { mutableStateOf(formatDecimalLocalized(initial.dose)) }
    var startBehavior by remember { mutableStateOf(initial.startBehavior) }
    var menuExpanded by remember { mutableStateOf(false) }
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
            if (selectedParent == null) {
                selectedParent = candidates.firstOrNull { it.id == initialParentReminderId } ?: candidates.firstOrNull()
            }
        }
    }

    val delayValue = delayMinutes.toIntOrNull()
    val doseValue = parseDecimal(dose)
    val currentSiblings = siblings

    val dirty = rememberDirty(siblings, selectedParent?.id, delayMinutes, dose, startBehavior, advanced.snapshot())
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
                                    selectedParent = sibling
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
