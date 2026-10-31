package com.hazia.emivita.feature.medicines

import com.hazia.emivita.ui.form.DialogActionPair
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import com.hazia.emivita.feature.settings.DetailHeader
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import com.hazia.emivita.core.format.labelRes
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.hazia.emivita.core.data.entity.MedicineNote
import com.hazia.emivita.core.format.LocaleFormats
import androidx.compose.foundation.text.selection.SelectionContainer
import com.hazia.emivita.ui.components.MedicineTile

/**
 * Medicine detail: read-only summary + its reminders list + "Add reminder",
 * plus (spec §9 item 1's "immediate follow-up") an edit action for the
 * medicine itself and a tap-to-edit affordance on each reminder row.
 * Delete (spec §9 #1's deferred data-integrity question, resolved: cascade)
 * and "Stock & Expiration" (§6/§9 #1) both live in the overflow menu now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicineDetailScreen(
    medicineId: Long,
    medicineRepository: MedicineRepository,
    reminderRepository: ReminderRepository,
    doseEventRepository: DoseEventRepository,
    onAddReminder: () -> Unit,
    onEditMedicine: () -> Unit,
    onEditReminder: (reminderId: Long) -> Unit,
    onOpenStockExpiration: () -> Unit,
    onDeleted: () -> Unit,
    onDuplicated: (newMedicineId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val copySuffix = stringResource(R.string.medicine_detail_copy_suffix)
    val medicine by produceState<Medicine?>(initialValue = null, medicineId) {
        medicineRepository.observeById(medicineId).collect { value = it }
    }
    val reminders by produceState<List<Reminder>>(initialValue = emptyList(), medicineId) {
        reminderRepository.observeForMedicine(medicineId).collect { value = it }
    }
    // The reminders a following dose follows, which may be another medicine's,
    // so its row can name the one it comes after (§7 R120).
    val parents by produceState<Map<Long, Reminder>>(initialValue = emptyMap(), reminders) {
        value = reminders.mapNotNull { it.parentReminderId }.distinct()
            .mapNotNull { id -> reminders.firstOrNull { it.id == id } ?: reminderRepository.getById(id) }
            .associateBy { it.id }
    }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // No Scaffold of its own (§9 #34): this screen composes inside the
    // NavHost's Scaffold, and a nested one consumed the status-bar inset a
    // second time -- the "tall header" §7 R59 flagged and never fixed. The
    // header is the settings screens' DetailHeader with the two actions on
    // its row; the FAB floats in a Box and the list keeps clear of it.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                title = medicine?.name ?: "",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onEditMedicine) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.medicine_detail_edit_medicine))
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.medicine_detail_more))
                        }
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_stock_expiration)) },
                                onClick = { showOverflowMenu = false; onOpenStockExpiration() },
                            )
                            // §9 #24: duplicate (§6's planned pair) and pause/resume all.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.medicine_detail_duplicate)) },
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        medicineRepository.duplicate(medicineId, copySuffix, withReminders = false, reminderRepository)?.let(onDuplicated)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.medicine_detail_duplicate_with_reminders)) },
                                onClick = {
                                    showOverflowMenu = false
                                    scope.launch {
                                        medicineRepository.duplicate(medicineId, copySuffix, withReminders = true, reminderRepository)?.let(onDuplicated)
                                    }
                                },
                            )
                            val anyDosingEnabled = reminders.any { it.enabled && it.type != ReminderType.LOW_STOCK_ALERT && it.type != ReminderType.EXPIRATION_ALERT }
                            if (reminders.any { it.type != ReminderType.LOW_STOCK_ALERT && it.type != ReminderType.EXPIRATION_ALERT }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (anyDosingEnabled) R.string.medicine_detail_pause_all else R.string.medicine_detail_resume_all)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        scope.launch { reminderRepository.setAllDosingEnabled(medicineId, enabled = !anyDosingEnabled) }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.medicine_detail_delete)) },
                                onClick = { showOverflowMenu = false; showDeleteConfirm = true },
                            )
                        }
                    }
                },
            )
            // Everything under the header scrolls as one (§7 R137): fixed above the list, the figures, the journal and the
            // rest left the reminders 29 dp at font 1.8 on the 9 Pro and none at 2.0. One item, always first, as on Today.
            // 96 dp beneath the last row: the FAB's 56 dp plus its margins, so the last reminder's switch is never under it.
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "summary") {
                    Column {
                        val currentMedicine = medicine
                        if (currentMedicine != null) {
                            MedicineFigures(currentMedicine, doseEventRepository)
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MedicineTile(currentMedicine, size = 48.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(currentMedicine.name, style = MaterialTheme.typography.titleLarge)
                                    currentMedicine.strength?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    currentMedicine.indication?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            currentMedicine.notes?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            currentMedicine.rxNumber?.takeIf { it.isNotBlank() }?.let {
                                // Selectable so it can be read out to the pharmacy or copied.
                                SelectionContainer {
                                    Text(
                                        stringResource(R.string.medicine_detail_rx, it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            currentMedicine.storageNote?.let {
                                Text(
                                    stringResource(R.string.medicine_detail_storage, it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                        }

                        NotesJournal(medicineId, medicineRepository)

                        Text(
                            stringResource(R.string.medicine_detail_reminders),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        if (reminders.isEmpty()) {
                            Text(
                                stringResource(R.string.medicine_detail_no_reminders_yet_tap_to_add_one),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
                items(reminders, key = { it.id }) { reminder ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        ReminderRow(
                            reminder = reminder,
                            doseUnit = medicine?.doseUnit,
                            parent = reminder.parentReminderId?.let { parents[it] },
                            onEdit = { onEditReminder(reminder.id) },
                            onEnabledChange = { enabled -> scope.launch { reminderRepository.setEnabled(reminder, enabled) } },
                        )
                    }
                }
            }
        }
        FloatingActionButton(onClick = onAddReminder, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.common_add_reminder))
        }
    }

    if (showDeleteConfirm) {
        val currentMedicine = medicine
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    stringResource(
                        R.string.medicine_detail_delete_title,
                        currentMedicine?.name ?: stringResource(R.string.medicine_detail_this_medicine),
                    ),
                )
            },
            text = { Text(stringResource(R.string.medicine_detail_this_removes_every_reminder_and_all_dose)) },
            confirmButton = {
                DialogActionPair(stringResource(R.string.common_confirm), destructive = true, onDismiss = { showDeleteConfirm = false }, onConfirm = onConfirm@{
                    showDeleteConfirm = false
                    val toDelete = currentMedicine ?: return@onConfirm
                    scope.launch {
                        medicineRepository.archive(toDelete)
                        onDeleted()
                    }
                })
            },
        )
    }
}

/**
 * One reminder on the detail screen (§9 #24, owner's choice): the row opens
 * the editor and the switch pauses or resumes, two nodes on purpose because
 * they do different things, so the switch carries its own label ("Enabled,
 * Fixed time"). An alert reminder (low stock / expiry) has no switch here,
 * since the stock screen owns those.
 *
 * Its text is [reminderRowText], "08:00 · Daily · 1 drops", with the kind
 * beneath it (§7 R120); an alert keeps its kind as its text.
 */
