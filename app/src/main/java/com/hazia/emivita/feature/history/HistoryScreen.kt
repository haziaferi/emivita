package com.hazia.emivita.feature.history

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.export.HistoryExporter
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.ui.components.DoseEditDialog
import com.hazia.emivita.ui.components.rememberCurrentDate
import com.hazia.emivita.core.format.formatDoseWithUnit
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.icons.MedicineIcons
import com.hazia.emivita.ui.theme.NeutralMedicineAccent
import com.hazia.emivita.ui.theme.doseCritical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val HISTORY_LOOKBACK_DAYS = 30L

private data class HistoryDay(
    val date: LocalDate,
    val entries: List<DoseEventWithMedicine>,
)

/** The three readings of the same history. Only LIST carries the summary
 *  card and trend chart; CALENDAR and TABLE are denser alternate views, not
 *  copies of the same content. */
private enum class HistoryViewMode(val label: String) {
    LIST("List"),
    CALENDAR("Calendar"),
    TABLE("Table"),
}

/**
 * History: past entries from a direct DB query, not the projection cache --
 * spec's own split (§9 #6, "medTimer's own split, per ADR-0002"), the cache
 * only ever looks forward. Reverse-chronological, grouped by day, reusing
 * [DoseEditDialog] (spec §3/§9 #3 -- "swipe-to-edit on a Today/History
 * entry" names both screens explicitly).
 *
 * Refills show up as ordinary rows distinguished by status, per spec §5:
 * "exactly like medTimer folds refills into the same event stream rather
 * than a separate log" -- Today deliberately filters these out (a restock
 * isn't a dose to take), History does not.
 *
 * Three views (§5's ADR-0001 shopping list, §9 #6): the List view here
 * (adherence-summary card + Vico trend chart [AdherenceChart] +
 * reverse-chronological day groups), a Calendar view ([HistoryCalendarView],
 * month grid with per-day taken/skipped/missed dots, which browses its own
 * DB query per visible month rather than [HISTORY_LOOKBACK_DAYS]'s fixed
 * window -- see that file's doc comment), and a Table view
 * ([HistoryTableView], hand-rolled, sortable by tapping a column header).
 */
