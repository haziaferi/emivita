package com.hazia.emivita.feature.medicines

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Tag
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.ui.components.ColorPickerDialog
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.components.toHexString
import com.hazia.emivita.ui.icons.MedicineIcons
import com.hazia.emivita.ui.theme.NeutralMedicineAccent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.notification.ReminderNotificationChannels
import androidx.annotation.StringRes
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalResources
import com.hazia.emivita.ui.components.unsavedChangesGuard
import com.hazia.emivita.ui.components.rememberDirty

/**
 * Add Medicine (spec §6 field order: name + barcode -> appearance (icon +
 * color) -> tags -> indication/storage note (low visual weight) -> lock-
 * override -> Save/Cancel). Thin wrapper around [MedicineFormBody] with
 * empty initial values; create-only.
 */
@Composable
fun AddMedicineScreen(
    profileId: Long,
    medicineRepository: MedicineRepository,
    prefilledBarcode: String? = null,
    onSaved: (medicineId: Long) -> Unit,
    onCancel: () -> Unit,
) {
    val colorsInUse = rememberColorsInUse(medicineRepository, profileId)
    MedicineFormBody(
        title = stringResource(R.string.add_medicine_title),
        saveLabel = stringResource(R.string.common_save),
        initialName = "",
        initialBarcodeValue = prefilledBarcode,
        initialIconKey = MedicineIcons.default.key,
        initialColor = null,
        initialTagNames = emptyList(),
        initialIndication = "",
        initialStorageNote = "",
        initialNotes = "",
        initialDoseUnit = "",
        initialLockOverride = null,
        initialPriority = NotificationPriority.STANDARD,
        colorsInUse = colorsInUse,
        onSave = { name, barcodeValue, iconKey, color, tagNames, indication, storageNote, notes, doseUnit, lockOverride, priority, prnMax, prnHours, strength, rxNumber ->
            val id = medicineRepository.create(
                Medicine(
                    profileId = profileId,
                    name = name,
                    iconKey = iconKey,
                    color = color?.toHexString(),
                    remindersLockedUntilTaken = lockOverride,
                    notificationPriority = priority,
                    sortOrder = System.currentTimeMillis().toDouble(),
                    barcodeValue = barcodeValue,
                    indication = indication,
                    storageNote = storageNote,
                    notes = notes,
                    doseUnit = doseUnit,
                    prnMaxDoses = prnMax,
                    prnWindowHours = prnHours,
                    strength = strength,
                    rxNumber = rxNumber,
                    createdAtEpochMillis = System.currentTimeMillis(),
                )
            )
            tagNames.forEach { medicineRepository.addTag(id, it) }
            onSaved(id)
        },
        onCancel = onCancel,
    )
}

/**
 * Edit Medicine (spec §9 item 1). Loads the existing [Medicine] and its
 * current tags, then reuses [MedicineFormBody] with those as the initial
 * values. Tag reconciliation on save is a plain set diff against the
 * snapshot of tags loaded when the screen opened -- fine for a single-user,
 * single-device, non-collaborative app (spec §1), no optimistic-concurrency
 * handling needed.
 *
 * Delete is not on this form: it acts on the medicine as a whole, cascading
 * to its reminders and dose history, so it lives behind a confirm dialog on
 * MedicineDetailScreen instead. See MedicineRepository.delete.
 */
