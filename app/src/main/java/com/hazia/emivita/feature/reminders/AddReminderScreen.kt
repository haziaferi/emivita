package com.hazia.emivita.feature.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.IntervalStartBehavior
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.annotation.StringRes
import com.hazia.emivita.core.format.labelRes
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import com.hazia.emivita.ui.components.rememberDirty
import com.hazia.emivita.ui.form.FieldHelpLine
import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.ui.form.FormTimeField

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
 * LowStockAlert/ExpirationAlert get a real AlarmManager occurrence (spec §7
 * R37), so both forms carry a time of day, and only low stock's ONCE/ALWAYS
 * modes, which mean "the moment it crosses", are still announced by
 * LowStockExpirationAlertEvaluator on the projection cache's recompute pass.
 * CyclicActivePause is a 4th RepeatPattern case inside Fixed Time's own form,
 * not a top-level type with a card of its own (R8, §7).
 */
@Composable
fun AddReminderScreen(
    medicineId: Long,
    reminderRepository: ReminderRepository,
    medicineRepository: MedicineRepository,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var selectedType by rememberSaveable { mutableStateOf<ReminderType?>(null) }
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
                    stickyUntilAnswered = advanced.stickyUntilAnswered, variableAmount = advanced.variableAmount,
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
                    stickyUntilAnswered = advanced.stickyUntilAnswered, variableAmount = advanced.variableAmount,
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
                    stickyUntilAnswered = advanced.stickyUntilAnswered, variableAmount = advanced.variableAmount,
                )
                onDone()
            },
            onCancel = { selectedType = null },
        )
        // Unreachable: [reminderTypeOptions] excludes both, since
        // StockExpirationScreen is their sole creation path (spec §6/§9 #1:
        // "one entry point, no duplicated fields elsewhere"). Kept as an
        // explicit case rather than an `else` so adding a 7th ReminderType
        // still forces this file to be revisited (EditReminderScreen below
        // routes to their form bodies).
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
                    stickyUntilAnswered = advanced.stickyUntilAnswered, variableAmount = advanced.variableAmount,
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

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
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
                    stickyUntilAnswered = advanced.stickyUntilAnswered, variableAmount = advanced.variableAmount,
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
                    stickyUntilAnswered = advanced.stickyUntilAnswered, variableAmount = advanced.variableAmount,
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
                    stickyUntilAnswered = advanced.stickyUntilAnswered, variableAmount = advanced.variableAmount,
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
                    stickyUntilAnswered = advanced.stickyUntilAnswered, variableAmount = advanced.variableAmount,
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

/** The one line that makes an overnight window legible (§9 #31, A-20): an end
 *  before the start is accepted and read as "the next day", so the form says
 *  so. Absent for a same-day window; under the two fields, at their text inset (§7 R124). */
@Composable
internal fun WindowEndsNextDayNote(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
    val overnight = endHour * 60 + endMinute < startHour * 60 + startMinute
    if (!overnight) return
    val endMillis = java.time.LocalDate.now().atTime(endHour, endMinute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    FieldHelpLine(stringResource(R.string.reminder_window_ends_next_day, LocaleFormats.time(endMillis)))
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
    var leadDays by rememberSaveable { mutableStateOf(initial.leadDays.toString()) }
    var frequency by rememberSaveable { mutableStateOf(initial.frequency) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var timeBad by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val leadDaysValue = leadDays.toIntOrNull()
    val leadDaysOk = leadDaysValue != null && leadDaysValue >= 0

    val dirty = rememberDirty(Unit, leadDays, frequency, hour, minute)
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = leadDaysOk && !timeBad,
        onSave = {
            scope.launch {
                onSave(ReminderConfig.ExpirationAlert(leadDaysValue ?: 0, frequency, hour, minute))
            }
        },
        onCancel = onCancel,
    ) {
        FormTextField(
            leadDays, { leadDays = it }, stringResource(R.string.add_reminder_days_before_expiration_to_start_alerting),
            keyboardType = KeyboardType.Number, error = if (leadDaysOk) null else stringResource(R.string.form_min_zero), reserve = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.common_notify), style = MaterialTheme.typography.labelLarge)
        Column {
            RadioRow(stringResource(R.string.common_once), frequency == ExpirationTriggerFrequency.ONCE) { frequency = ExpirationTriggerFrequency.ONCE }
            RadioRow(stringResource(R.string.add_reminder_once_a_day), frequency == ExpirationTriggerFrequency.DAILY) { frequency = ExpirationTriggerFrequency.DAILY }
        }
        // See LowStockAlertFormBody's note: alert reminders own a real
        // alarm since spec §7 R37, so they need a time to ring at.
        Spacer(Modifier.height(8.dp))
        FormTimeField(stringResource(R.string.add_reminder_alert_at), hour, minute, onError = { timeBad = it }) { h, m -> hour = h; minute = m }
    }
}
