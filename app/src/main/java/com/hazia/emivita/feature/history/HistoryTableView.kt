package com.hazia.emivita.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.format.formatDoseWithUnit
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.theme.NeutralMedicineAccent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R

/** Sortable columns for [HistoryTableView] -- hand-rolled per spec §5's
 *  ADR-0001 shopping list ("hand-rolled `SortableTable`"), §9 #6.
 *  Dense/scannable by design, distinct from the List view's cards:
 *  a table earns its place specifically when someone wants to sort by
 *  medicine or status across the whole window rather than read
 *  chronologically. */
// @StringRes for the same reason as HistoryViewMode: an enum body is not a
// composable scope. `weight` stays a plain Float -- it is layout, not text.
private enum class HistoryTableColumn(@StringRes val label: Int, val weight: Float) {
    DATE(R.string.history_table_date, 1.15f),
    MEDICINE(R.string.history_table_medicine, 1.3f),
    STATUS(R.string.history_table_status, 0.85f),
    DOSE(R.string.history_table_dose, 0.7f),
}

@Composable
fun HistoryTableView(
    events: List<DoseEventWithMedicine>,
    zone: ZoneId,
    onRowClick: (DoseEventWithMedicine) -> Unit,
) {
    var sortColumn by remember { mutableStateOf(HistoryTableColumn.DATE) }
    // Newest-first / highest-first by default (matches the List view's own
    // reverse-chronological default); switching to a different column resets
    // to descending -- one direction convention for the whole table rather
    // than a different "natural" default per column, simpler to reason
    // about while sorting. Clicking the already-active column just flips it.
    var descending by remember { mutableStateOf(true) }

    val sorted = remember(events, sortColumn, descending) {
        val comparator = when (sortColumn) {
            HistoryTableColumn.DATE -> compareBy<DoseEventWithMedicine> { it.doseEvent.scheduledEpochMillis }
            HistoryTableColumn.MEDICINE -> compareBy { it.medicine.name.lowercase() }
            HistoryTableColumn.STATUS -> compareBy { it.doseEvent.status.ordinal }
            HistoryTableColumn.DOSE -> compareBy { it.doseEvent.doseAmount }
        }
        events.sortedWith(if (descending) comparator.reversed() else comparator)
    }

    Column(Modifier.fillMaxWidth()) {
        HistoryTableHeader(sortColumn, descending) { column ->
            if (sortColumn == column) {
                descending = !descending
            } else {
                sortColumn = column
                descending = true
            }
        }
        HorizontalDivider()
        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
            items(sorted, key = { it.doseEvent.id }) { row ->
                HistoryTableRow(row, zone, onClick = {
                    if (row.doseEvent.status != DoseStatus.REFILLED) onRowClick(row) // §7 R46
                })
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
        }
    }
}

@Composable
private fun HistoryTableHeader(sortColumn: HistoryTableColumn, descending: Boolean, onSelect: (HistoryTableColumn) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryTableColumn.entries.forEach { column ->
            Row(
                Modifier.weight(column.weight).clickable { onSelect(column) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(column.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (column == sortColumn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (column == sortColumn) {
                    Icon(
                        if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        contentDescription = if (descending) "Sorted descending" else "Sorted ascending",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp).padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryTableRow(row: DoseEventWithMedicine, zone: ZoneId, onClick: () -> Unit) {
    val medicine = row.medicine
    val accent = medicine.color?.let { parseHexColor(it) } ?: NeutralMedicineAccent
    val editable = row.doseEvent.status != DoseStatus.REFILLED
    val time = remember(row.doseEvent.resolvedAtEpochMillis, row.doseEvent.scheduledEpochMillis) {
        val epochMillis = row.doseEvent.resolvedAtEpochMillis ?: row.doseEvent.scheduledEpochMillis
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    }

    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().let { if (editable) it.clickable(onClick = onClick) else it },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                time,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(HistoryTableColumn.DATE.weight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.weight(HistoryTableColumn.MEDICINE.weight), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(
                    medicine.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                historyStatusLabel(row.doseEvent.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(HistoryTableColumn.STATUS.weight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatDoseWithUnit(row.doseEvent.doseAmount, row.medicine.doseUnit),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(HistoryTableColumn.DOSE.weight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
