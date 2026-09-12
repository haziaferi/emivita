package com.hazia.emivita.feature.today

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.dao.MedicineWithTags
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.core.scheduling.ProjectedOccurrence
import com.hazia.emivita.core.scheduling.ProjectionState
import com.hazia.emivita.ui.components.DoseEditDialog
import com.hazia.emivita.ui.components.rememberCurrentDate
import com.hazia.emivita.core.format.formatDoseWithUnit
import com.hazia.emivita.ui.components.parseHexColor
import com.hazia.emivita.ui.icons.MedicineIcons
import com.hazia.emivita.ui.theme.NeutralMedicineAccent
import com.hazia.emivita.ui.theme.doseCritical
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

internal enum class TimelineStatus { PENDING, TAKEN, SKIPPED, UPCOMING }

internal data class TimelineEntry(
    val medicine: Medicine,
    val doseAmount: Double,
    val status: TimelineStatus,
    // Non-null only for an already-materialized event -- the only ones
    // resolve() can act on. A projected-but-not-yet-fired occurrence has no
    // DoseEvent row yet (ReminderAlarmReceiver only materializes one when its
    // alarm actually fires), so it's shown but not actionable here.
    val doseEventId: Long?,
    // Only meaningful (and only ever read) for TAKEN/SKIPPED -- the edit
    // dialog's starting time for editRecordedTime (spec §9 #3).
    val resolvedAtEpochMillis: Long?,
)

private data class TimelineNode(
    val epochMillis: Long,
    val entries: List<TimelineEntry>,
)

private const val TODAY_CONSUMER_ID = "today-screen"
// Today + a peek into tomorrow is all this screen renders. Declaring it is a
// statement of Today's *minimum*, not a request for a cheaper projection: the
// cache's window is the max of every registration and its own floor, so this
// is a no-op while the floor stands at 28 and would only bite if the floor
// ever dropped below 2. The comment here used to read "cheap relative to the
// cache's 28-day default", which had it backwards -- the request replaced the
// 28-day window for every other reader of the shared projection rather than
// buying Today a cheaper slice of it. See docs/SPEC.md §7 R28.
private const val TODAY_WINDOW_DAYS = 2L

/**
 * Today's linked/timeline pattern (spec §6/§9 #5): a chronological rail of
 * "nodes," one per distinct time, each an aggregate of every dose due at that
 * instant -- multiple medicines sharing a slot (the common case for a
 * DoseGroup, or just two reminders that happen to land on the same time)
 * collapse into one node instead of one row per medicine.
 *
 * Consumes [OccurrenceProjectionCache] (spec §9 #4) rather than running its
 * own simulation -- registers a window on entry, releases it on exit. Merges
 * two sources per node:
 *  - already-materialized [com.hazia.emivita.core.data.entity.DoseEvent] rows
 *    for today ([DoseEventRepository.observeInRange]) -- real, actionable
 *    (Take/Skip), reflects actual resolved status.
 *  - the cache's still-projected occurrences for today that haven't
 *    materialized yet -- shown for visibility, not actionable, since there's
 *    no DoseEvent row to resolve() until the alarm actually fires.
 */
