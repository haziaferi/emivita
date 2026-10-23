package com.hazia.emivita.feature.medicines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.encodeReminderConfig
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.feature.reminders.AdvancedReminderFieldsState
import com.hazia.emivita.ui.components.TimeOfDayField
import com.hazia.emivita.ui.components.rememberDirty
import com.hazia.emivita.ui.icons.MedicineIcons
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * D-07, adding a medicine (§7 R114, the owner's pick V3 of R106's scoring):
 * the Medicines tab's add button opens this one sheet. Built from the app's
 * own parts (ModalBottomSheet as Today's sheets, TimeOfDayField, the
 * unsaved-changes strings); the full form stays as "add details".
 */

/** Two names are the same medicine's when they differ only in case and
 *  spaces (§7 R114): "Vitamina D", "vitamina d" and " VitaminaD ". */
internal fun sameMedicineName(a: String, b: String): Boolean {
    fun key(s: String) = s.filterNot { it.isWhitespace() }.lowercase()
    return key(a).isNotEmpty() && key(a) == key(b)
}

/**
 * The name field of both the sheet and the full form (§7 R114). Blank is
 * said once the field has been visited and left; a name the profile already
 * has is said under it as a warning, and Save still works. [otherNames] are
 * the profile's other medicines, so an edit never warns about itself.
 */
@Composable
internal fun MedicineNameField(name: String, onNameChange: (String) -> Unit, otherNames: List<String>) {
    var visited by rememberSaveable { mutableStateOf(false) }
    var left by rememberSaveable { mutableStateOf(false) }
    val blank = left && name.isBlank()
    val duplicate = !blank && otherNames.any { sameMedicineName(it, name) }
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.common_name)) },
        isError = blank,
        supportingText = when {
            blank -> { { Text(stringResource(R.string.medicine_name_blank)) } }
            duplicate -> { { Text(stringResource(R.string.medicine_name_duplicate)) } }
            else -> null
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) visited = true else if (visited) left = true },
    )
}

/** What the sheet hands back: the medicine and its first reminder. */
internal data class QuickAddInput(val name: String, val dose: Double, val unit: String?, val hour: Int, val minute: Int)

/**
 * The one sheet: name, dose amount and unit, and the first reminder's time,
 * every day as Add reminder's own default. Save creates both; "Save and add
 * details" saves the same and hands the new id on for the full edit form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickAddMedicineSheet(
    otherNames: List<String>,
    save: suspend (QuickAddInput) -> Long,
    onSaved: (medicineId: Long, addDetails: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Saveable throughout, so a rotation or a process death keeps the input (§7 R83 (a)).
    var name by rememberSaveable { mutableStateOf("") }
    var dose by rememberSaveable { mutableStateOf(formatDecimalLocalized(1.0)) }
    var unit by rememberSaveable { mutableStateOf("") }
    var hour by rememberSaveable { mutableIntStateOf(8) }
    var minute by rememberSaveable { mutableIntStateOf(0) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) } // transient: a save in flight does not survive the activity
    var saveError by remember { mutableStateOf<String?>(null) }
    val resources = LocalResources.current
    val doseValue = parseDecimal(dose)
    // Said on the field as it is typed, as the colour picker's hex field does.
    val doseInvalid = doseValue == null || doseValue <= 0
    val canSave = name.isNotBlank() && !doseInvalid && !saving
    val dirty = rememberDirty(Unit, name, dose, unit, hour, minute)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun submit(addDetails: Boolean) {
        val input = QuickAddInput(name.trim(), checkNotNull(doseValue), unit.trim().ifBlank { null }, hour, minute)
        saving = true
        saveError = null
        scope.launch {
            // Whole once begun: closing the sheet must not cut a save in half.
            val id = withContext(NonCancellable) { runCatching { save(input) } }
            id.onSuccess { onSaved(it, addDetails) }.onFailure { e ->
                saving = false
                saveError = resources.getString(R.string.error_could_not_save, e.message ?: resources.getString(R.string.error_unknown))
            }
        }
    }

    ModalBottomSheet(
        // Back, a swipe down or a tap outside: once touched, ask first (§9 #29, HM-40).
        onDismissRequest = { if (dirty) asking = true else onDismiss() },
        sheetState = sheetState,
    ) {
        // Scrolls, as Settings' sheets do, so Save stays reachable at a large font.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.add_medicine_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            MedicineNameField(name, { name = it }, otherNames)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dose,
                    onValueChange = { dose = it },
                    label = { Text(stringResource(R.string.common_dose_amount)) },
                    isError = doseInvalid,
                    supportingText = if (doseInvalid) { { Text(stringResource(R.string.quick_add_dose_invalid)) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text(stringResource(R.string.add_medicine_measurement_optional)) },
                    placeholder = { Text(stringResource(R.string.add_medicine_e_g_capsules_drops_tablet)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            TimeOfDayField(stringResource(R.string.add_reminder_time), hour, minute) { h, m -> hour = h; minute = m }
            Text(
                stringResource(R.string.field_label_value, stringResource(R.string.add_reminder_repeats), stringResource(R.string.common_daily)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(enabled = canSave, onClick = { submit(addDetails = false) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_save))
            }
            TextButton(enabled = canSave, onClick = { submit(addDetails = true) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.quick_add_more_details))
            }
            saveError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false; scope.launch { sheetState.show() } },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_text)) },
            confirmButton = { TextButton(onClick = { asking = false; onDismiss() }) { Text(stringResource(R.string.unsaved_discard)) } },
            dismissButton = { TextButton(onClick = { asking = false; scope.launch { sheetState.show() } }) { Text(stringResource(R.string.unsaved_keep)) } },
        )
    }
}

/** The sheet's Save: a new medicine with every default the full form would
 *  give it, and its daily fixed-time reminder with Add reminder's defaults
 *  (B-16's repeat included), written together (§7 R114). */
internal suspend fun saveQuickAdd(medicineRepository: MedicineRepository, profileId: Long, input: QuickAddInput): Long {
    val now = System.currentTimeMillis()
    val advanced = AdvancedReminderFieldsState(null).snapshot()
    return medicineRepository.createWithFirstReminder(
        Medicine(
            profileId = profileId,
            name = input.name,
            iconKey = MedicineIcons.default.key,
            sortOrder = now.toDouble(),
            doseUnit = input.unit,
            createdAtEpochMillis = now,
        ),
        Reminder(
            medicineId = 0, // set to the new medicine's id inside the transaction
            type = ReminderType.FIXED_TIME,
            configJson = encodeReminderConfig(ReminderConfig.FixedTime(input.hour, input.minute, input.dose, RepeatPattern.Daily)),
            autoResolve = advanced.autoResolve,
            activeFromEpochDay = advanced.activeFromEpochDay,
            activeUntilEpochDay = advanced.activeUntilEpochDay,
            repeatOnUnanswered = advanced.repeatOnUnanswered,
            weekendModeOptIn = advanced.weekendModeOptIn,
            preAlarmMinutes = advanced.preAlarmMinutes,
            instructions = advanced.instructions,
            dosesRemaining = advanced.dosesRemaining,
            stickyUntilAnswered = advanced.stickyUntilAnswered,
            variableAmount = advanced.variableAmount,
            createdAtEpochMillis = now,
        ),
    )
}
