package com.hazia.emivita.feature.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.entity.RepeatConfig
import com.hazia.emivita.ui.components.DateField
import com.hazia.emivita.ui.components.TimeOfDayField
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.format.formatDecimalLocalized
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.toggleable
import com.hazia.emivita.ui.components.unsavedChangesGuard
import com.hazia.emivita.ui.components.rememberDirty
import com.hazia.emivita.ui.components.CollapsibleSection

@Composable
internal fun ReminderFormScaffold(
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

/** Snapshot of the "Advanced" section's fields at save time, passed to each
 *  dose-producing form body's widened `onSave` -- see [ReminderRepository.create]/
 *  [ReminderRepository.updateAdvancedFields], the two places these four
 *  cross-cutting fields (spec §3/§4) actually get persisted. */
internal data class AdvancedFields(
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
internal class AdvancedReminderFieldsState(initialReminder: Reminder?) {
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

    companion object {
        /** Field by field, so the section survives a rotation (§7 R83 (a)). */
        val Saver: Saver<AdvancedReminderFieldsState, Any> = listSaver(
            save = { listOf(it.autoResolve, it.activeFromEpochDay, it.activeUntilEpochDay, it.nagEnabled, it.nagDelayMinutes, it.nagMaxRepeats,
                it.weekendModeOptIn, it.preAlarmEnabled, it.preAlarmMinutes, it.instructions, it.courseEnabled, it.dosesRemaining) },
            restore = { v ->
                AdvancedReminderFieldsState(null).apply {
                    autoResolve = v[0] as Boolean; activeFromEpochDay = v[1] as Long?; activeUntilEpochDay = v[2] as Long?
                    nagEnabled = v[3] as Boolean; nagDelayMinutes = v[4] as String; nagMaxRepeats = v[5] as String
                    weekendModeOptIn = v[6] as Boolean; preAlarmEnabled = v[7] as Boolean; preAlarmMinutes = v[8] as String
                    instructions = v[9] as String; courseEnabled = v[10] as Boolean; dosesRemaining = v[11] as String
                }
            },
        )
    }

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

/** Collapsed by default -- these are the "advanced" fields per their own
 *  schema doc comments (Reminder.kt), not something most reminders need.
 *  Shared across the four dose-producing form bodies (Fixed Time, Repeating
 *  Interval, Daily Window, Linked Following) -- LowStockAlert/ExpirationAlert
 *  don't include this section at all, since they never produce DoseEvents
 *  and none of these four fields mean anything without one. */
@Composable
internal fun AdvancedReminderFieldsSection(state: AdvancedReminderFieldsState) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Spacer(Modifier.height(16.dp))
    CollapsibleSection(stringResource(R.string.add_reminder_advanced), expanded, onToggle = { expanded = !expanded }) {
        AdvancedReminderFields(state)
    }
}

@Composable
private fun AdvancedReminderFields(state: AdvancedReminderFieldsState) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = state.autoResolve, role = Role.Switch) { state.autoResolve = it },
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
        Switch(checked = state.autoResolve, onCheckedChange = null)
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
        Modifier.fillMaxWidth().toggleable(value = state.nagEnabled, role = Role.Switch) { state.nagEnabled = it },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.add_reminder_repeat_until_answered), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = state.nagEnabled, onCheckedChange = null)
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
        Modifier.fillMaxWidth().toggleable(value = state.weekendModeOptIn, role = Role.Switch) { state.weekendModeOptIn = it },
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
        Switch(checked = state.weekendModeOptIn, onCheckedChange = null)
    }
}

/** No dose field -- unlike the other three types, a stock alert doesn't
 *  materialize a DoseEvent (spec §4's doc comment on doseAmountOrNull()), so
 *  there's no "how much" to ask for here, only "how low" and "how often". */
@Composable
internal fun LowStockAlertFormBody(
    title: String,
    saveLabel: String,
    initial: ReminderConfig.LowStockAlert,
    onSave: suspend (ReminderConfig.LowStockAlert) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var threshold by rememberSaveable { mutableStateOf(formatDecimalLocalized(initial.threshold)) }
    var triggerMode by rememberSaveable { mutableStateOf(initial.triggerMode) }
    var hour by rememberSaveable { mutableStateOf(initial.hour) }
    var minute by rememberSaveable { mutableStateOf(initial.minute) }
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