@Composable
fun TodayScreen(
    profileId: Long,
    medicineRepository: MedicineRepository,
    doseEventRepository: DoseEventRepository,
    occurrenceProjectionCache: OccurrenceProjectionCache,
    settingsRepository: SettingsRepository,
) {
    var showLogDose by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        occurrenceProjectionCache.requestWindow(TODAY_CONSUMER_ID, TODAY_WINDOW_DAYS)
        onDispose { occurrenceProjectionCache.releaseWindow(TODAY_CONSUMER_ID) }
    }

    val medicinesById = produceState<Map<Long, Medicine>?>(initialValue = null, profileId) {
        medicineRepository.observeForProfile(profileId).collectLatest { list ->
            value = list.associate { it.medicine.id to it.medicine }
        }
    }.value

    val zone = remember { ZoneId.systemDefault() }
    // rememberCurrentDate, not remember { LocalDate.now() } -- otherwise the
    // window below is frozen at whatever day this screen first composed on,
    // and Today keeps showing yesterday after midnight. Every derived value
    // and query key downstream is keyed on it so they all move together.
    val today = rememberCurrentDate(zone)
    val startOfToday = remember(today, zone) { today.atStartOfDay(zone).toInstant().toEpochMilli() }
    val startOfTomorrow = remember(today, zone) { today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }

    val materializedToday = produceState<List<DoseEventWithMedicine>?>(initialValue = null, profileId, startOfToday, startOfTomorrow) {
        doseEventRepository.observeInRange(profileId, startOfToday, startOfTomorrow).collectLatest { value = it }
    }.value

    val projectionState by produceState(initialValue = ProjectionState()) {
        occurrenceProjectionCache.state.collectLatest { value = it }
    }

    // "Lock reminders until taken" (spec §3) resolves per medicine as
    // `medicine.remindersLockedUntilTaken ?: this global default`, which is
    // exactly what SettingsRepository.isLockedUntilTaken does. That function is
    // suspend (it reads the DataStore flow), so it can't be called from
    // composition -- the global half is collected once here and the per-medicine
    // override applied synchronously at each row, giving the same answer.
    //
    // This screen used to offer an unconditional Skip chip, which made the
    // whole feature decorative: the notification builder omits Skip when
    // locked, ReminderActionReceiver rejects a stale one, and AlarmActivity
    // does both -- and then Today, the app's start destination and the surface
    // a user actually reaches for, let the same dose be dismissed in one tap.
    // A safety gate with an unguarded door on the front page is not a gate.
    //
    // `false` as the initial value is the repository's own default for this
    // key, not a placeholder, so the first frame can't briefly offer a Skip
    // that the resolved value would forbid.
    val lockedByDefault by produceState(initialValue = false) {
        settingsRepository.lockRemindersUntilTakenByDefault.collectLatest { value = it }
    }

    val nodes = remember(medicinesById, materializedToday, projectionState, startOfToday, startOfTomorrow) {
        buildTimeline(medicinesById, materializedToday, projectionState, startOfToday, startOfTomorrow)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.today_today), style = MaterialTheme.typography.headlineSmall)
            }

            when {
                medicinesById == null || materializedToday == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                nodes.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.today_nothing_scheduled_yet_add_a_medicine_and_a),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    items(nodes, key = { it.epochMillis }) { node ->
                        TimelineNodeItem(node, doseEventRepository, lockedByDefault)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        // Secondary action alongside Today's timeline (spec §6) -- picks a
        // medicine, suggests its last-used amount, defaults to "just now".
        // Not a bottom-nav destination.
        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.today_log_dose)) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = { showLogDose = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    if (showLogDose) {
        LogDoseDialog(
            profileId = profileId,
            medicineRepository = medicineRepository,
            doseEventRepository = doseEventRepository,
            onDismiss = { showLogDose = false },
            onLogged = { showLogDose = false },
        )
    }
}

/** Merges materialized events + still-projected occurrences into timeline
 *  nodes, one per distinct instant, entries sorted by medicine name within a
 *  node. A projected occurrence already covered by a materialized event
 *  (same reminder, same slot) is dropped from the projected side so nothing
 *  shows twice once an alarm has actually fired. */
