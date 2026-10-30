package com.hazia.emivita.feature.reminders

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.staticCompositionLocalOf
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.notification.ReminderNotificationChannels
import com.hazia.emivita.ui.form.FormRadioRow
import com.hazia.emivita.feature.medicines.channelDescriptionFor
import com.hazia.emivita.ui.form.GridActionCell
import com.hazia.emivita.ui.form.SpanGrid
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.ui.form.FieldPair
import com.hazia.emivita.ui.form.FormDateField
import com.hazia.emivita.ui.form.FormGroupRow
import com.hazia.emivita.ui.form.FormSheetScaffold
import com.hazia.emivita.ui.form.FormSwitchRow
import com.hazia.emivita.ui.form.FormTextField
import java.time.LocalDate

/** The Avanzate groups in the owner's order, by dependency (§7 R124). */
internal enum class ReminderGroup(@StringRes val title: Int) {
    TAKING(R.string.reminder_group_taking),
    NOTIFICATION(R.string.reminder_group_notification),
    UNANSWERED(R.string.reminder_group_unanswered),
    DURATION(R.string.reminder_group_duration),
}

/** What a group's row says: its current values, joined, in one line. */
@Composable
internal fun groupSummary(g: ReminderGroup, s: AdvancedReminderFieldsState): String {
    val parts = mutableListOf<String>()
    when (g) {
        ReminderGroup.TAKING -> {
            if (s.variableAmount) parts += stringResource(R.string.reminder_sum_ask_amount)
            if (s.instructions.isNotBlank()) parts += s.instructions.trim()
            if (parts.isEmpty()) parts += stringResource(R.string.reminder_sum_no_instructions)
        }
        ReminderGroup.NOTIFICATION -> {
            // Its own tier when it has one (§7 R127, B-38), else its medicine's.
            parts += s.importance?.let { stringResource(ReminderNotificationChannels.tierNameFor(it)) } ?: stringResource(R.string.reminder_sum_as_medicine)
            if (s.weekendModeOptIn) parts += stringResource(R.string.reminder_sum_weekend)
            if (s.autoResolve) parts += stringResource(R.string.add_reminder_auto_resolve)
        }
        ReminderGroup.UNANSWERED -> {
            if (s.nagEnabled) parts += stringResource(R.string.reminder_sum_repeat, s.nagDelayMinutes.toIntOrNull() ?: 0, s.nagMaxRepeats.toIntOrNull() ?: 0)
            if (s.stickyUntilAnswered) parts += stringResource(R.string.reminder_sum_sticky)
            if (s.preAlarmEnabled) parts += stringResource(R.string.reminder_sum_prealarm, s.preAlarmMinutes.toIntOrNull() ?: 0)
            if (parts.isEmpty()) parts += stringResource(R.string.reminder_sum_nothing)
        }
        ReminderGroup.DURATION -> {
            val from = s.activeFromEpochDay?.let { LocaleFormats.monthDayYear(LocalDate.ofEpochDay(it)) }
            val until = s.activeUntilEpochDay?.let { LocaleFormats.monthDayYear(LocalDate.ofEpochDay(it)) }
            if (from != null) parts += stringResource(R.string.reminder_sum_from, from)
            if (until != null) parts += stringResource(R.string.reminder_sum_until, until)
            if (s.courseEnabled) parts += stringResource(R.string.reminder_sum_doses, s.dosesRemaining.toIntOrNull() ?: 0)
            if (parts.isEmpty()) parts += stringResource(R.string.reminder_sum_no_limit)
        }
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun ReminderGroupRow(g: ReminderGroup, state: AdvancedReminderFieldsState, onClick: () -> Unit) =
    FormGroupRow(stringResource(g.title), groupSummary(g, state), onClick = onClick)

/**
 * A group's settings in a sheet (§7 R124): the sheet edits a draft, its Salva applies the draft
 * and Annulla or Back drops it (asking first when edited). Salva stays off while a value would be
 * stored wrong, such as "every 0 min".
 */
@Composable
internal fun ReminderGroupSheet(group: ReminderGroup, state: AdvancedReminderFieldsState, close: () -> Unit) {
    val draft = remember { state.draft() }
    var badDates by remember { mutableStateOf(false) }
    val errors = draft.errors()
    FormSheetScaffold(
        title = stringResource(group.title),
        dirty = !draft.sameAs(state),
        saveEnabled = errors.isEmpty() && !badDates,
        onSave = { state.applyFrom(draft); close() },
        close = close,
    ) {
        when (group) {
            ReminderGroup.TAKING -> TakingGroup(draft)
            ReminderGroup.NOTIFICATION -> NotificationGroup(draft)
            ReminderGroup.UNANSWERED -> UnansweredGroup(draft, errors)
            ReminderGroup.DURATION -> DurationGroup(draft, errors) { badDates = it }
        }
    }
}

@Composable
private fun NumberField(value: String, @StringRes label: Int, modifier: Modifier, enabled: Boolean, @StringRes error: Int?, onChange: (String) -> Unit) =
    // A rule shows only when broken, in a line the field keeps reserved, so nothing moves (§7 R124).
    FormTextField(value, onChange, stringResource(label), modifier, error = error?.let { stringResource(it) },
        enabled = enabled, keyboardType = KeyboardType.Number, reserve = true)

@Composable
private fun TakingGroup(s: AdvancedReminderFieldsState) {
    FormSwitchRow(stringResource(R.string.add_reminder_variable_amount), stringResource(R.string.add_reminder_variable_amount_desc), s.variableAmount) { s.variableAmount = it }
    Spacer(Modifier.height(8.dp))
    // Instructions (§9 #26; medTimer): the one line that goes on the notification, whatever the switch says.
    FormTextField(s.instructions, { s.instructions = it }, stringResource(R.string.add_reminder_instructions), help = stringResource(R.string.add_reminder_instructions_hint))
    Spacer(Modifier.height(8.dp))
    // The samples on the grid (§7 R127, D-10 V1): a tap adds one to what is there, never twice.
    val samples = instructionSamples.map { stringResource(it) }
    SpanGrid { samples.forEach { t -> GridActionCell(t) { s.instructions = appendSample(s.instructions, t) } } }
}

/** The instruction samples (§7 R127, D-10; medTimer's), in the rig's approved words. */
internal val instructionSamples = listOf(
    R.string.instructions_sample_with_food,
    R.string.instructions_sample_empty_stomach,
    R.string.instructions_sample_before_bed,
    R.string.instructions_sample_with_water,
)

/** What joins a picked sample to the text before it: the reminder row's own separator. */
private const val SAMPLE_SEPARATOR = " · "

/** [sample] added after what [current] holds, joined with " · "; a sample already there is not added again. */
internal fun appendSample(current: String, sample: String): String {
    val parts = current.split(SAMPLE_SEPARATOR.trim()).map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        parts.any { it.equals(sample, ignoreCase = true) } -> current
        current.isBlank() -> sample
        else -> current.trimEnd() + SAMPLE_SEPARATOR + sample
    }
}

@Composable
private fun NotificationGroup(s: AdvancedReminderFieldsState) {
    // Weekend mode delays a dose; which days and how late are set once in Settings (§7 R36).
    FormSwitchRow(stringResource(R.string.add_reminder_follow_weekend_mode), stringResource(R.string.add_reminder_push_this_reminder_to_the_end_of_the_weekend), s.weekendModeOptIn) {
        s.weekendModeOptIn = it
    }
    Spacer(Modifier.height(8.dp))
    FormSwitchRow(stringResource(R.string.add_reminder_auto_resolve), stringResource(R.string.add_reminder_silently_mark_taken_no_notification_for), s.autoResolve) { s.autoResolve = it }
    // The reminder's own tier (§7 R127, B-38 V1): only tiers above its medicine's, none for an Alarm medicine.
    val medicine = LocalMedicinePriority.current
    val higher = medicine?.let { m -> NotificationPriority.entries.filter { it > m } }.orEmpty()
    if (medicine != null && higher.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.add_reminder_importance), style = MaterialTheme.typography.labelLarge)
        FormRadioRow(
            stringResource(R.string.add_reminder_importance_same, stringResource(ReminderNotificationChannels.tierNameFor(medicine))),
            s.importance.let { it == null || it <= medicine },
            stringResource(channelDescriptionFor(medicine)),
        ) { s.importance = null }
        for (tier in higher) {
            FormRadioRow(stringResource(ReminderNotificationChannels.tierNameFor(tier)), s.importance == tier, stringResource(channelDescriptionFor(tier))) { s.importance = tier }
        }
    }
}

