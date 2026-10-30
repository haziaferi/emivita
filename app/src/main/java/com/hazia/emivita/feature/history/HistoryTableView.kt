package com.hazia.emivita.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.dao.displayName
import com.hazia.emivita.core.data.dao.doseUnit
import com.hazia.emivita.core.data.entity.isAlertSeen
import com.hazia.emivita.core.data.entity.isRecordOnly
import com.hazia.emivita.core.format.formatDoseWithUnit
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.theme.NeutralMedicineAccent
import java.time.ZoneId
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import com.hazia.emivita.core.format.doseStatusLabelRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalResources
import com.hazia.emivita.core.format.LocaleFormats

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

/** The text one record's cells show. */
private class Cells(val date: String, val medicine: String, val status: String, val dose: String)

/** Room each column needs besides its text: the medicine's dot, the sort arrow, and the space between columns. */
private val DotRoom = 12.dp
private val ArrowRoom = 16.dp
private val ColumnGap = 8.dp

/**
 * Whether the four columns show whole side by side in [room]: each column's natural width (its widest cell or its
 * header, on one line) must fit the share its weight gives it. When they do not, at a large font or with a long name,
 * the table draws each record on two lines (§7 R132, the owner: "C: two-line rows").
 */
private fun columnsFit(natural: List<Dp>, room: Dp): Boolean {
    val total = HistoryTableColumn.entries.sumOf { it.weight.toDouble() }.toFloat()
    return HistoryTableColumn.entries.all { c -> natural[c.ordinal] + ColumnGap <= room * (c.weight / total) }
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
            // By the value the cell shows (§7 R60 D F4): a dose taken late
            // sorted by its scheduled slot appeared out of order beside its
            // displayed time.
            HistoryTableColumn.DATE -> compareBy<DoseEventWithMedicine> { it.doseEvent.resolvedAtEpochMillis ?: it.doseEvent.scheduledEpochMillis }
            HistoryTableColumn.MEDICINE -> compareBy { it.displayName.lowercase() }
            HistoryTableColumn.STATUS -> compareBy { it.doseEvent.status.ordinal }
            HistoryTableColumn.DOSE -> compareBy { it.doseEvent.doseAmount }
        }
        events.sortedWith(if (descending) comparator.reversed() else comparator)
    }
    val res = LocalResources.current
    val cells = remember(events, zone, res) {
        events.associate { row ->
            val e = row.doseEvent
            e.id to Cells(
                date = LocaleFormats.monthDayTime(e.resolvedAtEpochMillis ?: e.scheduledEpochMillis, zone),
                medicine = row.displayName,
                status = res.getString(doseStatusLabelRes(e.status)),
                // A seen alert (§7 R105) has no amount.
                dose = if (e.status.isAlertSeen) "" else formatDoseWithUnit(e.doseAmount, row.doseUnit, res),
            )
        }
    }

    val measurer = rememberTextMeasurer()
    val cellStyle = MaterialTheme.typography.bodySmall
    val headStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val natural: List<Dp> = remember(cells, density, cellStyle, headStyle) {
        fun w(text: String, style: TextStyle) = with(density) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width.toDp() }
        HistoryTableColumn.entries.map { col ->
            val widest = cells.values.maxOfOrNull { c ->
                when (col) {
                    HistoryTableColumn.DATE -> w(c.date, cellStyle)
                    HistoryTableColumn.MEDICINE -> w(c.medicine, cellStyle) + DotRoom
                    HistoryTableColumn.STATUS -> w(c.status, cellStyle)
                    HistoryTableColumn.DOSE -> w(c.dose, cellStyle)
                }
            } ?: 0.dp
            maxOf(w(res.getString(col.label), headStyle) + ArrowRoom, widest)
        }
    }

    val onSelect: (HistoryTableColumn) -> Unit = { column ->
        if (sortColumn == column) descending = !descending else { sortColumn = column; descending = true }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = !columnsFit(natural, maxWidth - 32.dp)
        Column(Modifier.fillMaxWidth()) {
            if (stacked) StackedHeader(sortColumn, descending, onSelect) else HistoryTableHeader(sortColumn, descending, onSelect)
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                items(sorted, key = { it.doseEvent.id }) { row ->
                    val onClick = { if (!row.doseEvent.status.isRecordOnly) onRowClick(row) } // §7 R46
                    if (stacked) StackedRow(row, cells.getValue(row.doseEvent.id), onClick)
                    else HistoryTableRow(row, cells.getValue(row.doseEvent.id), onClick)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
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
            val sortLabel = stringResource(R.string.a11y_sort_by, stringResource(column.label))
            Row(
                Modifier.weight(column.weight).clickable(onClickLabel = sortLabel, role = Role.Button) { onSelect(column) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortLabel(column, sortColumn, descending)
            }
        }
    }
}

/** The four sort labels on one line that wraps, above the two-line records. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StackedHeader(sortColumn: HistoryTableColumn, descending: Boolean, onSelect: (HistoryTableColumn) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HistoryTableColumn.entries.forEach { column ->
            val sortLabel = stringResource(R.string.a11y_sort_by, stringResource(column.label))
            Row(
                Modifier.heightIn(min = 38.dp).clickable(onClickLabel = sortLabel, role = Role.Button) { onSelect(column) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortLabel(column, sortColumn, descending)
            }
        }
    }
}

@Composable
private fun SortLabel(column: HistoryTableColumn, sortColumn: HistoryTableColumn, descending: Boolean) {
    Text(
        stringResource(column.label),
        style = MaterialTheme.typography.labelLarge,
        color = if (column == sortColumn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
    )
    if (column == sortColumn) {
        Icon(
            if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
            contentDescription = stringResource(if (descending) R.string.history_sorted_descending else R.string.history_sorted_ascending),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp).padding(start = 2.dp),
        )
    }
}

/** A record opens its edit on a tap, unless it is only a record (§7 R46); [label] is what TalkBack says the tap does. */
private fun Modifier.record(row: DoseEventWithMedicine, label: String, onClick: () -> Unit): Modifier =
    if (row.doseEvent.status.isRecordOnly) fillMaxWidth()
    else fillMaxWidth().clickable(onClickLabel = label, role = Role.Button, onClick = onClick)

/** One record in the four columns; [columnsFit] has checked that every cell shows whole. */
@Composable
private fun HistoryTableRow(row: DoseEventWithMedicine, c: Cells, onClick: () -> Unit) {
    val accent = row.medicine?.color?.let { parseHexColor(it) } ?: NeutralMedicineAccent
    Surface(color = Color.Transparent, modifier = Modifier.record(row, stringResource(R.string.a11y_edit_dose), onClick)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(c.date, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(HistoryTableColumn.DATE.weight), maxLines = 1)
            Row(Modifier.weight(HistoryTableColumn.MEDICINE.weight), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(c.medicine, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Text(
                c.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(HistoryTableColumn.STATUS.weight), maxLines = 1,
            )
            Text(c.dose, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(HistoryTableColumn.DOSE.weight), maxLines = 1)
        }
    }
}

/** One record on two lines: the medicine and its status, then the date and the dose. */
@Composable
private fun StackedRow(row: DoseEventWithMedicine, c: Cells, onClick: () -> Unit) {
    val accent = row.medicine?.color?.let { parseHexColor(it) } ?: NeutralMedicineAccent
    Surface(color = Color.Transparent, modifier = Modifier.record(row, stringResource(R.string.a11y_edit_dose), onClick)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(c.medicine, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(ColumnGap))
                Text(c.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(14.dp))
                Text(c.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(ColumnGap))
                Text(c.dose, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