private fun buildTimeline(
    medicinesById: Map<Long, Medicine>?,
    materialized: List<DoseEventWithMedicine>?,
    projection: ProjectionState,
    startOfToday: Long,
    startOfTomorrow: Long,
): List<TimelineNode> {
    if (medicinesById == null || materialized == null) return emptyList()

    val materializedKeys = HashSet<Pair<Long?, Long>>()
    val entries = mutableListOf<Pair<Long, TimelineEntry>>()

    materialized.forEach { row ->
        materializedKeys += row.doseEvent.reminderId to row.doseEvent.scheduledEpochMillis
        val status = when (row.doseEvent.status) {
            DoseStatus.PENDING -> TimelineStatus.PENDING
            DoseStatus.TAKEN -> TimelineStatus.TAKEN
            DoseStatus.SKIPPED -> TimelineStatus.SKIPPED
            DoseStatus.REFILLED -> return@forEach // a restock, not a dose -- doesn't belong on the dose timeline
        }
        entries += row.doseEvent.scheduledEpochMillis to TimelineEntry(
            medicine = row.medicine,
            doseAmount = row.doseEvent.doseAmount,
            status = status,
            doseEventId = row.doseEvent.id,
            resolvedAtEpochMillis = row.doseEvent.resolvedAtEpochMillis,
        )
    }

    projection.occurrences
        .asSequence()
        .filter { it.epochMillis in startOfToday until startOfTomorrow }
        .filter { (it.reminderId to it.epochMillis) !in materializedKeys }
        .forEach { occurrence: ProjectedOccurrence ->
            val medicine = medicinesById[occurrence.medicineId] ?: return@forEach
            entries += occurrence.epochMillis to TimelineEntry(
                medicine = medicine,
                doseAmount = occurrence.doseAmount,
                status = TimelineStatus.UPCOMING,
                doseEventId = null,
                resolvedAtEpochMillis = null,
            )
        }

    return entries
        .groupBy({ it.first }, { it.second })
        .map { (epochMillis, nodeEntries) -> TimelineNode(epochMillis, nodeEntries.sortedBy { it.medicine.name }) }
        .sortedBy { it.epochMillis }
}