/** The tier of the medicine whose reminder the form edits; null until it has loaded (§7 R127, B-38). */
internal val LocalMedicinePriority = staticCompositionLocalOf<NotificationPriority?> { null }

/** The dependent fields stay in place, off while their switch is off, so a toggle moves nothing. */
@Composable
private fun UnansweredGroup(s: AdvancedReminderFieldsState, errors: Map<AdvancedField, Int>) {
    FormSwitchRow(stringResource(R.string.add_reminder_repeat_until_answered), null, s.nagEnabled) { s.nagEnabled = it }
    FieldPair(
        listOf(stringResource(R.string.add_reminder_every_min), stringResource(R.string.add_reminder_max_repeats), stringResource(R.string.form_min_one_minute)),
        first = { m -> NumberField(s.nagDelayMinutes, R.string.add_reminder_every_min, m, s.nagEnabled, errors[AdvancedField.NAG_DELAY]) { s.nagDelayMinutes = it } },
        second = { m -> NumberField(s.nagMaxRepeats, R.string.add_reminder_max_repeats, m, s.nagEnabled, errors[AdvancedField.NAG_MAX]) { s.nagMaxRepeats = it } },
    )
    Spacer(Modifier.height(8.dp))
    // The notification's own behaviour (§7 R109, Appendix E B-42 and B-45).
    FormSwitchRow(stringResource(R.string.add_reminder_sticky), stringResource(R.string.add_reminder_sticky_desc), s.stickyUntilAnswered) { s.stickyUntilAnswered = it }
    Spacer(Modifier.height(8.dp))
    // Pre-alarm (§9 #25, HM-19): one toggleable node, then the lead field.
    FormSwitchRow(stringResource(R.string.add_reminder_pre_alarm), stringResource(R.string.add_reminder_pre_alarm_desc), s.preAlarmEnabled) { s.preAlarmEnabled = it }
    NumberField(s.preAlarmMinutes, R.string.add_reminder_pre_alarm_minutes, Modifier.fillMaxWidth(), s.preAlarmEnabled, errors[AdvancedField.PRE_ALARM]) { s.preAlarmMinutes = it }
}

