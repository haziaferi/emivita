package com.hazia.emivita.feature.medicines

import com.hazia.emivita.ui.form.FormRadioRow
import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.notification.ReminderNotificationChannels
import com.hazia.emivita.ui.form.FormSheetScaffold
import com.hazia.emivita.ui.form.FormTextField

/** The medicine form's Avanzate groups (§7 R125): what most medicines never change. */
internal enum class MedicineGroup(@StringRes val title: Int) {
    NOTIFICATION(R.string.reminder_group_notification),
    DETAILS(R.string.medicine_group_details),
}

/** The Notifica row's line: the channel tier and the lock. */
@Composable
internal fun notificationSummary(priority: NotificationPriority, lock: Boolean?): String =
    stringResource(ReminderNotificationChannels.tierNameFor(priority)) + " · " + stringResource(
        when (lock) {
            null -> R.string.medicine_sum_lock_default
            true -> R.string.medicine_sum_lock_on
            false -> R.string.medicine_sum_lock_off
        },
    )

/** The Dettagli row's line: what is filled in, joined, or "Niente aggiunto". */
@Composable
internal fun detailsSummary(indication: String, storage: String, rxNumber: String, notes: String, barcode: String?): String {
    val parts = listOfNotNull(
        indication.takeIf { it.isNotBlank() }?.trim(),
        storage.takeIf { it.isNotBlank() }?.trim(),
        rxNumber.takeIf { it.isNotBlank() }?.let { stringResource(R.string.medicine_details_rx, it.trim()) },
        notes.takeIf { it.isNotBlank() }?.trim()?.lines()?.first(),
        barcode?.let { stringResource(R.string.medicine_details_code, it) },
    )
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.medicine_details_none) }
}

/** The channel description for a tier, so the sheet says exactly what the
 *  Notifications & Alerts screen and Android's own settings say. */
@StringRes
internal fun channelDescriptionFor(priority: NotificationPriority): Int = when (priority) {
    NotificationPriority.STANDARD -> R.string.channel_standard_desc
    NotificationPriority.HIGH -> R.string.channel_high_desc
    NotificationPriority.ALARM -> R.string.channel_alarm_desc
}

/**
 * Notifica (§7 R125): the medicine's channel tier (§7 R59, the only place it is set) and the lock
 * until taken. The sheet edits a draft; its Salva hands it to [apply], Annulla or Back drops it.
 */
@Composable
internal fun MedicineNotificationSheet(priority: NotificationPriority, lock: Boolean?, close: () -> Unit, apply: (NotificationPriority, Boolean?) -> Unit) {
    var p by rememberSaveable { mutableStateOf(priority) }
    var l by rememberSaveable { mutableStateOf(lock) }
    FormSheetScaffold(
        stringResource(R.string.reminder_group_notification), dirty = p != priority || l != lock, saveEnabled = true,
        onSave = { apply(p, l); close() }, close = close,
    ) {
        Text(stringResource(R.string.add_medicine_notification_priority), style = MaterialTheme.typography.labelLarge)
        for (option in NotificationPriority.entries) {
            FormRadioRow(stringResource(ReminderNotificationChannels.tierNameFor(option)), p == option, stringResource(channelDescriptionFor(option))) { p = option }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.add_medicine_lock_reminders_until_taken), style = MaterialTheme.typography.labelLarge)
        // One selectable node per option (§9 #19 Stage B).
        for ((value, label) in listOf(null to R.string.add_medicine_use_global_default, true to R.string.add_medicine_always_lock, false to R.string.add_medicine_never_lock)) {
            FormRadioRow(stringResource(label), l == value) { l = value }
        }
    }
}

/** The Dettagli sheet's fields, as one draft. */
internal data class MedicineDetails(val barcode: String, val indication: String, val storage: String, val rxNumber: String, val notes: String)

/**
 * Dettagli (§7 R125): the barcode with a scan icon, what it is for, storage, the prescription
 * number (§9 #27; DT-10) and notes (§7 R60 C F-4). A draft its own Salva applies.
 */
@Composable
internal fun MedicineDetailsSheet(initial: MedicineDetails, close: () -> Unit, apply: (MedicineDetails) -> Unit) {
    var code by rememberSaveable { mutableStateOf(initial.barcode) }
    var indication by rememberSaveable { mutableStateOf(initial.indication) }
    var storage by rememberSaveable { mutableStateOf(initial.storage) }
    var rx by rememberSaveable { mutableStateOf(initial.rxNumber) }
    var notes by rememberSaveable { mutableStateOf(initial.notes) }
    // zxing "Scan a barcode" intent: an installed scanner app (e.g. Binary Eye) reads it; the app
    // bundles no camera code and looks nothing up (spec §3).
    var scanUnavailable by rememberSaveable { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getStringExtra("SCAN_RESULT")?.let { code = it }
    }
    val draft = MedicineDetails(code, indication, storage, rx, notes)
    FormSheetScaffold(
        stringResource(R.string.medicine_group_details), dirty = draft != initial, saveEnabled = true,
        onSave = { apply(draft); close() }, close = close,
    ) {
        FormTextField(
            code, { code = it }, stringResource(R.string.medicine_barcode),
            help = if (scanUnavailable) stringResource(R.string.medicine_scan_unavailable) else null,
            trailingIcon = {
                IconButton(onClick = {
                    try {
                        scanLauncher.launch(Intent("com.google.zxing.client.android.SCAN")); scanUnavailable = false
                    } catch (_: ActivityNotFoundException) {
                        scanUnavailable = true
                    }
                }) { Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.add_medicine_scan_barcode)) }
            },
        )
        Spacer(Modifier.height(8.dp))
        FormTextField(indication, { indication = it }, stringResource(R.string.add_medicine_what_it_s_for_optional))
        Spacer(Modifier.height(8.dp))
        FormTextField(storage, { storage = it }, stringResource(R.string.add_medicine_storage_note_optional))
        Spacer(Modifier.height(8.dp))
        FormTextField(rx, { rx = it }, stringResource(R.string.add_medicine_rx_number_optional))
        Spacer(Modifier.height(8.dp))
        FormTextField(notes, { notes = it }, stringResource(R.string.add_medicine_notes_optional), freeText = true)
    }
}
