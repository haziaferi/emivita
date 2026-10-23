package com.hazia.emivita.feature.medicines

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.NotificationPriority
import com.hazia.emivita.core.data.entity.Tag
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.ui.components.ColorPickerDialog
import com.hazia.emivita.ui.components.NullableColorSaver
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.components.rememberDirty
import com.hazia.emivita.ui.components.toHexString
import com.hazia.emivita.ui.components.unsavedChangesGuard
import com.hazia.emivita.ui.form.DialogActionPair
import com.hazia.emivita.ui.form.FieldHelpLine
import com.hazia.emivita.ui.form.FieldPair
import com.hazia.emivita.ui.form.FormGroupRow
import com.hazia.emivita.ui.form.FormScaffold
import com.hazia.emivita.ui.form.FormSwitchRow
import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.ui.form.GridToggleCell
import com.hazia.emivita.ui.form.SecondaryControlHeight
import com.hazia.emivita.ui.icons.MedicineIconOption
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
    val otherNames = rememberOtherNames(medicineRepository, profileId)
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
        otherNames = otherNames,
        onSave = { name, barcodeValue, iconKey, color, tagNames, indication, storageNote, notes, doseUnit, lockOverride, priority, prnMax, prnHours, strength, rxNumber, asNeeded ->
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
                    asNeeded = asNeeded,
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
 * Edit Medicine (spec §9 item 1): reuses [MedicineFormBody] seeded with the
 * existing [Medicine] and its tags, and saves tags as a plain set diff against
 * that snapshot, enough for a single-user, single-device app (spec §1).
 *
 * Delete (§7 R125) is the form's tertiary text action when [onDeleted] is
 * given: it asks as the detail screen's Delete does, then archives the
 * medicine (§9 #26), never the cascade.
 */
@Composable
fun EditMedicineScreen(
    medicineId: Long,
    medicineRepository: MedicineRepository,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    onDeleted: (() -> Unit)? = null,
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
    val otherNames = rememberOtherNames(medicineRepository, current.profileId, excludeId = medicineId)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (confirmDelete && onDeleted != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.medicine_detail_delete_title, current.name)) },
            text = { Text(stringResource(R.string.medicine_detail_this_removes_every_reminder_and_all_dose)) },
            confirmButton = {
                DialogActionPair(stringResource(R.string.medicine_detail_delete), destructive = true, onDismiss = { confirmDelete = false }, onConfirm = {
                    confirmDelete = false
                    scope.launch { medicineRepository.archive(current); onDeleted() }
                })
            },
        )
    }
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
        initialAsNeeded = current.asNeeded,
        initialStrength = current.strength ?: "",
        initialRxNumber = current.rxNumber ?: "",
        initialDoseUnit = current.doseUnit ?: "",
        initialLockOverride = current.remindersLockedUntilTaken,
        initialPriority = current.notificationPriority,
        colorsInUse = colorsInUse,
        otherNames = otherNames,
        onDelete = onDeleted?.let { { confirmDelete = true } },
        onSave = { name, barcodeValue, iconKey, color, tagNames, indication, storageNote, notes, doseUnit, lockOverride, priority, prnMax, prnHours, strength, rxNumber, asNeeded ->
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
                    asNeeded = asNeeded,
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

/** The profile's other medicines' names, for the duplicate-name warning
 *  (§7 R114); an edit leaves its own medicine out. */
@Composable
private fun rememberOtherNames(medicineRepository: MedicineRepository, profileId: Long, excludeId: Long? = null): List<String> {
    val medicines by medicineRepository.observeForProfile(profileId).collectAsState(initial = emptyList())
    return remember(medicines, excludeId) { medicines.map { it.medicine }.filter { it.id != excludeId }.map { it.name } }
}

/** Shared body for both Add and Edit -- see AddMedicineScreen/EditMedicineScreen
 *  above for what differs (title/button text, initial values, and what the
 *  final onSave callback actually does with the field values). */
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
    initialAsNeeded: Boolean = false,
    initialStrength: String = "",
    initialRxNumber: String = "",
    initialDoseUnit: String,
    initialLockOverride: Boolean?,
    initialPriority: NotificationPriority,
    /** Other medicines' accent colours, for the picker's differentiation hint
     *  (spec §3, §7 R47). Excludes the medicine being edited -- a colour is
     *  not "too close to" itself. */
    colorsInUse: List<Pair<String, Color>>,
    otherNames: List<String>,
    onDelete: (() -> Unit)? = null,
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
        asNeeded: Boolean,
    ) -> Unit,
    onCancel: () -> Unit,
) {
    // Every field is saveable, so a rotation or a process death keeps the
    // input (§7 R83 (a)).
    var name by rememberSaveable { mutableStateOf(initialName) }
    var priority by rememberSaveable { mutableStateOf(initialPriority) }
    var barcodeValue by rememberSaveable { mutableStateOf(initialBarcodeValue) }
    var iconKey by rememberSaveable { mutableStateOf(initialIconKey) }
    var color by rememberSaveable(stateSaver = NullableColorSaver) { mutableStateOf(initialColor) }
    var showColorPicker by rememberSaveable { mutableStateOf(false) }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var pendingTags by rememberSaveable { mutableStateOf(initialTagNames) }
    // Every tag shown this visit, so one turned off stays as an off chip to turn back on.
    var shownTags by rememberSaveable { mutableStateOf(initialTagNames) }
    var indication by rememberSaveable { mutableStateOf(initialIndication) }
    var storageNote by rememberSaveable { mutableStateOf(initialStorageNote) }
    var notes by rememberSaveable { mutableStateOf(initialNotes) }
    var prnMax by rememberSaveable { mutableStateOf(initialPrnMaxDoses?.toString() ?: "") }
    var prnHours by rememberSaveable { mutableStateOf(initialPrnWindowHours?.toString() ?: "") }
    var asNeeded by rememberSaveable { mutableStateOf(initialAsNeeded) }
    var strength by rememberSaveable { mutableStateOf(initialStrength) }
    var rxNumber by rememberSaveable { mutableStateOf(initialRxNumber) }
    val resources = LocalResources.current
    var doseUnit by rememberSaveable { mutableStateOf(initialDoseUnit) }
    // null = inherit global default, true/false = explicit override
    var lockOverride by rememberSaveable { mutableStateOf(initialLockOverride) }
    var open by rememberSaveable { mutableStateOf<MedicineGroup?>(null) }
    // §9 #29 (HM-40): every field a save would persist, against its first value.
    val dirty = rememberDirty(Unit, name, priority, barcodeValue, iconKey, color, pendingTags, indication, storageNote, notes, prnMax, prnHours, asNeeded, strength, rxNumber, doseUnit, lockOverride)
    val cancel = unsavedChangesGuard(dirty, onCancel)
    var saving by remember { mutableStateOf(false) } // transient: a save in flight does not survive the activity
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // A limit is both numbers or neither (§7 R114): one alone was saved and
    // Today's Log dose then showed no ceiling.
    val prnCheck = checkPrnCeiling(prnMax, prnHours)

    fun addTag() {
        val trimmed = tagInput.trim()
        if (trimmed.isNotEmpty()) {
            if (trimmed !in pendingTags) pendingTags = pendingTags + trimmed
            if (trimmed !in shownTags) shownTags = shownTags + trimmed
        }
        tagInput = ""
    }

    fun save() {
        saving = true
        saveError = null
        scope.launch {
            try {
                onSave(
                    name.trim(), barcodeValue, iconKey, color, pendingTags,
                    indication.ifBlank { null }, storageNote.ifBlank { null }, notes.trim().ifBlank { null },
                    doseUnit.trim().ifBlank { null }, lockOverride, priority,
                    prnMax.trim().toIntOrNull()?.takeIf { it > 0 }, prnHours.trim().toIntOrNull()?.takeIf { it > 0 },
                    strength.trim().ifBlank { null }, rxNumber.trim().ifBlank { null }, asNeeded,
                )
            } catch (e: Exception) {
                // Reset on failure, not just success: a thrown onSave would otherwise
                // leave Save off for good with no way to retry.
                saving = false
                saveError = resources.getString(R.string.error_could_not_save, e.message ?: resources.getString(R.string.error_unknown))
            }
        }
    }

    FormScaffold(
        title = title,
        saveLabel = saveLabel,
        saveEnabled = name.isNotBlank() && prnCheck.valid && !saving,
        onSave = ::save,
        onCancel = cancel,
        deleteLabel = onDelete?.let { stringResource(R.string.medicine_detail_delete) },
        onDelete = onDelete,
    ) {
        MedicineNameField(name, { name = it }, otherNames)
        Spacer(Modifier.height(8.dp))

        // What a dose is measured in, and the "50 mg" on the box (§9 #27; HM-14):
        // a label for the card and the emergency card, never a dose amount.
        val unitLabel = stringResource(R.string.add_medicine_measurement_optional)
        val unitHelp = stringResource(R.string.add_medicine_e_g_capsules_drops_tablet)
        val strengthLabel = stringResource(R.string.add_medicine_strength_optional)
        val strengthHelp = stringResource(R.string.add_medicine_strength_hint)
        FieldPair(
            listOf(unitLabel, unitHelp, strengthLabel, strengthHelp),
            first = { m -> FormTextField(doseUnit, { doseUnit = it }, unitLabel, m, help = unitHelp) },
            second = { m -> FormTextField(strength, { strength = it }, strengthLabel, m, help = strengthHelp) },
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.common_appearance))
        AppearanceGrid(
            iconKey = iconKey,
            color = color,
            onIcon = { option ->
                iconKey = option.key
                // The icon is the dosage form (§9 #27; HM-05): it fills a blank
                // unit and never overwrites a typed one.
                doseUnit = MedicineIcons.unitAfterPickingForm(doseUnit, option.defaultUnit?.let { resources.getString(it) })
            },
            onPickColor = { showColorPicker = true },
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.add_medicine_tags))
        FormTextField(
            tagInput, { tagInput = it }, stringResource(R.string.add_medicine_add_a_tag), onDone = ::addTag,
            trailingIcon = { IconButton(onClick = ::addTag) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.medicine_add_tag)) } },
        )
        if (shownTags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // The owner (2026-10-23): tag chips take their own width and wrap, left-aligned.
            // A chip on is a tag this medicine has; tapping it takes the tag off, as the chip's
            // close icon did (§7 R125).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shownTags.forEach { tag ->
                    GridToggleCell(tag, tag in pendingTags) { on -> pendingTags = if (on) pendingTags + tag else pendingTags - tag }
                }
            }
        }

        // Taken when needed (§7 R116, the owner's switch).
        Spacer(Modifier.height(16.dp))
        FormSwitchRow(stringResource(R.string.add_medicine_as_needed), stringResource(R.string.add_medicine_as_needed_desc), asNeeded) { asNeeded = it }

        // As-needed ceiling (§9 #26; Pill Time PT-01). The limit is not the switch's:
        // getTakenSince counts every TAKEN dose and Log dose shows the headroom for any
        // medicine (R116), so it has its own title and stays enabled.
        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.add_medicine_prn_ceiling))
        val maxLabel = stringResource(R.string.add_medicine_prn_max)
        val hoursLabel = stringResource(R.string.add_medicine_prn_hours)
        FieldPair(
            listOf(maxLabel, hoursLabel),
            first = { m -> FormTextField(prnMax, { prnMax = it }, maxLabel, m, keyboardType = KeyboardType.Number, flagError = prnCheck.maxError != null) },
            second = { m -> FormTextField(prnHours, { prnHours = it }, hoursLabel, m, keyboardType = KeyboardType.Number, flagError = prnCheck.hoursError != null) },
        )
        // One line for the pair: the help, or the first error in its place.
        FieldHelpLine(stringResource(R.string.add_medicine_prn_ceiling_desc), (prnCheck.maxError ?: prnCheck.hoursError)?.let { stringResource(it) })

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.add_reminder_advanced))
        FormGroupRow(stringResource(MedicineGroup.NOTIFICATION.title), notificationSummary(priority, lockOverride)) { open = MedicineGroup.NOTIFICATION }
        FormGroupRow(stringResource(MedicineGroup.DETAILS.title), detailsSummary(indication, storageNote, rxNumber, notes, barcodeValue)) { open = MedicineGroup.DETAILS }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
    }

    when (open) {
        MedicineGroup.NOTIFICATION -> MedicineNotificationSheet(priority, lockOverride, close = { open = null }) { p, l -> priority = p; lockOverride = l }
        MedicineGroup.DETAILS -> MedicineDetailsSheet(
            MedicineDetails(barcodeValue ?: "", indication, storageNote, rxNumber, notes), close = { open = null },
        ) { d ->
            barcodeValue = d.barcode.trim().ifBlank { null }
            indication = d.indication; storageNote = d.storage; rxNumber = d.rxNumber; notes = d.notes
        }
        null -> Unit
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

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
}

