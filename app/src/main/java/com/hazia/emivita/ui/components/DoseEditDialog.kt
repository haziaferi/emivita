package com.hazia.emivita.ui.components

import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.ui.form.DialogActionPair
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
 * Event editing (spec §3/§9 #3), shared between Today and History and opened
 * by a tap rather than an easily-triggered swipe: only the fields that changed
 * are written, each through its own repository method, so only a status
 * switch touches stock, via [DoseEventRepository.switchStatus].
 *
 * For a PENDING ("Missed") event (spec §7 R46) neither status radio starts
 * selected and the recorded time starts at [scheduledEpochMillis] rather than
 * now, so resolving it is a deliberate choice dated when the dose happened.
 * A locked medicine is not gated here (§7 R32), because that lock stops a live
 * dose being dismissed and correcting a past record is not a dismissal.
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
                // The one field pattern (§7 R125), as in the forms.
                FormTextField(doseAmount, { doseAmount = it }, stringResource(R.string.common_dose_amount), suffix = doseUnit)
                Spacer(Modifier.height(12.dp))
                FormTextField(notes, { notes = it }, stringResource(R.string.dose_edit_notes), freeText = true)

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
                TextButton(enabled = !saving, onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.dose_edit_delete), color = MaterialTheme.colorScheme.error)
                }
                if (confirmDelete) {
                    // Its own dialog, titled with the action (§7 R129, the owner: "2: separate
                    // dialog"): inline, its Annulla / Conferma sat over the edit's Annulla / Salva.
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text(stringResource(R.string.dose_edit_delete_title)) },
                        text = { Text(stringResource(R.string.dose_edit_delete_confirm_text)) },
                        confirmButton = {
                            DialogActionPair(
                                stringResource(R.string.common_confirm), destructive = true, confirmEnabled = !saving,
                                onDismiss = { confirmDelete = false },
                                onConfirm = {
                                    saving = true
                                    scope.launch {
                                        try {
                                            doseEventRepository.softDelete(doseEventId)
                                            ReminderNotifications.dismiss(context, doseEventId)
                                            onDismiss()
                                        } catch (e: Exception) {
                                            saving = false
                                            confirmDelete = false
                                            saveError = resources.getString(R.string.error_could_not_save, e.message ?: resources.getString(R.string.error_unknown))
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
                saveError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            DialogActionPair(
                stringResource(R.string.common_save),
                onDismiss = onDismiss,
                confirmEnabled = doseValue != null && doseValue > 0 && !saving && status != null,
                onConfirm = onConfirm@{
                    val amount = doseValue ?: return@onConfirm
                    val chosenStatus = status ?: return@onConfirm
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
            )
        },
    )
}
