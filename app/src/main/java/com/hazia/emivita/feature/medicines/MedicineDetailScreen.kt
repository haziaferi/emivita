package com.hazia.emivita.feature.medicines

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.icons.MedicineIcons
import com.hazia.emivita.ui.theme.NeutralMedicineAccent
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
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(medicine?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddReminder) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.common_add_reminder))
            }
        },
    ) { padding ->
        val currentMedicine = medicine
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (currentMedicine != null) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val swatch = currentMedicine.color?.let { parseHexColor(it) } ?: NeutralMedicineAccent
                    Surface(shape = CircleShape, color = swatch, modifier = Modifier.size(48.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(MedicineIcons.iconFor(currentMedicine.iconKey), contentDescription = null, tint = Color.White)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(currentMedicine.name, style = MaterialTheme.typography.titleLarge)
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
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(reminders, key = { it.id }) { reminder ->
                        ReminderRow(
                            reminder = reminder,
                            onEdit = { onEditReminder(reminder.id) },
                            onEnabledChange = { enabled -> scope.launch { reminderRepository.setEnabled(reminder, enabled) } },
                        )
                    }
                }
            }
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
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val toDelete = currentMedicine ?: return@TextButton
                    scope.launch {
                        medicineRepository.archive(toDelete)
                        onDeleted()
                    }
                }) { Text(stringResource(R.string.medicine_detail_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * One reminder on the detail screen (§9 #24, owner's choice): the row opens
 * the editor; the switch pauses or resumes. Two nodes on purpose -- they do
 * two different things, unlike the Stage B rows where a switch and its row
 * were one action. The switch carries its own label so a screen reader hears
 * "Enabled, Fixed time" rather than an unnamed toggle. An alert reminder
 * (low stock / expiry) has no switch here: the stock screen owns those.
 */
@Composable
internal fun ReminderRow(reminder: Reminder, onEdit: () -> Unit, onEnabledChange: (Boolean) -> Unit) {
    val isAlert = reminder.type == ReminderType.LOW_STOCK_ALERT || reminder.type == ReminderType.EXPIRATION_ALERT
    val typeLabel = stringResource(reminder.type.labelRes())
    val switchLabel = stringResource(R.string.reminder_enabled_switch, typeLabel)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (reminder.enabled) 0.4f else 0.2f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(role = Role.Button, onClick = onEdit),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(typeLabel, style = MaterialTheme.typography.bodyMedium)
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
 * The notes journal (§9 #26; Appendix F MT-04): dated entries, newest first,
 * for what a standing notes field cannot say -- "dizzy for two days after
 * the dose went up". Add at the top; tap an entry to edit; delete from the
 * edit dialog. Kept short here: three most recent, "show all" expands.
 */
@Composable
private fun NotesJournal(medicineId: Long, medicineRepository: MedicineRepository) {
    val notes by produceState<List<MedicineNote>>(initialValue = emptyList(), medicineId) {
        medicineRepository.observeNotes(medicineId).collect { value = it }
    }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MedicineNote?>(null) }
    var showAll by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.medicine_detail_journal), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(R.string.medicine_detail_journal_hint)) },
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = { scope.launch { medicineRepository.addNote(medicineId, draft); draft = "" } },
            ) { Text(stringResource(R.string.medicine_detail_journal_add)) }
        }
        val shown = if (showAll) notes else notes.take(3)
        shown.forEach { note ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
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
                androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, maxLines = 6, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { scope.launch { medicineRepository.editNote(note, text); editing = null } }) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { scope.launch { medicineRepository.deleteNote(note.id); editing = null } }) {
                        Text(stringResource(R.string.medicine_detail_delete), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }
}
