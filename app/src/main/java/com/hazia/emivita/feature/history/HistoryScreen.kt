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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import com.hazia.emivita.ui.components.TabHeader
import androidx.annotation.StringRes
import android.content.Context
import com.hazia.emivita.core.format.doseStatusLabelRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalResources
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.data.settings.SettingsRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.core.notification.ReminderNotificationBuilder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.hazia.emivita.core.data.entity.Medicine

private const val HISTORY_LOOKBACK_DAYS = 30L

private data class HistoryDay(
    val date: LocalDate,
    val entries: List<DoseEventWithMedicine>,
)

/** The three readings of the same history. Only LIST carries the summary
 *  card and trend chart; CALENDAR and TABLE are denser alternate views, not
 *  copies of the same content. */
// @StringRes rather than a String: this enum is a top-level declaration,
// not a composable, so stringResource() cannot be called here. The id
// travels and the call site resolves it (spec §9 #19 Stage A).
private enum class HistoryViewMode(@StringRes val label: Int) {
    LIST(R.string.history_view_list),
    CALENDAR(R.string.history_view_calendar),
    TABLE(R.string.history_view_table),
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
    settingsRepository: SettingsRepository,
    reminderRepository: ReminderRepository,
    notificationBuilder: ReminderNotificationBuilder,
) {
    val zone = remember { ZoneId.systemDefault() }
    val resources = LocalResources.current
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
    // Remembered across navigation and process death (§7 R60 D F5) -- a
    // person who reads History as a table should not be handed the list
    // every time. Stored by name so a removed case degrades to LIST.
    val savedViewModeName by settingsRepository.historyViewModeName.collectAsState(initial = null)
    var viewMode by remember { mutableStateOf(HistoryViewMode.LIST) }
    LaunchedEffect(savedViewModeName) {
        savedViewModeName?.let { name -> HistoryViewMode.entries.firstOrNull { it.name == name }?.let { viewMode = it } }
    }
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    var showExportMenu by remember { mutableStateOf(false) }
    // Export scope (§9 #26; MT-05/HM-14): a range preset and an optional
    // medicine, chosen in a dialog before the file picker. The rows are
    // fetched once for the chosen scope, not taken from the visible window.
    var exportDialogFormat by remember { mutableStateOf<String?>(null) }
    var exportRange by remember { mutableStateOf(ExportRange.LAST_30_DAYS) }
    var exportMedicineId by remember { mutableStateOf<Long?>(null) }
    val exportRows = events ?: emptyList()
    val medicinesForFilter = remember(events) { events.orEmpty().map { it.medicine }.distinctBy { it.id }.sortedBy { it.name } }
    suspend fun rowsForExport(): List<DoseEventWithMedicine> {
        val start = when (exportRange) {
            ExportRange.LAST_30_DAYS -> today.minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
            ExportRange.LAST_90_DAYS -> today.minusDays(90).atStartOfDay(zone).toInstant().toEpochMilli()
            ExportRange.THIS_YEAR -> today.withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
            ExportRange.ALL -> 0L
        }
        return doseEventRepository.getInRangeOnce(profileId, start, rangeEnd, exportMedicineId)
    }

    // Both exports run on Dispatchers.IO: formatting the rows and writing
    // through the SAF stream are real work over the whole visible window, and
    // rememberCoroutineScope() dispatches on Main, so without this they block
    // the frame that's drawing the file picker's dismissal.
    // Not `?.use { }` (§7 R60 E F3): a null stream made the write a silent
    // no-op with no message either way -- the shape §7 R23 fixed for backup
    // export. Both paths now fail loudly and report either outcome.
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportStatus = try {
                withContext(Dispatchers.IO) {
                    val csv = HistoryExporter.buildCsv(rowsForExport(), zone)
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error(resources.getString(R.string.history_export_no_stream))
                    out.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                }
                resources.getString(R.string.history_export_done)
            } catch (e: Exception) {
                resources.getString(R.string.history_export_failed, e.message ?: resources.getString(R.string.error_unknown))
            }
        }
    }
    val exportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportStatus = try {
                withContext(Dispatchers.IO) {
                    val rows = rowsForExport()
                    val document = HistoryExporter.buildPdf(context, rows, adherenceSubtitle(context, rows), zone)
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error(resources.getString(R.string.history_export_no_stream))
                    out.use { HistoryExporter.writePdf(document, it) }
                }
                resources.getString(R.string.history_export_done)
            } catch (e: Exception) {
                resources.getString(R.string.history_export_failed, e.message ?: resources.getString(R.string.error_unknown))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // The shared header (§9 #33, F-14). The export button used to sit
        // inside this row, and its 48 dp target made History's header 13 dp
        // taller than Today's whenever there was anything to export -- the
        // §7 R59 shape on a third tab, measured by TabHeaderLayoutTest.
        TabHeader(stringResource(R.string.history_history), actions = if (exportRows.isEmpty()) null else {
            {
                Box {
                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.history_export))
                    }
                    DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_export_as_csv)) },
                            onClick = {
                                showExportMenu = false
                                exportDialogFormat = "csv"
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_export_as_pdf)) },
                            onClick = {
                                showExportMenu = false
                                exportDialogFormat = "pdf"
                            },
                        )
                    }
                }
            }
        })

        exportDialogFormat?.let { format ->
            ExportScopeDialog(
                range = exportRange,
                onRange = { exportRange = it },
                medicines = medicinesForFilter,
                medicineId = exportMedicineId,
                onMedicine = { exportMedicineId = it },
                onDismiss = { exportDialogFormat = null },
                onConfirm = {
                    exportDialogFormat = null
                    if (format == "csv") exportCsvLauncher.launch(defaultExportFileName("csv")) else exportPdfLauncher.launch(defaultExportFileName("pdf"))
                },
            )
        }

        exportStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryViewMode.entries.forEach { mode ->
                FilterChip(
                    selected = viewMode == mode,
                    onClick = { viewMode = mode; scope.launch { settingsRepository.setHistoryViewModeName(mode.name) } },
                    label = { Text(stringResource(mode.label)) },
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
                        stringResource(R.string.history_nothing_recorded_yet_taken_skipped_and),
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
                        stringResource(R.string.history_nothing_recorded_yet_taken_skipped_and),
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
            originalNotes = row.doseEvent.notes,
            onReopened = { reopened ->
                val reminder = reopened.reminderId?.let { reminderRepository.getById(it) }
                if (reminder != null) notificationBuilder.buildAndPost(reopened, reminder, row.medicine)
            },
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
// Takes a Context: this builds the PDF subtitle from a coroutine, not a
// composable, so stringResource() is out of reach (§9 #19).
private fun adherenceSubtitle(context: Context, events: List<DoseEventWithMedicine>): String {
    val counts = adherenceCounts(events)
    val days = HISTORY_LOOKBACK_DAYS.toInt()
    if (counts.answered == 0 && counts.missed == 0) {
        return context.resources.getQuantityString(R.plurals.history_last_days, days, days)
    }
    val lead = if (counts.answered == 0) context.getString(R.string.history_no_doses_answered)
    else context.getString(R.string.history_percent_taken, counts.percent)
    val tail = context.resources.getQuantityString(
        R.plurals.history_counts_summary, days, counts.taken, counts.skipped, counts.missed, days,
    )
    return context.getString(R.string.history_subtitle_join, lead, tail)
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
                if (counts.answered == 0) stringResource(R.string.history_no_doses_answered)
                else stringResource(R.string.history_percent_taken, counts.percent),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                pluralStringResource(
                    R.plurals.history_counts_summary,
                    // Long constant, Int quantity: the plural selector and the
                    // format arg both want an Int.
                    HISTORY_LOOKBACK_DAYS.toInt(),
                    counts.taken,
                    counts.skipped,
                    counts.missed,
                    HISTORY_LOOKBACK_DAYS.toInt(),
                ),
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
        // Assigned to a val before reaching Text(), which is exactly the
        // shape a sink-based scan cannot see -- found by reading, not by the
        // gate (§9 #19 Stage A, and the gate's own KDoc says so).
        date == today -> stringResource(R.string.history_today)
        date == today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> LocaleFormats.weekdayMonthDay(date)
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
        LocaleFormats.time(epochMillis)
    }

    // One merged node per entry, state as stateDescription (§9 #19 Stage B);
    // see TimelineEntryRow in TodayScreen for the reasoning.
    val statusLabel = stringResource(doseStatusLabelRes(status))
    val editLabel = stringResource(R.string.a11y_edit_dose)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.14f),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (editable) it.clickable(onClickLabel = editLabel, role = Role.Button, onClick = onClick) else it }
            .semantics(mergeDescendants = true) { stateDescription = statusLabel },
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
                    stringResource(
                        R.string.history_entry_summary,
                        stringResource(doseStatusLabelRes(status)),
                        time,
                        formatDoseWithUnit(row.doseEvent.doseAmount, medicine.doseUnit, LocalResources.current),
                    ),
                    // A time and a dose amount together -- squarely what
                    // Typography.doseCritical names a floor for (§7 R47).
                    style = MaterialTheme.typography.doseCritical,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// The status label moved to core/format/DoseFormatting.kt as
