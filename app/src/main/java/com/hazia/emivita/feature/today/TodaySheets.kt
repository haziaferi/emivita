package com.hazia.emivita.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.format.formatDoseWithUnit
import com.hazia.emivita.ui.components.MedicineTile
import com.hazia.emivita.ui.components.TonalActionChip
import com.hazia.emivita.ui.theme.doseCritical

/**
 * "Take all" on a time group asks here first (§7 R107, census D-20), listing
 * exactly the doses the confirm records and naming those left out (§7 R110).
 * The caller records them one at a time through the row's own path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TakeGroupSheet(time: String, entries: List<TimelineEntry>, onConfirm: () -> Unit, onDismiss: () -> Unit, leftOut: List<TimelineEntry> = emptyList()) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.today_take_all_title), style = MaterialTheme.typography.titleLarge)
            Text(time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            entries.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MedicineTile(e.medicine, size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.medicine.name, style = MaterialTheme.typography.titleSmall)
                        Text(formatDoseWithUnit(e.doseAmount, e.medicine.doseUnit, LocalResources.current), style = MaterialTheme.typography.doseCritical, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (leftOut.isNotEmpty()) {
                // Which doses stay due and why, in one line (§7 R110).
                Spacer(Modifier.height(8.dp))
                Text(
                    pluralStringResource(R.plurals.today_take_all_left_out, leftOut.size, leftOut.joinToString(", ") { it.medicine.name }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(pluralStringResource(R.plurals.today_take_all_confirm, entries.size, entries.size))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

/**
 * A tap on a dose still to come opens its details (§7 R107, census D-55):
 * when, how much, the reminder's instructions and the medicine's notes, with
 * the row's own Take now and, when the lock allows it, Skip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DoseDetailSheet(entry: TimelineEntry, time: String, canSkip: Boolean, onResolve: (DoseStatus) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MedicineTile(entry.medicine, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.medicine.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.today_dose_detail_when, time, formatDoseWithUnit(entry.doseAmount, entry.medicine.doseUnit, LocalResources.current)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            entry.instructions?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.today_dose_detail_instructions), style = MaterialTheme.typography.labelLarge)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            listOfNotNull(entry.medicine.indication, entry.medicine.notes).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { lines ->
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.today_dose_detail_notes), style = MaterialTheme.typography.labelLarge)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TonalActionChip(onClick = { onResolve(DoseStatus.TAKEN) }, label = stringResource(R.string.today_take_now), icon = Icons.Filled.Check)
                if (canSkip) {
                    Spacer(Modifier.width(6.dp))
                    AssistChip(
                        onClick = { onResolve(DoseStatus.SKIPPED) },
                        label = { Text(stringResource(R.string.common_skip)) },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                    )
                }
            }
        }
    }
}
