package com.hazia.emivita.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.format.formatDecimal
import com.hazia.emivita.core.data.repository.DoseEventRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalResources

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
) {
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
    var status by remember { mutableStateOf(originalStatus.takeIf { !wasUnresolved }) }
    var hour by remember { mutableIntStateOf(originalZoned.hour) }
    var minute by remember { mutableIntStateOf(originalZoned.minute) }
    var doseAmount by remember { mutableStateOf(formatDecimal(originalDoseAmount)) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val doseValue = doseAmount.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(medicineName) },
        text = {
            Column {
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
                                // resolve(), not switchStatus(): the latter requires an
                                // already-resolved event, and this one never was. It
                                // also applies the stock change, which is right --
                                // the pill left the bottle when it was taken, and the
                                // count has been one too high ever since.
                                doseEventRepository.resolve(doseEventId, chosenStatus, newResolvedAt)
                            } else {
                                if (chosenStatus != originalStatus) doseEventRepository.switchStatus(doseEventId, chosenStatus)
                                if (newResolvedAt != originalResolvedAtEpochMillis) doseEventRepository.editRecordedTime(doseEventId, newResolvedAt)
                            }
                            if (amount != originalDoseAmount) doseEventRepository.editDoseAmount(doseEventId, amount)
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
