package com.hazia.emivita.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.repository.DoseEventRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalResources
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.format.formatDecimalLocalized
import androidx.compose.ui.platform.LocalContext
import com.hazia.emivita.core.notification.ReminderNotifications
import com.hazia.emivita.core.data.entity.DoseEvent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * Event editing (spec §3/§9 #3) -- shared between Today and History, since
 * spec explicitly calls both out ("swipe-to-edit on a Today/History entry").
 * Status switch, recorded time, and dose amount are each independently
 * editable; only the fields that actually changed get written, each through
 * its own repository method, so the stock-correct-undo logic stays isolated
 * to [DoseEventRepository.switchStatus] specifically -- editing amount/time
 * never touches stock, only a status switch does.
 *
 * Tap-to-open rather than a literal swipe gesture -- functionally
 * equivalent (every field spec calls out is editable), and a modal step
 * before changing a medication record reads safer for this app's purpose
 * than a gesture that's easy to trigger by accident.
 *
 * Also opens for an unresolved (PENDING) event, which History labels
 * "Missed" -- spec §7 R46. That case used to be excluded by every caller,
 * which meant a dose you took but did not tap was wrong in the record
 * permanently and wrong in the PDF export with it. Two differences when
 * [originalStatus] is PENDING: neither status radio starts selected, so
 * resolving is a deliberate choice rather than a default the dialog picked
 * for you; and the recorded time starts at the dose's own
 * [scheduledEpochMillis] rather than now, because a dose taken on Tuesday and
 * recorded on Friday happened on Tuesday.
 *
 * A locked medicine (`Medicine.remindersLockedUntilTaken`) is deliberately
 * not gated here, on the same reasoning §7 R32 used for the resolved case:
 * that lock stops a *live* dose being dismissed without being taken, and
 * correcting a past record is not a dismissal.
 */
@Composable
fun DoseEditDialog(
    medicineName: String,
    doseEventId: Long,
    originalStatus: DoseStatus,
    originalDoseAmount: Double,
    originalResolvedAtEpochMillis: Long?,
    scheduledEpochMillis: Long,
    doseEventRepository: DoseEventRepository,
    onDismiss: () -> Unit,
    doseUnit: String? = null,
    originalNotes: String? = null,
    /** Called with the reopened event after "Return to Due" (§9 #26), so the
     *  caller -- which has the notification builder -- can re-post it. */
    onReopened: (suspend (DoseEvent) -> Unit)? = null,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val resources = LocalResources.current
    // Falls back to the dose's own scheduled slot, not to now: for a PENDING
    // ("Missed") event there is no recorded time yet, and the scheduled time is
    // the closest thing to the truth the app knows.
    val originalZoned = remember(originalResolvedAtEpochMillis, scheduledEpochMillis) {
        Instant.ofEpochMilli(originalResolvedAtEpochMillis ?: scheduledEpochMillis).atZone(zone)
    }

    val wasUnresolved = originalStatus == DoseStatus.PENDING
    // Null until chosen when the dose was never resolved -- see the doc comment
    // on why this dialog refuses to guess Taken for a missed dose.
    var status by rememberSaveable { mutableStateOf(originalStatus.takeIf { !wasUnresolved }) }
    var hour by rememberSaveable { mutableIntStateOf(originalZoned.hour) }
    var minute by rememberSaveable { mutableIntStateOf(originalZoned.minute) }
    var doseAmount by rememberSaveable { mutableStateOf(formatDecimalLocalized(originalDoseAmount)) }
    var notes by rememberSaveable { mutableStateOf(originalNotes ?: "") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Move (§9 #26; medTimer RESCHEDULE): a pending dose to a chosen day + time.
    var moveDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var moveHour by rememberSaveable { mutableIntStateOf(originalZoned.hour) }
    var moveMinute by rememberSaveable { mutableIntStateOf(originalZoned.minute) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val doseValue = parseDecimal(doseAmount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(medicineName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (wasUnresolved) {
                    Text(
                        stringResource(R.string.dose_edit_this_dose_was_never_answered_recording_it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(stringResource(R.string.dose_edit_status), style = MaterialTheme.typography.labelLarge)
                Row(
                    // One selectable node per option (§9 #19 Stage B): the row carries
                    // the role and the state, the RadioButton is display-only, and the
                    // label is read as the option's name rather than as a second control.
                    Modifier.selectable(selected = status == DoseStatus.TAKEN, role = Role.RadioButton) { status = DoseStatus.TAKEN },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = status == DoseStatus.TAKEN, onClick = null)
                    Text(stringResource(R.string.dose_edit_taken))
                }
                Row(
                    // One selectable node per option (§9 #19 Stage B): the row carries
                    // the role and the state, the RadioButton is display-only, and the
                    // label is read as the option's name rather than as a second control.
                    Modifier.selectable(selected = status == DoseStatus.SKIPPED, role = Role.RadioButton) { status = DoseStatus.SKIPPED },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = status == DoseStatus.SKIPPED, onClick = null)
                    Text(stringResource(R.string.dose_edit_skipped))
                }
                Spacer(Modifier.height(12.dp))
                TimeOfDayField(stringResource(R.string.dose_edit_recorded_time), hour, minute) { h, m -> hour = h; minute = m }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = doseAmount,
                    onValueChange = { doseAmount = it },
                    label = { Text(stringResource(R.string.common_dose_amount)) },
                    suffix = doseUnit?.let { unit -> { Text(unit) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.dose_edit_notes)) },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (wasUnresolved) {
                    // Move this dose (§9 #26): the slot itself changes and a one-off
                    // alarm is armed there; its current notification goes.
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.dose_edit_move_title), style = MaterialTheme.typography.labelLarge)
                    DateField(stringResource(R.string.dose_edit_move_day), moveDay) { moveDay = it }
                    TimeOfDayField(stringResource(R.string.dose_edit_move_time), moveHour, moveMinute) { h, m -> moveHour = h; moveMinute = m }
                    TextButton(
                        enabled = moveDay != null && !saving,
                        onClick = {
                            val day = moveDay ?: return@TextButton
                            saving = true
                            scope.launch {
                                try {
                                    val at = java.time.LocalDate.ofEpochDay(day).atTime(moveHour, moveMinute).atZone(zone).toInstant().toEpochMilli()
                                    doseEventRepository.reschedule(doseEventId, at)
                                    ReminderNotifications.dismiss(context, doseEventId)
                                    onDismiss()
                                } catch (e: Exception) {
                                    saving = false
                                    saveError = resources.getString(R.string.error_could_not_save, e.message ?: resources.getString(R.string.error_unknown))
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.dose_edit_move_action)) }
                } else {
                    // Return to Due (§9 #26; medTimer RERAISE): undo an answer.
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        enabled = !saving && onReopened != null,
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val reopened = doseEventRepository.reopen(doseEventId)
                                    if (reopened != null) onReopened?.invoke(reopened)
                                    onDismiss()
                                } catch (e: Exception) {
                                    saving = false
                                    saveError = resources.getString(R.string.error_could_not_save, e.message ?: resources.getString(R.string.error_unknown))
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.dose_edit_return_to_due)) }
                }

                // Delete (§9 #26, soft): the row hides, its slot stays claimed,
                // a Taken dose gives its stock back. Two taps, never one.
                Spacer(Modifier.height(4.dp))
                if (!confirmDelete) {
                    TextButton(enabled = !saving, onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.dose_edit_delete), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text(stringResource(R.string.dose_edit_delete_confirm_text), style = MaterialTheme.typography.labelMedium)
                    Row {
                        TextButton(
                            enabled = !saving,
                            onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        doseEventRepository.softDelete(doseEventId)
                                        ReminderNotifications.dismiss(context, doseEventId)
                                        onDismiss()
                                    } catch (e: Exception) {
                                        saving = false
                                        saveError = resources.getString(R.string.error_could_not_save, e.message ?: resources.getString(R.string.error_unknown))
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.dose_edit_delete_confirm), color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
                saveError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = doseValue != null && doseValue > 0 && !saving && status != null,
                onClick = {
                    val amount = doseValue ?: return@TextButton
                    val chosenStatus = status ?: return@TextButton
                    saving = true
                    saveError = null
                    scope.launch {
                        try {
                            val newResolvedAt = originalZoned.withHour(hour).withMinute(minute).toInstant().toEpochMilli()
                            if (wasUnresolved) {
                                // The amount FIRST (§7 R60 C F-1): resolve() computes the
                                // stock change from the event's stored doseAmount, and
                                // editDoseAmount() deliberately never touches stock. In
                                // the old order a Missed dose corrected to Taken with a
                                // different amount decremented the reminder's default,
                                // not the amount just typed. medTimer's
                                // VariableAmountHandler does it this way round too.
                                if (amount != originalDoseAmount) doseEventRepository.editDoseAmount(doseEventId, amount)
                                // resolve(), not switchStatus(): the latter requires an
                                // already-resolved event, and this one never was. It
                                // also applies the stock change, which is right --
                                // the pill left the bottle when it was taken, and the
                                // count has been one too high ever since.
                                doseEventRepository.resolve(doseEventId, chosenStatus, newResolvedAt)
                            } else {
                                if (chosenStatus != originalStatus) doseEventRepository.switchStatus(doseEventId, chosenStatus)
                                if (newResolvedAt != originalResolvedAtEpochMillis) doseEventRepository.editRecordedTime(doseEventId, newResolvedAt)
                                if (amount != originalDoseAmount) doseEventRepository.editDoseAmount(doseEventId, amount)
                            }
                            if (notes.trim() != (originalNotes ?: "")) doseEventRepository.setNotes(doseEventId, notes)
                            onDismiss()
                        } catch (e: Exception) {
                            saving = false
                            saveError = resources.getString(R.string.error_could_not_save, e.message ?: resources.getString(R.string.error_unknown))
                        }
                    }
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
