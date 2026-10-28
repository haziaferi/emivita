package com.hazia.emivita.feature.medicines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.ui.form.DialogActionPair
import com.hazia.emivita.ui.form.TertiaryControlHeight
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Medicines list's actions on every medicine at once and on one card (§7 R127): pause all
 * (C-29 V2) and swipe to delete (D-03 V0).
 */

private fun Reminder.isDosing() = type != ReminderType.LOW_STOCK_ALERT && type != ReminderType.EXPIRATION_ALERT

private fun dosing(medicines: List<MedicineWithReminders>) = medicines.flatMap { m -> m.reminders.filter { it.isDosing() } }

/**
 * Pause-all (§7 R127, C-29 V2): every dosing reminder off, the ids it switched off recorded so
 * Riattiva can switch back on only those. Resume-all switches every one back on and drops the record.
 */
internal suspend fun pauseAll(medicines: List<MedicineWithReminders>, reminders: ReminderRepository, settings: SettingsRepository) {
    settings.setPausedByPauseAll(dosing(medicines).filter { it.enabled }.map { it.id }.toSet())
    medicines.forEach { reminders.setAllDosingEnabled(it.medicine.id, false) }
}

internal suspend fun resumeAll(medicines: List<MedicineWithReminders>, reminders: ReminderRepository, settings: SettingsRepository) {
    settings.setPausedByPauseAll(emptySet())
    medicines.forEach { reminders.setAllDosingEnabled(it.medicine.id, true) }
}

/**
 * Riattiva (§7 R127, the owner's fix): back on only what pause-all switched off, so a reminder
 * paused on purpose before it stays paused. With no record (every reminder paused one by one),
 * every reminder, as resume-all does.
 */
internal suspend fun reactivate(medicines: List<MedicineWithReminders>, reminders: ReminderRepository, settings: SettingsRepository) {
    val paused = settings.pausedByPauseAll.first()
    if (paused.isEmpty()) {
        medicines.forEach { reminders.setAllDosingEnabled(it.medicine.id, true) }
    } else {
        for (id in paused) reminders.getById(id)?.let { if (!it.enabled) reminders.setEnabled(it, true) }
    }
    settings.setPausedByPauseAll(emptySet())
}

/** The Medicines header's overflow: pause every reminder, or resume them while none is on. */
@Composable
internal fun PauseAllMenu(medicines: List<MedicineWithReminders>, reminders: ReminderRepository, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val all = dosing(medicines)
    val anyOn = all.any { it.enabled }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.medicine_detail_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (all.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (anyOn) R.string.medicine_detail_pause_all else R.string.medicine_detail_resume_all)) },
                    onClick = {
                        open = false
                        scope.launch { if (anyOn) pauseAll(medicines, reminders, settings) else resumeAll(medicines, reminders, settings) }
                    },
                )
            }
        }
    }
}

/** While every dosing reminder is paused: "Tutti i promemoria sono in pausa · Riattiva". */
@Composable
internal fun AllPausedLine(medicines: List<MedicineWithReminders>, reminders: ReminderRepository, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val all = dosing(medicines)
    if (all.isEmpty() || all.any { it.enabled }) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.medicines_all_paused), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        // A text action on the line, the tertiary tier (§7 R124 C2), as a form's delete is.
        TextButton(onClick = { scope.launch { reactivate(medicines, reminders, settings) } }, modifier = Modifier.heightIn(min = TertiaryControlHeight)) {
            Text(stringResource(R.string.medicines_resume), maxLines = 1)
        }
    }
}

/**
 * Swipe a card from end to start over the error colour and its bin (§7 R127, D-03 V0; medTimer's
 * list) to the delete question: Elimina archives, restorable from Settings, and Annulla snaps the
 * card back. TalkBack reaches the same question through the card's "Elimina" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToDelete(medicine: Medicine, onDelete: suspend (Medicine) -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState()
    var asking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.currentValue) { if (state.currentValue == SwipeToDismissBoxValue.EndToStart) asking = true }
    val deleteLabel = stringResource(R.string.medicine_detail_delete)
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { asking = true; true }) },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError) }
        },
    ) { content() }
    if (asking) {
        val cancel = { asking = false; scope.launch { state.reset() }; Unit }
        AlertDialog(
            onDismissRequest = cancel,
            title = { Text(stringResource(R.string.medicine_detail_delete_title, medicine.name)) },
            text = { Text(stringResource(R.string.medicine_detail_this_removes_every_reminder_and_all_dose)) },
            confirmButton = {
                DialogActionPair(stringResource(R.string.common_confirm), destructive = true, onDismiss = cancel, onConfirm = {
                    asking = false
                    // Whole once begun: the card leaves the list as the archive lands.
                    scope.launch { withContext(NonCancellable) { onDelete(medicine) } }
                })
            },
        )
    }
}
