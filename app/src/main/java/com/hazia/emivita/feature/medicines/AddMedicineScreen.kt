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
        title = "Add medicine",
        saveLabel = "Save",
        initialName = "",
        initialBarcodeValue = prefilledBarcode,
        initialIconKey = MedicineIcons.default.key,
        initialColor = null,
        initialTagNames = emptyList(),
        initialIndication = "",
        initialStorageNote = "",
        initialDoseUnit = "",
        initialLockOverride = null,
        colorsInUse = colorsInUse,
        onSave = { name, barcodeValue, iconKey, color, tagNames, indication, storageNote, doseUnit, lockOverride ->
            val id = medicineRepository.create(
                Medicine(
                    profileId = profileId,
                    name = name,
                    iconKey = iconKey,
                    color = color?.toHexString(),
                    remindersLockedUntilTaken = lockOverride,
                    sortOrder = System.currentTimeMillis().toDouble(),
                    barcodeValue = barcodeValue,
                    indication = indication,
                    storageNote = storageNote,
                    doseUnit = doseUnit,
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
            Text(if (loaded) "Medicine not found." else "Loading...", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val tagsSnapshot = existingTags
    val colorsInUse = rememberColorsInUse(medicineRepository, current.profileId, excludeId = medicineId)
    MedicineFormBody(
        title = "Edit medicine",
        saveLabel = "Save changes",
        initialName = current.name,
        initialBarcodeValue = current.barcodeValue,
        initialIconKey = current.iconKey,
        initialColor = current.color?.let { parseHexColor(it) },
        initialTagNames = tagsSnapshot.map { it.name },
        initialIndication = current.indication ?: "",
        initialStorageNote = current.storageNote ?: "",
        initialDoseUnit = current.doseUnit ?: "",
        initialLockOverride = current.remindersLockedUntilTaken,
        colorsInUse = colorsInUse,
        onSave = { name, barcodeValue, iconKey, color, tagNames, indication, storageNote, doseUnit, lockOverride ->
            medicineRepository.update(
                current.copy(
                    name = name,
                    iconKey = iconKey,
                    color = color?.toHexString(),
                    remindersLockedUntilTaken = lockOverride,
                    barcodeValue = barcodeValue,
                    indication = indication,
                    storageNote = storageNote,
                    doseUnit = doseUnit,
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
    initialDoseUnit: String,
    initialLockOverride: Boolean?,
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
        doseUnit: String?,
        lockOverride: Boolean?,
    ) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var barcodeValue by remember { mutableStateOf(initialBarcodeValue) }
    var iconKey by remember { mutableStateOf(initialIconKey) }
    var color by remember { mutableStateOf(initialColor) }
    var showColorPicker by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    var pendingTags by remember { mutableStateOf(initialTagNames) }
    var indication by remember { mutableStateOf(initialIndication) }
    var storageNote by remember { mutableStateOf(initialStorageNote) }
    var doseUnit by remember { mutableStateOf(initialDoseUnit) }
    // null = inherit global default, true/false = explicit override
    var lockOverride by remember { mutableStateOf(initialLockOverride) }
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
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        // What a dose is measured in ("capsules", "drops", "tablet") -- a
        // display label threaded into every dose-amount entry/display point
        // (see Medicine.doseUnit's own doc comment), set once here rather
        // than re-typed at each of those points.
        OutlinedTextField(
            value = doseUnit,
            onValueChange = { doseUnit = it },
            label = { Text("Measurement (optional)") },
            placeholder = { Text("e.g. capsules, drops, tablet") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
                Text(if (barcodeValue != null) "Scanned: $barcodeValue" else "Scan barcode")
            }
        }
        if (scanUnavailable) {
            Text(
                "No barcode scanner app installed (e.g. Binary Eye from F-Droid).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("Appearance", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(MedicineIcons.options) { option ->
                val selected = option.key == iconKey
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(52.dp)
                        .clickable { iconKey = option.key },
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Icon(
                            option.icon,
                            contentDescription = option.label,
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
                    .clickable { showColorPicker = true }
                    .background(color ?: NeutralMedicineAccent, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            AssistChip(onClick = { showColorPicker = true }, label = { Text(color?.let { "Change color" } ?: "Pick a color") })
        }

        Spacer(Modifier.height(20.dp))
        Text("Tags", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = tagInput,
                onValueChange = { tagInput = it },
                label = { Text("Add a tag") },
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
            }) { Text("Add") }
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
                                contentDescription = "Remove",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { pendingTags = pendingTags - tag },
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
            label = { Text("What it's for (optional)") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = storageNote,
            onValueChange = { storageNote = it },
            label = { Text("Storage note (optional)") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Text("Lock reminders until taken", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = lockOverride == null, onClick = { lockOverride = null })
            Text("Use global default")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = lockOverride == true, onClick = { lockOverride = true })
            Text("Always lock")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = lockOverride == false, onClick = { lockOverride = false })
            Text("Never lock")
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
                                doseUnit.trim().ifBlank { null },
                                lockOverride,
                            )
                        } catch (e: Exception) {
                            // Reset on failure, not just success -- otherwise a
                            // thrown onSave (a transient DB error, etc.) leaves
                            // Save permanently disabled with no explanation and
                            // no way to retry short of leaving the screen.
                            saving = false
                            saveError = "Couldn't save: ${e.message ?: "unknown error"}"
                        }
                    }
                },
            ) { Text(saveLabel) }
            Button(onClick = onCancel) { Text("Cancel") }
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
