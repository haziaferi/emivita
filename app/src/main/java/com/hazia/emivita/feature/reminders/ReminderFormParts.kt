package com.hazia.emivita.feature.reminders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.ui.components.unsavedChangesGuard
import com.hazia.emivita.ui.components.rememberDirty
import com.hazia.emivita.ui.form.FormScaffold
import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.ui.form.FormTimeField

/** Every reminder form: title and fields scroll, Salva and Annulla stay in the foot (§7 R124). */
@Composable
internal fun ReminderFormScaffold(
    title: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    saveEnabled: Boolean,
    saveLabel: String,
    onDelete: (() -> Unit)? = null,
    dirty: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Back and Cancel both ask once the form has been touched (§9 #29, HM-40).
    val cancel = unsavedChangesGuard(dirty, onCancel)
    // Delete lives at the end of the edit form (§9 #24, the owner's choice).
    FormScaffold(
        title = title, saveLabel = saveLabel, saveEnabled = saveEnabled, onSave = onSave, onCancel = cancel,
        deleteLabel = stringResource(R.string.reminder_delete), onDelete = onDelete, content = content,
    )
}

/** One selectable node per option (§9 #19 Stage B): the row carries the role and the state. */
@Composable
internal fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
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
    val stickyUntilAnswered: Boolean,
    val variableAmount: Boolean,
)

/** The Avanzate fields that can be refused, each with the error it shows. */
internal enum class AdvancedField { NAG_DELAY, NAG_MAX, PRE_ALARM, DOSES, UNTIL }

/** State holder for [AdvancedReminderFieldsSection] -- a plain class with
 *  Compose-backed `var`s (not a data class + copy()) since there are six
 *  independently-editable sub-fields; seeded from [initialReminder] when
 *  editing, all defaults when creating. */
internal class AdvancedReminderFieldsState(initialReminder: Reminder?) {
    var autoResolve by mutableStateOf(initialReminder?.autoResolve ?: false)
    var activeFromEpochDay by mutableStateOf(initialReminder?.activeFromEpochDay)
    var activeUntilEpochDay by mutableStateOf(initialReminder?.activeUntilEpochDay)
    // On for a new reminder, as in medTimer (the owner, §7 R104); an existing one keeps its own.
    var nagEnabled by mutableStateOf(initialReminder?.let { it.repeatOnUnanswered != null } ?: true)
    var nagDelayMinutes by mutableStateOf(initialReminder?.repeatOnUnanswered?.delayMinutes?.toString() ?: "30")
    var nagMaxRepeats by mutableStateOf(initialReminder?.repeatOnUnanswered?.maxRepeats?.toString() ?: "3")
    var weekendModeOptIn by mutableStateOf(initialReminder?.weekendModeOptIn ?: false)
    var preAlarmEnabled by mutableStateOf(initialReminder?.preAlarmMinutes != null)
    var preAlarmMinutes by mutableStateOf(initialReminder?.preAlarmMinutes?.toString() ?: "30")
    var instructions by mutableStateOf(initialReminder?.instructions ?: "")
    var courseEnabled by mutableStateOf(initialReminder?.dosesRemaining != null)
    var dosesRemaining by mutableStateOf(initialReminder?.dosesRemaining?.toString() ?: "10")
    var stickyUntilAnswered by mutableStateOf(initialReminder?.stickyUntilAnswered ?: false)
    var variableAmount by mutableStateOf(initialReminder?.variableAmount ?: false)

    private fun values(): List<Any?> = listOf(autoResolve, activeFromEpochDay, activeUntilEpochDay, nagEnabled, nagDelayMinutes, nagMaxRepeats,
        weekendModeOptIn, preAlarmEnabled, preAlarmMinutes, instructions, courseEnabled, dosesRemaining, stickyUntilAnswered, variableAmount)

    private fun restore(v: List<Any?>) {
        autoResolve = v[0] as Boolean; activeFromEpochDay = v[1] as Long?; activeUntilEpochDay = v[2] as Long?
        nagEnabled = v[3] as Boolean; nagDelayMinutes = v[4] as String; nagMaxRepeats = v[5] as String
        weekendModeOptIn = v[6] as Boolean; preAlarmEnabled = v[7] as Boolean; preAlarmMinutes = v[8] as String
        instructions = v[9] as String; courseEnabled = v[10] as Boolean; dosesRemaining = v[11] as String
        stickyUntilAnswered = v[12] as Boolean; variableAmount = v[13] as Boolean
    }