@Composable
internal fun ReminderRow(
    reminder: Reminder,
    onEdit: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    doseUnit: String? = null,
    parent: Reminder? = null,
) {
    val isAlert = reminder.type == ReminderType.LOW_STOCK_ALERT || reminder.type == ReminderType.EXPIRATION_ALERT
    val typeLabel = stringResource(reminder.type.labelRes())
    val resources = LocalResources.current
    val summary = remember(reminder, doseUnit, parent, resources) { reminderRowText(reminder, doseUnit, parent, resources) }
    val switchLabel = stringResource(R.string.reminder_enabled_switch, typeLabel)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (reminder.enabled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(role = Role.Button, onClick = onEdit),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(summary ?: typeLabel, style = MaterialTheme.typography.bodyMedium)
                if (summary != null) {
                    Text(
                        typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!reminder.enabled) {
                    Text(
                        stringResource(R.string.reminder_paused),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!isAlert) {
                Switch(
                    checked = reminder.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                )
            }
        }
    }
}

/**
 * The notes journal (§9 #26, Appendix F MT-04): dated entries, newest first,
 * for what a standing notes field cannot say -- "dizzy for two days after
 * the dose went up". Add at the top, tap an entry to edit, delete from the
 * edit dialog, and only the three most recent show until "show all" expands.
 */
@Composable
internal fun NotesJournal(medicineId: Long, medicineRepository: MedicineRepository) {
    val notes by produceState<List<MedicineNote>>(initialValue = emptyList(), medicineId) {
        medicineRepository.observeNotes(medicineId).collect { value = it }
    }
    val scope = rememberCoroutineScope()
    // Saveable: the journal is a row of the page's list (§7 R137), and a plain remember lost a half-typed note scrolled off.
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<MedicineNote?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.medicine_detail_journal), style = MaterialTheme.typography.labelLarge)
        // Add on its own line, end-aligned (§7 R137): beside the field it left the field a sliver at font 1.8.
        androidx.compose.material3.OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(R.string.medicine_detail_journal_hint)) },
            maxLines = com.hazia.emivita.ui.form.FreeTextMaxLines,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            enabled = draft.isNotBlank(),
            onClick = { scope.launch { medicineRepository.addNote(medicineId, draft); draft = "" } },
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.medicine_detail_journal_add)) }
        val shown = if (showAll) notes else notes.take(3)
        shown.forEach { note ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.medicine_detail_journal_edit)) { editing = note },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(note.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        LocaleFormats.monthDayTime(note.createdAtEpochMillis) +
                            (note.editedAtEpochMillis?.let { " · " + stringResource(R.string.medicine_detail_journal_edited) } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (notes.size > 3 && !showAll) {
            TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.medicine_detail_journal_show_all, notes.size)) }
        }
        Spacer(Modifier.height(8.dp))
    }

    editing?.let { note ->
        var text by remember(note.id) { mutableStateOf(note.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.medicine_detail_journal_edit)) },
            text = {
                com.hazia.emivita.ui.form.FormTextField(text, { text = it }, "", freeText = true)
            },
            confirmButton = {
                DialogActionPair(
                    stringResource(R.string.common_save), confirmEnabled = text.isNotBlank(),
                    onConfirm = { scope.launch { medicineRepository.editNote(note, text); editing = null } },
                    onDismiss = { editing = null },
                    tertiary = stringResource(R.string.medicine_detail_delete),
                    onTertiary = { scope.launch { medicineRepository.deleteNote(note.id); editing = null } },
                )
            },
        )
    }
}