@Composable
private fun DurationGroup(s: AdvancedReminderFieldsState, errors: Map<AdvancedField, Int>, onBad: (Boolean) -> Unit) {
    var fromBad by remember { mutableStateOf(false) }
    var untilBad by remember { mutableStateOf(false) }
    LaunchedEffect(fromBad, untilBad) { onBad(fromBad || untilBad) }
    val help = stringResource(R.string.reminder_range_help)
    val from = stringResource(R.string.add_reminder_from)
    val until = stringResource(R.string.add_reminder_until)
    // A taper is just several reminders with adjacent ranges; empty is no bound.
    FieldPair(
        listOf(from, until, help, "00/00/0000"), iconDp = 48.dp,
        first = { m -> FormDateField(from, s.activeFromEpochDay, m, help = help, onError = { fromBad = it }) { s.activeFromEpochDay = it } },
        second = { m ->
            FormDateField(until, s.activeUntilEpochDay, m, help = help, error = errors[AdvancedField.UNTIL]?.let { stringResource(it) }, onError = { untilBad = it }) {
                s.activeUntilEpochDay = it
            }
        },
    )
    Spacer(Modifier.height(8.dp))
    // Dose-count course (§9 #26; MediTrak MT-07): "N doses, then stop".
    FormSwitchRow(stringResource(R.string.add_reminder_course), stringResource(R.string.add_reminder_course_desc), s.courseEnabled) { s.courseEnabled = it }
    NumberField(s.dosesRemaining, R.string.add_reminder_doses_remaining, Modifier.fillMaxWidth(), s.courseEnabled, errors[AdvancedField.DOSES]) { s.dosesRemaining = it }
}