/**
 * The icons and the colour as one grid of 42 dp cells (§7 R125): rows of seven from the form's left
 * edge to its right, the colour swatch the last cell; a short last row keeps the gap.
 */
@Composable
private fun AppearanceGrid(iconKey: String, color: Color?, onIcon: (MedicineIconOption) -> Unit, onPickColor: () -> Unit) {
    val options = MedicineIcons.options
    val shape = FilterChipDefaults.shape
    val pickColour = stringResource(R.string.add_medicine_pick_color)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = ((maxWidth - SecondaryControlHeight * 7) / 6).coerceAtLeast(0.dp)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            (0..options.size).chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (row.size == 7) Arrangement.SpaceBetween else Arrangement.spacedBy(gap)) {
                    row.forEach { i ->
                        if (i < options.size) {
                            val option = options[i]
                            val selected = option.key == iconKey
                            Box(
                                Modifier.size(SecondaryControlHeight)
                                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                                    .selectable(selected = selected, role = Role.RadioButton) { onIcon(option) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    option.icon, contentDescription = stringResource(option.label),
                                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            // The colour as the grid's last cell: the swatch itself inside a ring.
                            Box(
                                Modifier.size(SecondaryControlHeight)
                                    .semantics { contentDescription = pickColour }
                                    .clickable(role = Role.Button, onClickLabel = pickColour, onClick = onPickColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                                    .padding(5.dp)
                                    .background(color ?: NeutralMedicineAccent, shape),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What is wrong with each half of the as-needed limit, as a string id, or null. */
internal data class PrnCeilingCheck(@StringRes val maxError: Int?, @StringRes val hoursError: Int?) {
    val valid get() = maxError == null && hoursError == null
}

/** Both a whole number above zero, or both empty (§7 R114). A half with text
 *  that is not such a number says so; an empty half beside a filled one asks
 *  for both. */
internal fun checkPrnCeiling(max: String, hours: String): PrnCeilingCheck {
    fun bad(s: String) = s.isNotBlank() && (s.trim().toIntOrNull() ?: 0) <= 0
    fun half(own: String, other: String): Int? = when {
        bad(own) -> R.string.add_medicine_prn_whole
        own.isBlank() && other.isNotBlank() -> R.string.add_medicine_prn_both
        else -> null
    }
    return PrnCeilingCheck(half(max, hours), half(hours, max))
}
