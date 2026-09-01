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
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.icons.MedicineIcons
import kotlinx.coroutines.launch

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
    onBack: () -> Unit,
) {
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEditMedicine) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit medicine")
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Stock & Expiration") },
                                onClick = { showOverflowMenu = false; onOpenStockExpiration() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { showOverflowMenu = false; showDeleteConfirm = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddReminder) {
                Icon(Icons.Filled.Add, contentDescription = "Add reminder")
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
                    val swatch = currentMedicine.color?.let { parseHexColor(it) } ?: androidx.compose.ui.graphics.Color(0xFFB8ABA9)
                    Surface(shape = CircleShape, color = swatch, modifier = Modifier.size(48.dp)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(MedicineIcons.iconFor(currentMedicine.iconKey), contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(currentMedicine.name, style = MaterialTheme.typography.titleLarge)
                        currentMedicine.indication?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
                currentMedicine.storageNote?.let {
                    Text(
                        "Storage: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                "Reminders",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (reminders.isEmpty()) {
                Text(
                    "No reminders yet. Tap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(reminders) { reminder ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clickable { onEditReminder(reminder.id) },
                        ) {
                            Text(
                                reminder.type.summaryLabel(),
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val currentMedicine = medicine
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${currentMedicine?.name ?: "this medicine"}?") },
            text = { Text("This removes every reminder and all dose history for it. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val toDelete = currentMedicine ?: return@TextButton
                    scope.launch {
                        medicineRepository.delete(toDelete)
                        onDeleted()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private fun ReminderType.summaryLabel(): String = when (this) {
    ReminderType.FIXED_TIME -> "Fixed Time"
    ReminderType.REPEATING_INTERVAL -> "Repeating Interval"
    ReminderType.DAILY_WINDOW -> "Daily Window"
    ReminderType.LOW_STOCK_ALERT -> "Low Stock Alert"
    ReminderType.EXPIRATION_ALERT -> "Expiration Alert"
    ReminderType.LINKED_FOLLOWING -> "Linked Following"
}