@Composable
fun HistoryScreen(
    profileId: Long,
    doseEventRepository: DoseEventRepository,
) {
    val zone = remember { ZoneId.systemDefault() }
    // Keyed on the real current date, not on first composition -- otherwise
    // this rolling window silently stops rolling once the screen has been
    // open across midnight. See rememberCurrentDate.
    val today = rememberCurrentDate(zone)
    val rangeEnd = remember(today, zone) { today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val rangeStart = remember(today, zone) { today.minusDays(HISTORY_LOOKBACK_DAYS).atStartOfDay(zone).toInstant().toEpochMilli() }

    val events = produceState<List<DoseEventWithMedicine>?>(initialValue = null, profileId, rangeStart, rangeEnd) {
        doseEventRepository.observeInRange(profileId, rangeStart, rangeEnd).collectLatest { value = it }
    }.value

    val days = remember(events) { groupByDay(events, zone) }
    var editing by remember { mutableStateOf<DoseEventWithMedicine?>(null) }
    var viewMode by remember { mutableStateOf(HistoryViewMode.LIST) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExportMenu by remember { mutableStateOf(false) }
    val exportRows = events ?: emptyList()

    // Both exports run on Dispatchers.IO: formatting the rows and writing
    // through the SAF stream are real work over the whole visible window, and
    // rememberCoroutineScope() dispatches on Main, so without this they block
    // the frame that's drawing the file picker's dismissal.
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val csv = HistoryExporter.buildCsv(exportRows, zone)
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    val exportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val document = HistoryExporter.buildPdf(exportRows, adherenceSubtitle(exportRows), zone)
                context.contentResolver.openOutputStream(uri)?.use { HistoryExporter.writePdf(document, it) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("History", style = MaterialTheme.typography.headlineSmall)
            if (exportRows.isNotEmpty()) {
                Box {
                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Export")
                    }
                    DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Export as CSV") },
                            onClick = {
                                showExportMenu = false
                                exportCsvLauncher.launch(defaultExportFileName("csv"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export as PDF") },
                            onClick = {
                                showExportMenu = false
                                exportPdfLauncher.launch(defaultExportFileName("pdf"))
                            },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryViewMode.entries.forEach { mode ->
                FilterChip(
                    selected = viewMode == mode,
                    onClick = { viewMode = mode },
                    label = { Text(mode.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        when (viewMode) {
            HistoryViewMode.LIST -> when {
                events == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                days.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing recorded yet. Taken, skipped, and refilled doses will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    AdherenceSummary(events)
                    val dailyCounts = remember(events, zone, today) { dailyAdherenceCounts(events, zone, today) }
                    if (dailyCounts.any { it.total > 0 }) {
                        AdherenceChart(dailyCounts)
                        Spacer(Modifier.height(12.dp))
                    }
                    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        items(days, key = { it.date.toEpochDay() }) { day ->
                            DayHeader(day.date, today)
                            day.entries.forEach { row ->
                                // The row itself already refuses the click for a
                                // REFILLED entry; re-testing it here is what let
                                // this surface and the row disagree (§7 R47).
                                HistoryEntryRow(row, onClick = { editing = row })
                                Spacer(Modifier.height(8.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            // Browses its own per-visible-month query rather than exportRows'
            // fixed HISTORY_LOOKBACK_DAYS window (see HistoryCalendarView.kt's
            // doc comment) -- shown regardless of whether the last 30 days
            // have any data, so a past month can still be reached.
            HistoryViewMode.CALENDAR -> HistoryCalendarView(
                profileId = profileId,
                zone = zone,
                doseEventRepository = doseEventRepository,
                onEntryClick = { editing = it },
            )
            HistoryViewMode.TABLE -> when {
                events == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                exportRows.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing recorded yet. Taken, skipped, and refilled doses will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> HistoryTableView(
                    events = exportRows,
                    zone = zone,
                    onRowClick = { editing = it },
                )
            }
        }
    }

    editing?.let { row ->
        DoseEditDialog(
            medicineName = row.medicine.name,
            doseEventId = row.doseEvent.id,
            originalStatus = row.doseEvent.status,
            originalDoseAmount = row.doseEvent.doseAmount,
            originalResolvedAtEpochMillis = row.doseEvent.resolvedAtEpochMillis,
            scheduledEpochMillis = row.doseEvent.scheduledEpochMillis,
            doseEventRepository = doseEventRepository,
            onDismiss = { editing = null },
            doseUnit = row.medicine.doseUnit,
        )
    }
}

private fun defaultExportFileName(extension: String): String {
    val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    return "emivita_history_$stamp.$extension"
}

/** Same calculation [AdherenceSummary] renders, as a plain string for the PDF
 *  report's subtitle line -- kept in sync by both reading from the same
 *  [HISTORY_LOOKBACK_DAYS] window and the same [adherenceCounts], not by
 *  sharing layout. */
private fun adherenceSubtitle(events: List<DoseEventWithMedicine>): String {
    val counts = adherenceCounts(events)
    if (counts.answered == 0 && counts.missed == 0) return "Last $HISTORY_LOOKBACK_DAYS days"
    val lead = if (counts.answered == 0) "No doses answered" else "${counts.percent}% of answered doses taken"
    return "$lead -- ${counts.taken} taken, ${counts.skipped} skipped, " +
        "${counts.missed} missed, last $HISTORY_LOOKBACK_DAYS days"
}

/** Taken / (Taken + Skipped) over the visible window, with the missed count
 *  carried alongside rather than dropped.
 *
 *  The percentage's denominator is unchanged -- it is the "did you take what
 *  you meant to take" reading, where a deliberate Skip counts against you and
 *  a dose that fired and was never answered is a different kind of event. What
 *  changed is that the missed count no longer disappears on the way to the
 *  screen. Reporting the ratio alone put two contradictory claims about the
 *  same rows a few dp apart: [AdherenceChart] renders missed as its own series
 *  and its doc comment ranks it explicitly as "worse than a deliberate Skip"
 *  -- which is why it gets the reserved `error` colour -- while the headline
 *  above it counted the Skip and ignored the miss. Ten taken and twenty missed
 *  read as "100% adherence" over a chart that was two-thirds red.
 *
 *  Naming what the percentage is *of* is the other half: "100% adherence" is a
 *  claim about a month, "100% of answered doses taken" is a claim about a
 *  denominator the same card then prints. Whether missed doses belong *in* that
 *  denominator is a product decision, deliberately not made here. */
private data class AdherenceCounts(val taken: Int, val skipped: Int, val missed: Int) {
    val answered: Int get() = taken + skipped
    val percent: Int get() = if (answered == 0) 0 else (taken * 100) / answered
}

/** PENDING means "fired, never resolved" -- the same reading
 *  [historyStatusLabel] gives it as "Missed" and [dailyAdherenceCounts] uses
 *  for the chart's third series. REFILLED is a restock, not a dose, and is
 *  excluded from all three counts. */
private fun adherenceCounts(events: List<DoseEventWithMedicine>) = AdherenceCounts(
    taken = events.count { it.doseEvent.status == DoseStatus.TAKEN },
    skipped = events.count { it.doseEvent.status == DoseStatus.SKIPPED },
    missed = events.count { it.doseEvent.status == DoseStatus.PENDING },
)

/** [DayAdherenceCounts] for every day in the visible [HISTORY_LOOKBACK_DAYS]
 *  window, oldest first -- including days with zero entries, so the chart's
 *  x-axis reads as a continuous 30-day span rather than only the days that
 *  happened to have a dose. Refills are excluded (see [DayAdherenceCounts]'s
 *  own doc comment); PENDING here means "fired, never resolved," same
 *  reading [historyStatusLabel] already gives it as "Missed." */
private fun dailyAdherenceCounts(events: List<DoseEventWithMedicine>, zone: ZoneId, today: LocalDate): List<DayAdherenceCounts> {
    val windowStart = today.minusDays(HISTORY_LOOKBACK_DAYS - 1)
    val byDay = events
        .filter { it.doseEvent.status != DoseStatus.REFILLED }
        .groupBy { Instant.ofEpochMilli(it.doseEvent.scheduledEpochMillis).atZone(zone).toLocalDate() }
    return (0 until HISTORY_LOOKBACK_DAYS).map { offset ->
        val date = windowStart.plusDays(offset)
        val rows = byDay[date].orEmpty()
        DayAdherenceCounts(
            date = date,
            taken = rows.count { it.doseEvent.status == DoseStatus.TAKEN },
            skipped = rows.count { it.doseEvent.status == DoseStatus.SKIPPED },
            missed = rows.count { it.doseEvent.status == DoseStatus.PENDING },
        )
    }
}

private fun groupByDay(events: List<DoseEventWithMedicine>?, zone: ZoneId): List<HistoryDay> {
    if (events.isNullOrEmpty()) return emptyList()
    return events
        .groupBy { Instant.ofEpochMilli(it.doseEvent.scheduledEpochMillis).atZone(zone).toLocalDate() }
        .map { (date, rows) -> HistoryDay(date, rows.sortedByDescending { it.doseEvent.scheduledEpochMillis }) }
        .sortedByDescending { it.date }
}

/** See [adherenceCounts] for what this shows and what it deliberately does
 *  not decide. */
@Composable
private fun AdherenceSummary(events: List<DoseEventWithMedicine>) {
    val counts = adherenceCounts(events)
    // Was `if (taken + skipped == 0) return`, which hid the whole card for a
    // window containing nothing but missed doses -- the one window whose
    // summary matters most.
    if (counts.answered == 0 && counts.missed == 0) return

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (counts.answered == 0) "No doses answered" else "${counts.percent}% of answered doses taken",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${counts.taken} taken, ${counts.skipped} skipped, ${counts.missed} missed, " +
                    "last $HISTORY_LOOKBACK_DAYS days",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    val label = when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

/** `internal`, not `private` -- reused by [HistoryTableView] and
 *  [HistoryCalendarView]'s day-detail list (both file-scoped-private in
 *  Kotlin would otherwise block cross-file reuse within this package). */
@Composable
internal fun HistoryEntryRow(row: DoseEventWithMedicine, onClick: () -> Unit) {
    val medicine = row.medicine
    val status = row.doseEvent.status
    val accent = medicine.color?.let { parseHexColor(it) } ?: NeutralMedicineAccent
    // Not an allow-list: a PENDING ("Missed") dose is the case §7 R46 exists to
    // make correctable, and gating on the two resolved statuses is what kept it
    // uncorrectable on this row after R46 shipped (§7 R47). A REFILLED row is
    // a restock with nothing to resolve, so it stays inert -- matching
    // HistoryTableView and HistoryCalendarView, which this row is shared with.
    val editable = status != DoseStatus.REFILLED
    val time = remember(row.doseEvent.resolvedAtEpochMillis, row.doseEvent.scheduledEpochMillis) {
        val epochMillis = row.doseEvent.resolvedAtEpochMillis ?: row.doseEvent.scheduledEpochMillis
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.14f),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (editable) it.clickable(onClick = onClick) else it },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(40.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(MedicineIcons.iconFor(medicine.iconKey), contentDescription = null, tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(medicine.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${historyStatusLabel(status)} - $time - ${formatDoseWithUnit(row.doseEvent.doseAmount, medicine.doseUnit)}",
                    // A time and a dose amount together -- squarely what
                    // Typography.doseCritical names a floor for (§7 R47).
                    style = MaterialTheme.typography.doseCritical,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// PENDING here means the alarm fired (or was projected in the past) and
// nothing ever resolved it -- "Missed" is a display-only relabeling for a
// past-dated PENDING row, not a fifth DoseStatus; the underlying column
// stays PENDING so a late resolve() from elsewhere still works normally.
internal fun historyStatusLabel(status: DoseStatus): String = when (status) {
    DoseStatus.PENDING -> "Missed"
    DoseStatus.TAKEN -> "Taken"
    DoseStatus.SKIPPED -> "Skipped"
    DoseStatus.REFILLED -> "Refilled"
}