@Composable
fun EditMedicineScreen(
    medicineId: Long,
    medicineRepository: MedicineRepository,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    var medicine by remember { mutableStateOf<Medicine?>(null) }
    var existingTags by remember { mutableStateOf<List<Tag>>(emptyList()) }

    LaunchedEffect(medicineId) {
        medicine = medicineRepository.getById(medicineId)
        // .first(), not a continuous collector -- this is a one-time baseline
        // snapshot the diff-on-save logic below compares against, not a live
        // observation the form should keep re-rendering against while the
        // user is mid-edit.
        existingTags = medicineRepository.observeTagsForMedicine(medicineId).first()
        loaded = true
    }

    val current = medicine
    if (!loaded || current == null) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(if (loaded) R.string.medicine_not_found else R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val tagsSnapshot = existingTags
    val colorsInUse = rememberColorsInUse(medicineRepository, current.profileId, excludeId = medicineId)
    MedicineFormBody(
        title = stringResource(R.string.edit_medicine_title),
        saveLabel = stringResource(R.string.common_save_changes),
        initialName = current.name,
        initialBarcodeValue = current.barcodeValue,
        initialIconKey = current.iconKey,
        initialColor = current.color?.let { parseHexColor(it) },
        initialTagNames = tagsSnapshot.map { it.name },
        initialIndication = current.indication ?: "",
        initialStorageNote = current.storageNote ?: "",
        initialNotes = current.notes ?: "",
        initialPrnMaxDoses = current.prnMaxDoses,
        initialPrnWindowHours = current.prnWindowHours,
        initialStrength = current.strength ?: "",
        initialRxNumber = current.rxNumber ?: "",
        initialDoseUnit = current.doseUnit ?: "",
        initialLockOverride = current.remindersLockedUntilTaken,
        initialPriority = current.notificationPriority,
        colorsInUse = colorsInUse,
        onSave = { name, barcodeValue, iconKey, color, tagNames, indication, storageNote, notes, doseUnit, lockOverride, priority, prnMax, prnHours, strength, rxNumber ->
            medicineRepository.update(
                current.copy(
                    name = name,
                    iconKey = iconKey,
                    color = color?.toHexString(),
                    remindersLockedUntilTaken = lockOverride,
                    notificationPriority = priority,
                    barcodeValue = barcodeValue,
                    indication = indication,
                    storageNote = storageNote,
                    notes = notes,
                    doseUnit = doseUnit,
                    prnMaxDoses = prnMax,
                    prnWindowHours = prnHours,
                    strength = strength,
                    rxNumber = rxNumber,
                )
            )
            val previousNames = tagsSnapshot.associateBy { it.name }
            val newNameSet = tagNames.toSet()
            previousNames.forEach { (name2, tag) ->
                if (name2 !in newNameSet) medicineRepository.removeTag(medicineId, tag.id)
            }
            tagNames.forEach { name2 ->
                if (name2 !in previousNames) medicineRepository.addTag(medicineId, name2)
            }
            onSaved()
        },
        onCancel = onCancel,
    )
}

/** Shared body for both Add and Edit -- see AddMedicineScreen/EditMedicineScreen
 *  above for what differs (title/button text, initial values, and what the
 *  final onSave callback actually does with the field values). */
/**
 * The profile's other medicines and the colours they already carry, for the
 * picker's differentiation hint (spec §3, §7 R47).
 *
 * A live collector rather than the one-shot `.first()` snapshot the tag diff
 * uses, and for the opposite reason: the tag snapshot is a baseline that must
 * not move under a diff, whereas this is only ever read to compare against, so
 * seeing a medicine added in another tab is right rather than dangerous.
 * Medicines with no colour set are skipped -- they all render the same
 * NeutralMedicineAccent, and warning that every uncoloured medicine looks like
 * every other uncoloured medicine is noise, not news.
 */
@Composable
private fun rememberColorsInUse(
    medicineRepository: MedicineRepository,
    profileId: Long,
    excludeId: Long? = null,
): List<Pair<String, Color>> {
    val medicines by medicineRepository.observeForProfile(profileId).collectAsState(initial = emptyList())
    return remember(medicines, excludeId) {
        medicines
            .map { it.medicine }
            .filter { it.id != excludeId }
            .mapNotNull { m -> parseHexColor(m.color ?: return@mapNotNull null)?.let { m.name to it } }
    }
}

@Composable
private fun MedicineFormBody(
    title: String,
    saveLabel: String,
    initialName: String,
    initialBarcodeValue: String?,
    initialIconKey: String,
    initialColor: Color?,
    initialTagNames: List<String>,
    initialIndication: String,
    initialStorageNote: String,
    initialNotes: String,
    initialPrnMaxDoses: Int? = null,
    initialPrnWindowHours: Int? = null,
    initialStrength: String = "",
    initialRxNumber: String = "",
    initialDoseUnit: String,
    initialLockOverride: Boolean?,
    initialPriority: NotificationPriority,
    /** Other medicines' accent colours, for the picker's differentiation hint
     *  (spec §3, §7 R47). Excludes the medicine being edited -- a colour is
     *  not "too close to" itself. */
    colorsInUse: List<Pair<String, Color>>,
    onSave: suspend (
        name: String,
        barcodeValue: String?,
        iconKey: String,
        color: Color?,
        tagNames: List<String>,
        indication: String?,
        storageNote: String?,
        notes: String?,
        doseUnit: String?,
        lockOverride: Boolean?,
        priority: NotificationPriority,
        prnMaxDoses: Int?,
        prnWindowHours: Int?,
        strength: String?,
        rxNumber: String?,
    ) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var priority by remember { mutableStateOf(initialPriority) }
    var barcodeValue by remember { mutableStateOf(initialBarcodeValue) }
    var iconKey by remember { mutableStateOf(initialIconKey) }
    var color by remember { mutableStateOf(initialColor) }
    var showColorPicker by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    var pendingTags by remember { mutableStateOf(initialTagNames) }
    var indication by remember { mutableStateOf(initialIndication) }
    var storageNote by remember { mutableStateOf(initialStorageNote) }
    var notes by remember { mutableStateOf(initialNotes) }
    var prnMax by remember { mutableStateOf(initialPrnMaxDoses?.toString() ?: "") }
    var prnHours by remember { mutableStateOf(initialPrnWindowHours?.toString() ?: "") }
    var strength by remember { mutableStateOf(initialStrength) }
    var rxNumber by remember { mutableStateOf(initialRxNumber) }
    val resources = LocalResources.current
    var doseUnit by remember { mutableStateOf(initialDoseUnit) }
    // null = inherit global default, true/false = explicit override
    var lockOverride by remember { mutableStateOf(initialLockOverride) }
    // §9 #29 (HM-40): every field a save would persist, against its first value.
    val dirty = rememberDirty(Unit, name, priority, barcodeValue, iconKey, color, pendingTags, indication, storageNote, notes, prnMax, prnHours, strength, rxNumber, doseUnit, lockOverride)
    val cancel = unsavedChangesGuard(dirty, onCancel)
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // zxing "Scan a barcode or QR code" intent -- delegates to an installed
    // scanner app (e.g. Binary Eye) rather than bundling camera/ML code here
    // (spec §3: local-only, no drug database or online lookup, this is purely
    // for the user's own quick re-identification later).
    var scanUnavailable by remember { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            barcodeValue = result.data?.getStringExtra("SCAN_RESULT")
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.common_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        // What a dose is measured in ("capsules", "drops", "tablet") -- a
        // display label threaded into every dose-amount entry/display point
        // (see Medicine.doseUnit's own doc comment), set once here rather
        // than re-typed at each of those points.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = doseUnit,
                onValueChange = { doseUnit = it },
                label = { Text(stringResource(R.string.add_medicine_measurement_optional)) },
                placeholder = { Text(stringResource(R.string.add_medicine_e_g_capsules_drops_tablet)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            // Strength (§9 #27; HM-14): the "50 mg" on the box. A label for
            // the card and the emergency card, never a dose amount.
            OutlinedTextField(
                value = strength,
                onValueChange = { strength = it },
                label = { Text(stringResource(R.string.add_medicine_strength_optional)) },
                placeholder = { Text(stringResource(R.string.add_medicine_strength_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                try {
                    scanLauncher.launch(Intent("com.google.zxing.client.android.SCAN"))
                    scanUnavailable = false
                } catch (_: ActivityNotFoundException) {
                    scanUnavailable = true
                }
            }) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(barcodeValue?.let { stringResource(R.string.add_medicine_scanned, it) } ?: stringResource(R.string.add_medicine_scan_barcode))
            }
        }
        if (scanUnavailable) {
            Text(
                stringResource(R.string.common_no_barcode_scanner_app_installed_e_g_binary),
                style = MaterialTheme.typography.labelSmall,
                // A note about an OPTIONAL convenience, not a failure. `error`
                // here told the reader something had gone wrong when nothing had.
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.common_appearance), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(MedicineIcons.options) { option ->
                val selected = option.key == iconKey
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(52.dp)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            iconKey = option.key
                            // The icon is the dosage form (§9 #27; HM-05): it
                            // fills a blank unit and never overwrites a typed one.
                            doseUnit = MedicineIcons.unitAfterPickingForm(doseUnit, option.defaultUnit?.let { resources.getString(it) })
                        },
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Icon(
                            option.icon,
                            contentDescription = stringResource(option.label),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    // A sole-content clickable: without this it announces as nothing at all.
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.add_medicine_pick_color)) { showColorPicker = true }
                    .background(color ?: NeutralMedicineAccent, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            AssistChip(onClick = { showColorPicker = true }, label = { Text(stringResource(if (color != null) R.string.add_medicine_change_color else R.string.add_medicine_pick_color)) })
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.add_medicine_tags), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = tagInput,
                onValueChange = { tagInput = it },
                label = { Text(stringResource(R.string.add_medicine_add_a_tag)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val trimmed = tagInput.trim()
                if (trimmed.isNotEmpty() && trimmed !in pendingTags) {
                    pendingTags = pendingTags + trimmed
                }
                tagInput = ""
            }) { Text(stringResource(R.string.add_medicine_add)) }
        }
        if (pendingTags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pendingTags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.a11y_remove_tag, tag),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable(role = Role.Button) { pendingTags = pendingTags - tag },
                            )
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = indication,
            onValueChange = { indication = it },
            label = { Text(stringResource(R.string.add_medicine_what_it_s_for_optional)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = storageNote,
            onValueChange = { storageNote = it },
            label = { Text(stringResource(R.string.add_medicine_storage_note_optional)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        // Rx number (§9 #27; DT-10): what the pharmacy asks for on the phone.
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = rxNumber,
            onValueChange = { rxNumber = it },
            label = { Text(stringResource(R.string.add_medicine_rx_number_optional)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        // Free-text notes (§6's inline section; the column existed from the
        // first schema and nothing wrote it -- §7 R60 C F-4, wired at the
        // owner's call). Multi-line: side effects, what the pharmacist said,
        // anything that is not a storage instruction or an indication.
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(stringResource(R.string.add_medicine_notes_optional)) },
            minLines = 2,
            maxLines = 6,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )

        // As-needed ceiling (§9 #26; Pill Time PT-01): "at most N in any H
        // hours". Shown as headroom on the Log dose dialog; at the ceiling
        // logging asks once and never refuses (the owner's choice).
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.add_medicine_prn_ceiling), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.add_medicine_prn_ceiling_desc),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = prnMax,
                onValueChange = { prnMax = it },
                label = { Text(stringResource(R.string.add_medicine_prn_max)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = prnHours,
                onValueChange = { prnHours = it },
                label = { Text(stringResource(R.string.add_medicine_prn_hours)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        // Notification priority (spec §7 R59). Medicine.notificationPriority
        // existed from the start and picked the channel a reminder posts to,
        // but no screen ever set it: every medicine was STANDARD, and the High
        // and Alarm tiers -- the full-screen AlarmActivity, the whole reason
        // FullScreenAlarmAccessCard exists -- were unreachable. Found by the
        // owner on the device when a reminder made a chime where an alarm was
        // expected. Names and descriptions are the same resources the
        // Notifications & Alerts screen and the channels themselves use.
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.add_medicine_notification_priority), style = MaterialTheme.typography.labelLarge)
        for (option in NotificationPriority.entries) {
            PriorityRow(
                selected = priority == option,
                title = stringResource(ReminderNotificationChannels.tierNameFor(option)),
                description = stringResource(channelDescriptionFor(option)),
                onSelect = { priority = option },
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.add_medicine_lock_reminders_until_taken), style = MaterialTheme.typography.labelLarge)
        // One selectable node per option (§9 #19 Stage B). These were
        // RadioButton + an unclickable Text -- the other shape of the pattern
        // Stage B fixed, which its regex did not match.
        for ((value, label) in listOf(
            null to R.string.add_medicine_use_global_default,
            true to R.string.add_medicine_always_lock,
            false to R.string.add_medicine_never_lock,
        )) {
            Row(
                Modifier.fillMaxWidth().selectable(selected = lockOverride == value, role = Role.RadioButton) { lockOverride = value },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = lockOverride == value, onClick = null)
                Text(stringResource(label))
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = name.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    saveError = null
                    scope.launch {
                        try {
                            onSave(
                                name.trim(),
                                barcodeValue,
                                iconKey,
                                color,
                                pendingTags,
                                indication.ifBlank { null },
                                storageNote.ifBlank { null },
                                notes.trim().ifBlank { null },
                                doseUnit.trim().ifBlank { null },
                                lockOverride,
                                priority,
                                prnMax.toIntOrNull()?.takeIf { it > 0 },
                                prnHours.toIntOrNull()?.takeIf { it > 0 },
                                strength.trim().ifBlank { null },
                                rxNumber.trim().ifBlank { null },
                            )
                        } catch (e: Exception) {
                            // Reset on failure, not just success -- otherwise a
                            // thrown onSave (a transient DB error, etc.) leaves
                            // Save permanently disabled with no explanation and
                            // no way to retry short of leaving the screen.
                            saving = false
                            saveError = resources.getString(R.string.error_could_not_save, e.message ?: resources.getString(R.string.error_unknown))
                        }
                    }
                },
            ) { Text(saveLabel) }
            Button(onClick = cancel) { Text(stringResource(R.string.common_cancel)) }
        }
        saveError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        }
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initialColor = color,
            onDismiss = { showColorPicker = false },
            onConfirm = { picked -> color = picked; showColorPicker = false },
            inUse = colorsInUse,
        )
    }
}

/** The channel description for a tier, so the form's second line says exactly
 *  what the Notifications & Alerts screen and Android's own settings say. */
@StringRes
private fun channelDescriptionFor(priority: NotificationPriority): Int = when (priority) {
    NotificationPriority.STANDARD -> R.string.channel_standard_desc
    NotificationPriority.HIGH -> R.string.channel_high_desc
    NotificationPriority.ALARM -> R.string.channel_alarm_desc
}

@Composable
private fun PriorityRow(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