    /** A group sheet's draft: a copy it edits, applied back only by the sheet's Salva. */
    fun draft(): AdvancedReminderFieldsState = AdvancedReminderFieldsState(null).also { it.restore(values()) }

    fun applyFrom(draft: AdvancedReminderFieldsState) = restore(draft.values())

    fun sameAs(other: AdvancedReminderFieldsState) = values() == other.values()

    /** What would be stored wrong or silently changed, field by field (§7 R124: "every 0 min"). */
    fun errors(): Map<AdvancedField, Int> {
        val e = linkedMapOf<AdvancedField, Int>()
        fun below(text: String, min: Int) = (text.trim().toIntOrNull() ?: Int.MIN_VALUE) < min
        if (nagEnabled && below(nagDelayMinutes, 1)) e[AdvancedField.NAG_DELAY] = R.string.form_min_one_minute
        if (nagEnabled && below(nagMaxRepeats, 1)) e[AdvancedField.NAG_MAX] = R.string.form_min_one
        if (preAlarmEnabled && below(preAlarmMinutes, 1)) e[AdvancedField.PRE_ALARM] = R.string.form_min_one_minute
        if (courseEnabled && below(dosesRemaining, 1)) e[AdvancedField.DOSES] = R.string.form_min_one
        val from = activeFromEpochDay
        val until = activeUntilEpochDay
        if (from != null && until != null && until < from) e[AdvancedField.UNTIL] = R.string.form_until_before_from
        return e
    }

    companion object {
        /** Field by field, so the section survives a rotation (§7 R83 (a)). */
        val Saver: Saver<AdvancedReminderFieldsState, Any> = listSaver(
            save = { it.values() },
            restore = { v -> AdvancedReminderFieldsState(null).apply { restore(v) } },
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
        stickyUntilAnswered = stickyUntilAnswered,
        variableAmount = variableAmount,
    )
}

/**
 * Avanzate (§7 R124): four grouped rows, always showing, in the owner's order by dependency
 * (Quando prendo, Notifica, Se non rispondo, Durata). Each row says its current values in one line
 * and opens its group's sheet. Alert reminders have no such section: they never produce a dose.
 */
@Composable
internal fun AdvancedReminderFieldsSection(state: AdvancedReminderFieldsState) {
    var open by rememberSaveable { mutableStateOf<ReminderGroup?>(null) }
    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.add_reminder_advanced), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        ReminderGroup.entries.forEach { g -> ReminderGroupRow(g, state) { open = g } }
    }
    open?.let { g -> ReminderGroupSheet(g, state) { open = null } }
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
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var timeBad by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val thresholdValue = parseDecimal(threshold)
    val thresholdOk = thresholdValue != null && thresholdValue >= 0

    val dirty = rememberDirty(Unit, threshold, triggerMode, hour, minute)
    ReminderFormScaffold(
        dirty = dirty,
        title = title,
        onDelete = onDelete,
        saveLabel = saveLabel,
        saveEnabled = thresholdOk && !timeBad,
        onSave = {
            scope.launch {
                onSave(ReminderConfig.LowStockAlert(thresholdValue ?: 0.0, triggerMode, hour, minute))
            }
        },
        onCancel = onCancel,
    ) {
        FormTextField(
            threshold, { threshold = it }, stringResource(R.string.add_reminder_alert_when_stock_is_at_or_below),
            keyboardType = KeyboardType.Decimal, error = if (thresholdOk) null else stringResource(R.string.form_min_zero), reserve = true,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.common_notify), style = MaterialTheme.typography.labelLarge)
        Column {
            RadioRow(stringResource(R.string.add_reminder_once_until_restocked), triggerMode == LowStockTriggerMode.ONCE_WHEN_BELOW) {
                triggerMode = LowStockTriggerMode.ONCE_WHEN_BELOW
            }
            RadioRow(stringResource(R.string.add_reminder_once_a_day_while_low), triggerMode == LowStockTriggerMode.DAILY_WHEN_BELOW) {
                triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW
            }
            RadioRow(stringResource(R.string.add_reminder_every_relevant_change_while_low), triggerMode == LowStockTriggerMode.ALWAYS_WHEN_BELOW) {
                triggerMode = LowStockTriggerMode.ALWAYS_WHEN_BELOW
            }
        }
        // Alert reminders carry a time of day since spec §7 R37: something has to say when the
        // alarm rings. Last in the form on purpose: the threshold is what the user came to set.
        Spacer(Modifier.height(8.dp))
        FormTimeField(stringResource(R.string.add_reminder_alert_at), hour, minute, onError = { timeBad = it }) { h, m -> hour = h; minute = m }
    }
}