@Composable
private fun TimelineNodeItem(
    node: TimelineNode,
    doseEventRepository: DoseEventRepository,
    lockedByDefault: Boolean,
) {
    val time = remember(node.epochMillis) {
        Instant.ofEpochMilli(node.epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<TimelineEntry?>(null) }

    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            node.entries.forEach { entry ->
                TimelineEntryRow(
                    entry = entry,
                    // Same resolution SettingsRepository.isLockedUntilTaken
                    // performs: the per-medicine override wins, the global
                    // default fills in when it is null.
                    canSkip = !(entry.medicine.remindersLockedUntilTaken ?: lockedByDefault),
                    onResolve = { status ->
                        val id = entry.doseEventId ?: return@TimelineEntryRow
                        scope.launch { doseEventRepository.resolve(id, status) }
                    },
                    // Event editing (spec §3/§9 #3) -- only an already-resolved
                    // entry has anything to edit; PENDING/UPCOMING have no
                    // recorded time or confirmed dose to correct yet.
                    onClick = {
                        if (entry.status == TimelineStatus.TAKEN || entry.status == TimelineStatus.SKIPPED) editing = entry
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    editing?.let { entry ->
        val id = entry.doseEventId
        if (id != null) {
            DoseEditDialog(
                medicineName = entry.medicine.name,
                doseEventId = id,
                originalStatus = if (entry.status == TimelineStatus.TAKEN) DoseStatus.TAKEN else DoseStatus.SKIPPED,
                originalDoseAmount = entry.doseAmount,
                originalResolvedAtEpochMillis = entry.resolvedAtEpochMillis,
                // Today never opens this for a PENDING entry -- those carry inline
                // Take/Skip chips -- so this is only ever the already-resolved
                // fallback. Passed because the dialog requires it, not relied on.
                scheduledEpochMillis = node.epochMillis,
                doseEventRepository = doseEventRepository,
                onDismiss = { editing = null },
                doseUnit = entry.medicine.doseUnit,
            )
        }
    }
}

@Composable
internal fun TimelineEntryRow(
    entry: TimelineEntry,
    canSkip: Boolean,
    onResolve: (DoseStatus) -> Unit,
    onClick: () -> Unit,
) {
    val accent = entry.medicine.color?.let { parseHexColor(it) } ?: NeutralMedicineAccent
    val muted = entry.status == TimelineStatus.UPCOMING
    val editable = entry.status == TimelineStatus.TAKEN || entry.status == TimelineStatus.SKIPPED

    // Accessibility (spec §9 #19 Stage B). One merged node per dose, with the
    // dose's state carried as stateDescription -- the semantic channel a
    // screen reader treats as state, the way R55's texture channel is the
    // one colour-blind eyes can read. The Take/Skip chips keep their own
    // nodes: clickable() merges its own descendants, so they are not
    // swallowed into the row.
    val statusLabel = stringResource(statusLabelRes(entry))
    val editLabel = stringResource(R.string.a11y_edit_dose)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = if (muted) 0.07f else 0.14f),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (editable) it.clickable(onClickLabel = editLabel, role = Role.Button, onClick = onClick) else it }
            .semantics(mergeDescendants = true) { stateDescription = statusLabel },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = accent.copy(alpha = if (muted) 0.5f else 1f), modifier = Modifier.size(40.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(MedicineIcons.iconFor(entry.medicine.iconKey), contentDescription = null, tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.medicine.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(
                        R.string.today_entry_summary,
                        stringResource(statusLabelRes(entry)),
                        formatDoseWithUnit(entry.doseAmount, entry.medicine.doseUnit),
                    ),
                    // Typography.doseCritical, not labelMedium written out: this
                    // is a dose amount, the text the floor exists for (§7 R47).
                    // Same 12sp today -- the point is that raising the floor is
                    // one edit, and that the call site says which rule binds it.
                    style = MaterialTheme.typography.doseCritical,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (entry.status) {
                TimelineStatus.PENDING -> {
                    AssistChip(
                        onClick = { onResolve(DoseStatus.TAKEN) },
                        label = { Text(stringResource(R.string.common_take)) },
                        leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                    )
                    // Omitted rather than disabled, matching how
                    // ReminderNotificationBuilder handles the same lock: a
                    // greyed-out control invites a tap and then explains
                    // nothing, and "Take" is the only answer the lock allows
                    // anyway. AlarmActivity does the same.
                    if (canSkip) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(
                            onClick = { onResolve(DoseStatus.SKIPPED) },
                            label = { Text(stringResource(R.string.common_skip)) },
                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                        )
                    }
                }
                else -> {}
            }
        }
    }
}

@StringRes
internal fun statusLabelRes(entry: TimelineEntry): Int = when (entry.status) {
    TimelineStatus.PENDING -> R.string.status_due
    TimelineStatus.TAKEN -> R.string.status_taken
    TimelineStatus.SKIPPED -> R.string.status_skipped
    TimelineStatus.UPCOMING -> R.string.status_upcoming
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDoseDialog(
    profileId: Long,
    medicineRepository: MedicineRepository,
    doseEventRepository: DoseEventRepository,
    onDismiss: () -> Unit,
    onLogged: () -> Unit,
) {
    var medicines by remember { mutableStateOf<List<MedicineWithTags>?>(null) }
    var selected by remember { mutableStateOf<Medicine?>(null) }
    var doseAmount by remember { mutableStateOf("1") }
    var menuExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(profileId) {
        medicineRepository.observeForProfile(profileId).collect { list ->
            medicines = list
            if (selected == null) selected = list.firstOrNull()?.medicine
        }
    }

    LaunchedEffect(selected?.id) {
        val medicineId = selected?.id ?: return@LaunchedEffect
        val lastUsed = doseEventRepository.getLastUsedDoseAmount(medicineId)
        if (lastUsed != null) doseAmount = lastUsed.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.today_log_dose)) },
        text = {
            Column {
                when {
                    medicines == null -> Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium)
                    medicines!!.isEmpty() -> Text(
                        stringResource(R.string.today_no_medicines_yet_add_one_first),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        ExposedDropdownMenuBox(
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = selected?.name ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.today_medicine)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                            )
                            ExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                medicines!!.forEach { entry ->
                                    DropdownMenuItem(
                                        text = { Text(entry.medicine.name) },
                                        onClick = {
                                            selected = entry.medicine
                                            menuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = doseAmount,
                            onValueChange = { doseAmount = it },
                            label = { Text(stringResource(R.string.common_dose_amount)) },
                            suffix = selected?.doseUnit?.let { unit -> { Text(unit) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.today_logged_as_taken_just_now),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        saveError?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && doseAmount.toDoubleOrNull() != null && !saving,
                onClick = {
                    val medicine = selected ?: return@TextButton
                    val amount = doseAmount.toDoubleOrNull() ?: return@TextButton
                    saving = true
                    saveError = null
                    scope.launch {
                        try {
                            doseEventRepository.logAdhocDose(medicine.id, amount, System.currentTimeMillis())
                            onLogged()
                        } catch (e: Exception) {
                            saving = false
                            saveError = "Couldn't log dose: ${e.message ?: "unknown error"}"
                        }
                    }
                },
            ) { Text(stringResource(R.string.today_log)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