// doseStatusLabelRes() (§9 #19): one @StringRes function now serves this
// screen, the table view and the PDF, where it used to be two String copies.

internal enum class ExportRange(@StringRes val labelRes: Int) {
    LAST_30_DAYS(R.string.history_export_range_30),
    LAST_90_DAYS(R.string.history_export_range_90),
    THIS_YEAR(R.string.history_export_range_year),
    ALL(R.string.history_export_range_all),
}

/** Range preset and medicine filter for an export (§9 #26). One node per
 *  option, radios for the range, chips for the medicine. */
@Composable
internal fun ExportScopeDialog(
    range: ExportRange,
    onRange: (ExportRange) -> Unit,
    medicines: List<Medicine>,
    medicineId: Long?,
    onMedicine: (Long?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_export)) },
        text = {
            Column {
                Text(stringResource(R.string.history_export_range), style = MaterialTheme.typography.labelLarge)
                ExportRange.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = range == option, role = Role.RadioButton) { onRange(option) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = range == option, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(option.labelRes))
                    }
                }
                if (medicines.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.history_export_medicine), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = medicineId == null, onClick = { onMedicine(null) }, label = { Text(stringResource(R.string.history_export_all_medicines)) })
                        medicines.forEach { m ->
                            Spacer(Modifier.width(6.dp))
                            FilterChip(selected = medicineId == m.id, onClick = { onMedicine(m.id) }, label = { Text(m.name) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.history_export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
