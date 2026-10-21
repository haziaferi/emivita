package com.hazia.emivita.feature.today

import com.hazia.emivita.ui.components.AttentionCard
import com.hazia.emivita.feature.permissions.rememberPermissionState
import com.hazia.emivita.feature.permissions.rememberPermissionFixer
import com.hazia.emivita.feature.permissions.missingKey
import com.hazia.emivita.feature.permissions.MissingPermissionsCard
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.hazia.emivita.ui.theme.doseCritical
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import com.hazia.emivita.ui.components.TabHeader
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalResources
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.format.parseDecimal
import android.text.format.DateUtils
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.entity.DoseEvent
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.core.notification.ReminderNotificationBuilder
import com.hazia.emivita.ui.components.DateField
import com.hazia.emivita.ui.components.TimeOfDayField
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableIntStateOf
import com.hazia.emivita.core.scheduling.AlarmWatchdog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import com.hazia.emivita.ui.components.MedicineTile
import com.hazia.emivita.ui.components.StateDisc
import com.hazia.emivita.ui.components.TonalActionChip
import com.hazia.emivita.ui.components.DiscState
import com.hazia.emivita.core.data.settings.TodayLayout

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
    // §9 #26: the reminder behind the slot (UPCOMING needs it for "Take now"),
    // its one-line instructions, and the note on an already-recorded dose.
    val reminderId: Long? = null,
    val instructions: String? = null,
    val notes: String? = null,
    /** §9 #31 (A-24): a linked follow-up projected off its parent's projected
     *  slot -- shown as "≈", with the parent slot it follows. */
    val estimated: Boolean = false,
    val afterEpochMillis: Long? = null,
    val afterMinutes: Int? = null,
    /** The reminder asks the amount when taken (§7 R109, B-45): "Take all"
     *  leaves it out (§7 R110), since the amount is the person's to type. */
    val asksAmount: Boolean = false,
)

internal data class TimelineNode(
    val epochMillis: Long,
    val entries: List<TimelineEntry>,
)

/** What every layout's rows need to act on an entry (§9 #37). */
internal class TodayDeps(
    val doseEventRepository: DoseEventRepository,
    val lockedByDefault: Boolean,
    val reminderRepository: ReminderRepository,
    val notificationBuilder: ReminderNotificationBuilder,
)

private const val TODAY_CONSUMER_ID = "today-screen"
// Today + a peek into tomorrow is all this screen renders. It states Today's
// *minimum*, not a request for a cheaper projection: the cache's window is the
// max of every registration and its 28-day floor, so this is a no-op unless
// the floor ever drops below 2 (docs/SPEC.md §7 R28).
private const val TODAY_WINDOW_DAYS = 2L

/**
 * Today's linked/timeline pattern (spec §6/§9 #5): a chronological rail of
 * "nodes", one per distinct time, each aggregating every dose due at that
 * instant, so medicines sharing a slot collapse into one node.
 *
 * It reads [OccurrenceProjectionCache] (spec §9 #4), registering a window on
 * entry and releasing it on exit, and merges two sources per node: today's
 * materialized [com.hazia.emivita.core.data.entity.DoseEvent] rows
 * ([DoseEventRepository.observeInRange]), which are actionable (Take/Skip),
 * and the cache's not-yet-materialized occurrences, shown but not actionable
 * because there is no DoseEvent row to resolve() until the alarm fires.
 */
@Composable
fun TodayScreen(
    profileId: Long,
    medicineRepository: MedicineRepository,
    doseEventRepository: DoseEventRepository,
    occurrenceProjectionCache: OccurrenceProjectionCache,
    settingsRepository: SettingsRepository,
    reminderRepository: ReminderRepository,
    notificationBuilder: ReminderNotificationBuilder,
    onOpenNotificationSettings: () -> Unit = {},
    onOpenDataAndBackup: () -> Unit = {},
) {
    var showLogDose by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val missedAlarm by AlarmWatchdog.notice.collectAsState()
    // The as-needed row's Log chip opens the dialog on its own medicine.
    var logDosePreselected by remember { mutableStateOf<Medicine?>(null) }

    DisposableEffect(Unit) {
        occurrenceProjectionCache.requestWindow(TODAY_CONSUMER_ID, TODAY_WINDOW_DAYS)
        onDispose { occurrenceProjectionCache.releaseWindow(TODAY_CONSUMER_ID) }
    }

    val medicinesById = produceState<Map<Long, Medicine>?>(initialValue = null, profileId) {
        medicineRepository.observeForProfile(profileId).collectLatest { list ->
            value = list.associate { it.medicine.id to it.medicine }
        }
    }.value
    // Reminders by id, for the instructions line and for "Take now" on a
    // projected slot; and the medicines with no dosing reminder switched on,
    // which are the as-needed ones (§9 #26; Pill Time's whole model).
    val withReminders = produceState<List<MedicineWithReminders>?>(initialValue = null, profileId) {
        medicineRepository.observeWithRemindersForProfile(profileId).collectLatest { value = it }
    }.value
    val remindersById = remember(withReminders) { withReminders.orEmpty().flatMap { it.reminders }.associateBy { it.id } }
    val asNeeded = remember(withReminders) {
        withReminders.orEmpty().filter { m -> m.reminders.none { it.enabled } }.map { it.medicine }.sortedBy { it.sortOrder }
    }

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
    // `medicine.remindersLockedUntilTaken ?: this global default`, the same
    // answer as the suspend SettingsRepository.isLockedUntilTaken: the global
    // half is collected once here and the override applied at each row, so
    // Today's Skip chip obeys the lock like the notification and AlarmActivity.
    // `false` as the initial value is the repository's own default for this
    // key, so the first frame cannot briefly offer a Skip the lock forbids.
    val lockedByDefault by produceState(initialValue = false) {
        settingsRepository.lockRemindersUntilTakenByDefault.collectLatest { value = it }
    }
    // §9 #37: the owner's four arrangements of the same rows, from Appearance.
    val layout by produceState(initialValue = TodayLayout.TIMELINE) {
        settingsRepository.todayLayout.collectLatest { value = it }
    }
    val deps = remember(doseEventRepository, lockedByDefault, reminderRepository, notificationBuilder) {
        TodayDeps(doseEventRepository, lockedByDefault, reminderRepository, notificationBuilder)
    }

    val nodes = remember(medicinesById, materializedToday, projectionState, startOfToday, startOfTomorrow, remindersById) {
        buildTimeline(medicinesById, materializedToday, projectionState, startOfToday, startOfTomorrow, remindersById)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabHeader(stringResource(R.string.today_today))
            missedAlarm?.let { plannedAt ->
                MissedAlarmCard(plannedAt, onOpen = onOpenNotificationSettings, onDismiss = { AlarmWatchdog.dismissNotice(context) })
            }
            TodayNoticeCards(settingsRepository, hasMedicines = !medicinesById.isNullOrEmpty(), onOpenDataAndBackup = onOpenDataAndBackup)

            when {
                medicinesById == null || materializedToday == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                nodes.isEmpty() && asNeeded.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.today_nothing_scheduled_yet_add_a_medicine_and_a),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    todayItems(layout, nodes, deps, zone)
                    // As-needed medicines (§9 #26): a standing row each, with
                    // the time since the last dose and a Log chip. They have no
                    // slot on the timeline, so they sit beneath it.
                    if (asNeeded.isNotEmpty()) {
                        item(key = "as-needed-header") {
                            Text(
                                stringResource(R.string.today_as_needed),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                            )
                        }
                        items(asNeeded, key = { "as-needed-" + it.id }) { medicine ->
                            AsNeededRow(
                                medicine = medicine,
                                doseEventRepository = doseEventRepository,
                                // Re-fetches "last taken" after a log today.
                                refreshKey = materializedToday.size,
                                onLog = { logDosePreselected = medicine; showLogDose = true },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item(key = "fab-space") { Spacer(Modifier.height(72.dp)) }
                }
            }
        }

        // Secondary action alongside Today's timeline (spec §6) -- picks a
        // medicine, suggests its last-used amount, defaults to "just now".
        // Not a bottom-nav destination.
        // The label is also the node's description (§7 R81): on the LE2123 the
        // extended FAB's text never reached the accessibility tree (uiautomator
        // showed the button with no text leaf, while every plain Button had one),
        // so TalkBack had nothing to say for Today's main action.
        val logDoseLabel = stringResource(R.string.today_log_dose)
        ExtendedFloatingActionButton(
            text = { Text(logDoseLabel) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = { logDosePreselected = null; showLogDose = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .semantics { contentDescription = logDoseLabel },
        )
    }

    if (showLogDose) {
        LogDoseDialog(
            profileId = profileId,
            medicineRepository = medicineRepository,
            doseEventRepository = doseEventRepository,
            onDismiss = { showLogDose = false },
            onLogged = { showLogDose = false },
            preselected = logDosePreselected,
        )
    }
}

/** One as-needed medicine (§9 #26; Pill Time PT-02): name, how long since
 *  the last dose in the platform's own relative words, and Log. */
@Composable
internal fun AsNeededRow(
    medicine: Medicine,
    doseEventRepository: DoseEventRepository,
    refreshKey: Int,
    onLog: () -> Unit,
) {
    val last by produceState<DoseEvent?>(initialValue = null, medicine.id, refreshKey) {
        value = doseEventRepository.lastDose(medicine.id)
    }
    val lastLine = last?.let { event ->
        val at = event.resolvedAtEpochMillis ?: event.scheduledEpochMillis
        stringResource(R.string.today_as_needed_last, DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
    } ?: stringResource(R.string.today_as_needed_never)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MedicineTile(medicine)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(medicine.name, style = MaterialTheme.typography.titleMedium)
                Text(lastLine, style = MaterialTheme.typography.doseCritical, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AssistChip(
                onClick = onLog,
                label = { Text(stringResource(R.string.today_log)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        }
    }
}

/** Merges materialized events + still-projected occurrences into timeline
 *  nodes, one per distinct instant, entries sorted by medicine name within a
 *  node. A projected occurrence already covered by a materialized event
 *  (same reminder, same slot) is dropped from the projected side so nothing
 *  shows twice once an alarm has actually fired. */
internal fun buildTimeline(
    medicinesById: Map<Long, Medicine>?,
    materialized: List<DoseEventWithMedicine>?,
    projection: ProjectionState,
    startOfToday: Long,
    startOfTomorrow: Long,
    remindersById: Map<Long, Reminder> = emptyMap(),
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
            DoseStatus.DELETED -> return@forEach // soft-deleted (§9 #26): the slot stays claimed, the row stays out of sight
            DoseStatus.STOCK_ALERT_SEEN, DoseStatus.EXPIRY_ALERT_SEEN -> return@forEach // a seen alert (§7 R105), not a dose
        }
        entries += row.doseEvent.scheduledEpochMillis to TimelineEntry(
            medicine = row.medicine,
            doseAmount = row.doseEvent.doseAmount,
            status = status,
            doseEventId = row.doseEvent.id,
            resolvedAtEpochMillis = row.doseEvent.resolvedAtEpochMillis,
            reminderId = row.doseEvent.reminderId,
            instructions = row.doseEvent.reminderId?.let { remindersById[it]?.instructions },
            notes = row.doseEvent.notes,
            asksAmount = row.doseEvent.reminderId?.let { remindersById[it]?.variableAmount } == true,
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
                reminderId = occurrence.reminderId,
                instructions = remindersById[occurrence.reminderId]?.instructions,
                estimated = occurrence.estimated,
                afterEpochMillis = occurrence.afterEpochMillis,
                afterMinutes = occurrence.afterEpochMillis?.let { ((occurrence.epochMillis - it) / 60_000L).toInt() },
            )
        }

    return entries
        .groupBy({ it.first }, { it.second })
        .map { (epochMillis, nodeEntries) -> TimelineNode(epochMillis, nodeEntries.sortedBy { it.medicine.name }) }
        .sortedBy { it.epochMillis }
}

@Composable
internal fun TimelineNodeItem(node: TimelineNode, deps: TodayDeps) {
    val time = nodeTimeLabel(node)

    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            // The reference app's disc (§7 R76): pale container, the node's
            // state as its glyph -- due wins over the rest, then a slot still
            // to come, then the recorded answer.
            StateDisc(discStateOf(node.entries.map { it.status }))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            TakeAllChip(node, time, deps)
            node.entries.forEach { entry ->
                EntryHost(node, entry, deps) { canSkip, onResolve, onClick ->
                    TimelineEntryRow(entry = entry, canSkip = canSkip, onResolve = onResolve, onClick = onClick)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * "Take all (n)" on a time with two or more doses due (§7 R107, census D-20),
 * confirmed in [TakeGroupSheet]. Each dose goes through [DoseEventRepository.resolve]
 * one at a time, as its own Take chip would send it, and NonCancellable keeps the
 * run whole when the first answers shrink the group and recompose this away.
 */
@Composable
private fun TakeAllChip(node: TimelineNode, time: String, deps: TodayDeps) {
    val scope = rememberCoroutineScope()
    val pending = node.entries.filter { it.status == TimelineStatus.PENDING && it.doseEventId != null }
    // A dose whose reminder asks the amount stays due (§7 R110): it is the
    // person's to answer with a number, which "Take all" cannot ask for.
    val (leftOut, due) = pending.partition { it.asksAmount }
    if (due.size < 2) return
    var confirmAll by remember(node.epochMillis) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
        TonalActionChip(onClick = { confirmAll = true }, label = stringResource(R.string.today_take_all, due.size), icon = Icons.Filled.Check)
    }
    if (confirmAll) {
        TakeGroupSheet(
            time = time,
            entries = due,
            leftOut = leftOut,
            onConfirm = {
                confirmAll = false
                val ids = due.mapNotNull { it.doseEventId }
                scope.launch { withContext(NonCancellable) { ids.forEach { deps.doseEventRepository.resolve(it, DoseStatus.TAKEN) } } }
            },
            onDismiss = { confirmAll = false },
        )
    }
}

/** "≈" when every entry at this instant is an estimate (§9 #31): the time
 *  is where a follow-up will land if its parent fires on time. */
@Composable
internal fun nodeTimeLabel(node: TimelineNode): String {
    val estimatedNode = node.entries.all { it.estimated }
    return remember(node.epochMillis, estimatedNode) {
        LocaleFormats.time(node.epochMillis).let { if (estimatedNode) "≈ $it" else it }
    }
}

/**
 * What acting on one entry means, whichever layout draws it (§9 #37): the
 * skip gate, resolving a materialized event or taking a projected slot now,
 * and the edit dialog for an answered dose. The four layouts differ only in
 * the row they put inside.
 */
@Composable
internal fun EntryHost(
    node: TimelineNode,
    entry: TimelineEntry,
    deps: TodayDeps,
    row: @Composable (canSkip: Boolean, onResolve: (DoseStatus) -> Unit, onClick: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Saveable, so the Dose Edit dialog survives a rotation (§7 R83 (a)).
    var editing by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf(false) }
    // Same resolution SettingsRepository.isLockedUntilTaken performs: the
    // per-medicine override wins, the global default fills in when it is null.
    val canSkip = !(entry.medicine.remindersLockedUntilTaken ?: deps.lockedByDefault)
    val onResolve: (DoseStatus) -> Unit = { status ->
        val id = entry.doseEventId
        scope.launch {
            if (id != null) {
                deps.doseEventRepository.resolve(id, status)
            } else if (entry.reminderId != null && status == DoseStatus.TAKEN) {
                // "Take now" on a projected slot (§9 #26).
                deps.doseEventRepository.takeProjectedNow(entry.reminderId, entry.medicine.id, node.epochMillis, entry.doseAmount)
            } else if (entry.reminderId != null) {
                // Skip ahead, from the upcoming dose's sheet only (§7 R107).
                deps.doseEventRepository.answerProjected(entry.reminderId, entry.medicine.id, node.epochMillis, entry.doseAmount, status)
            }
        }
    }
    row(
        canSkip,
        onResolve,
        // Event editing (spec §3/§9 #3) -- only an already-resolved entry has
        // anything to edit. A slot still to come opens its sheet (§7 R107).
        {
            if (entry.status == TimelineStatus.TAKEN || entry.status == TimelineStatus.SKIPPED) {
                editing = true
            } else if (entry.status == TimelineStatus.UPCOMING && entry.reminderId != null) {
                detail = true
            }
        },
    )

    if (detail) {
        DoseDetailSheet(
            entry = entry,
            time = nodeTimeLabel(node),
            canSkip = canSkip,
            onResolve = { status -> detail = false; onResolve(status) },
            onDismiss = { detail = false },
        )
    }

    val id = entry.doseEventId
    if (editing && id != null) {
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
            doseEventRepository = deps.doseEventRepository,
            onDismiss = { editing = false },
            doseUnit = entry.medicine.doseUnit,
            originalNotes = entry.notes,
            onReopened = { reopened ->
                val reminder = reopened.reminderId?.let { deps.reminderRepository.getById(it) }
                if (reminder != null) deps.notificationBuilder.buildAndPost(reopened, reminder, entry.medicine)
            },
        )
    }
}

@Composable
internal fun TimelineEntryRow(
    entry: TimelineEntry,
    canSkip: Boolean,
    onResolve: (DoseStatus) -> Unit,
    onClick: () -> Unit,
    // §9 #37: the grouped layout puts the state disc and the time inside the
    // card, where the timeline has them on the rail outside it.
    leading: (@Composable () -> Unit)? = null,
    timeLabel: String? = null,
) {
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
    // A slot still to come opens its details sheet (§7 R107, census D-55).
    val opensDetail = entry.status == TimelineStatus.UPCOMING && entry.reminderId != null
    val detailLabel = stringResource(R.string.a11y_show_dose_details)
    Surface(
        shape = RoundedCornerShape(14.dp),
        // Tonal, whatever the medicine's colour: the tile carries that (§7 R76).
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .let {
                when {
                    editable -> it.clickable(onClickLabel = editLabel, role = Role.Button, onClick = onClick)
                    opensDetail -> it.clickable(onClickLabel = detailLabel, role = Role.Button, onClick = onClick)
                    else -> it
                }
            }
            .semantics(mergeDescendants = true) { stateDescription = statusLabel },
    ) {
        // Two lines when the dose is due: name and status first, Take/Skip on
        // their own row beneath. They used to share one Row, and on a phone-width
        // screen two labelled chips beside the icon left the name about three
        // characters -- "Ser / tral / ine". Seen on the device (§7 R59), never
        // in a test: the chips only render for a PENDING dose, and Robolectric
        // asserts semantics, not pixels.
        Column(Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                if (timeLabel != null) {
                    Text(timeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    entry.medicine.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(
                        R.string.today_entry_summary,
                        stringResource(statusLabelRes(entry)),
                        formatDoseWithUnit(entry.doseAmount, entry.medicine.doseUnit, LocalResources.current),
                    ),
                    // Typography.doseCritical, not labelMedium written out: this
                    // is a dose amount, the text the floor exists for (§7 R47).
                    // Same 12sp today -- the point is that raising the floor is
                    // one edit, and that the call site says which rule binds it.
                    style = MaterialTheme.typography.doseCritical,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The reminder's one line (§9 #26): "with food" belongs where
                // the dose is taken, not only on the notification.
                entry.instructions?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // An estimate says what it follows (§9 #31, A-24).
                entry.afterEpochMillis?.takeIf { entry.estimated }?.let { after ->
                    Text(
                        stringResource(R.string.today_after_dose, entry.afterMinutes ?: 0, LocaleFormats.time(after)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            MedicineTile(entry.medicine)
        }
            when (entry.status) {
                TimelineStatus.PENDING -> Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TonalActionChip(
                        onClick = { onResolve(DoseStatus.TAKEN) },
                        label = stringResource(R.string.common_take),
                        icon = Icons.Filled.Check,
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
                // A slot the alarm has not reached yet can still be answered
                // early (§9 #26; medTimer's "take now" on a future dose).
                TimelineStatus.UPCOMING -> if (entry.reminderId != null) Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TonalActionChip(
                        onClick = { onResolve(DoseStatus.TAKEN) },
                        label = stringResource(R.string.today_take_now),
                        icon = Icons.Filled.Check,
                    )
                }
                else -> {}
            }
        }
    }
}

/** The disc for a node that may hold several entries: due wins, then a slot
 *  still to come, then the recorded answer (taken over skipped). */
internal fun discStateOf(statuses: List<TimelineStatus>): DiscState = when {
    TimelineStatus.PENDING in statuses -> DiscState.DUE
    TimelineStatus.UPCOMING in statuses -> DiscState.UPCOMING
    TimelineStatus.TAKEN in statuses -> DiscState.TAKEN
    else -> DiscState.SKIPPED
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
    preselected: Medicine? = null,
) {
    val resources = LocalResources.current
    var medicines by remember { mutableStateOf<List<MedicineWithTags>?>(null) }
    var selected by remember { mutableStateOf<Medicine?>(preselected) }
    var doseAmount by remember { mutableStateOf("1") }
    var menuExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Backdating (§9 #26; DITMM/Pill Time): "just now" by default, any day
    // and time on request. The chosen instant is what the headroom counts to.
    val zone = remember { ZoneId.systemDefault() }
    val openedAt = remember { System.currentTimeMillis() }
    var backdate by remember { mutableStateOf(false) }
    val openedZoned = remember { Instant.ofEpochMilli(openedAt).atZone(zone) }
    var atDay by remember { mutableStateOf<Long?>(openedZoned.toLocalDate().toEpochDay()) }
    var atHour by remember { mutableIntStateOf(openedZoned.hour) }
    var atMinute by remember { mutableIntStateOf(openedZoned.minute) }
    val atEpochMillis = remember(backdate, atDay, atHour, atMinute) {
        if (!backdate) null else atDay?.let { java.time.LocalDate.ofEpochDay(it).atTime(atHour, atMinute).atZone(zone).toInstant().toEpochMilli() }
    }
    // Headroom against the medicine's as-needed ceiling (§9 #26; PT-01).
    val headroom by produceState<PrnHeadroom?>(initialValue = null, selected?.id, atEpochMillis, preselected) {
        val medicine = selected
        val max = medicine?.prnMaxDoses; val hours = medicine?.prnWindowHours
        value = if (medicine == null || max == null || hours == null) null else {
            val at = atEpochMillis ?: System.currentTimeMillis()
            val window = hours * 60L * 60L * 1000L
            val taken = doseEventRepository.takenSince(medicine.id, at - window).filter { (it.resolvedAtEpochMillis ?: 0L) <= at }
            prnHeadroom(taken.mapNotNull { it.resolvedAtEpochMillis }, max, window)
        }
    }
    var confirmOverCeiling by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        medicineRepository.observeForProfile(profileId).collect { list ->
            medicines = list
            if (selected == null) selected = list.firstOrNull()?.medicine
        }
    }

    fun log() {
        val medicine = selected ?: return
        val amount = parseDecimal(doseAmount) ?: return
        saving = true
        saveError = null
        scope.launch {
            try {
                doseEventRepository.logAdhocDose(medicine.id, amount, atEpochMillis ?: System.currentTimeMillis())
                onLogged()
            } catch (e: Exception) {
                saving = false
                saveError = resources.getString(R.string.error_could_not_log_dose, e.message ?: resources.getString(R.string.error_unknown))
            }
        }
    }

    if (confirmOverCeiling) {
        // At the ceiling the app asks once and never refuses (the owner's
        // choice, §9 #26): the person knows more than the counter does.
        val h = headroom
        AlertDialog(
            onDismissRequest = { confirmOverCeiling = false },
            title = { Text(stringResource(R.string.today_prn_over_title)) },
            text = {
                Text(
                    if (h != null) stringResource(R.string.today_prn_over_text, h.taken, h.max, selected?.prnWindowHours ?: 0)
                    else stringResource(R.string.today_prn_over_title),
                )
            },
            confirmButton = { TextButton(onClick = { confirmOverCeiling = false; log() }) { Text(stringResource(R.string.today_prn_log_anyway)) } },
            dismissButton = { TextButton(onClick = { confirmOverCeiling = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
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
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = backdate, role = Role.Switch) { backdate = it },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(if (backdate) R.string.today_log_earlier else R.string.today_logged_as_taken_just_now),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = backdate, onCheckedChange = null)
                        }
                        if (backdate) {
                            DateField(stringResource(R.string.dose_edit_move_day), atDay) { atDay = it }
                            TimeOfDayField(stringResource(R.string.dose_edit_move_time), atHour, atMinute) { h, m -> atHour = h; atMinute = m }
                        }
                        headroom?.let { h ->
                            Spacer(Modifier.height(4.dp))
                            val line = stringResource(R.string.today_prn_headroom, h.taken, h.max, selected?.prnWindowHours ?: 0)
                            val returns = h.headroomReturnsAtEpochMillis?.let { " · " + stringResource(R.string.today_prn_headroom_returns, LocaleFormats.monthDayTime(it)) } ?: ""
                            Text(
                                line + returns,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (h.atCeiling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                enabled = selected != null && parseDecimal(doseAmount) != null && !saving && (!backdate || atDay != null),
                onClick = { if (headroom?.atCeiling == true) confirmOverCeiling = true else log() },
            ) { Text(stringResource(R.string.today_log)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** "A reminder planned for … did not fire" (§9 #29; DP-04): the one place the
 *  app admits the phone held it back, with the way to the battery settings
 *  and a dismiss. One merged node plus its two buttons. */
@Composable
internal fun MissedAlarmCard(plannedAtEpochMillis: Long, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AttentionCard(stringResource(R.string.missed_alarm_title), modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
        Text(
            stringResource(R.string.missed_alarm_body, LocaleFormats.monthDayTime(plannedAtEpochMillis)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 6.dp)) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.missed_alarm_open)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.missed_alarm_dismiss)) }
        }
    }
}

/**
 * Today's two standing notices (§7 R83). A grant reminders depend on is
 * missing -- the card names it and opens its page, and "Not now" holds until a
 * different one goes missing. No backup has ever been written while there is
 * something to lose -- the card stays until one is, because without it a lost
 * Keystore key leaves nothing to restore (§7 R83 (f)).
 */
@Composable
private fun TodayNoticeCards(settingsRepository: SettingsRepository, hasMedicines: Boolean, onOpenDataAndBackup: () -> Unit) {
    val scope = rememberCoroutineScope()
    val setupShown by settingsRepository.permissionSetupShown.collectAsState(initial = false)
    val dismissedFor by settingsRepository.permissionCardDismissedFor.collectAsState(initial = null)
    val permissions = rememberPermissionState()
    val onFix = rememberPermissionFixer(settingsRepository)
    val missing = permissions.missing
    if (setupShown && missing.isNotEmpty() && dismissedFor != missingKey(missing)) {
        MissingPermissionsCard(permissions, onFix, onDismiss = { scope.launch { settingsRepository.setPermissionCardDismissedFor(missingKey(missing)) } })
    }
    // Long.MIN_VALUE: still loading, so the card cannot flash on a phone that has a backup.
    val lastBackup by settingsRepository.lastBackupEpochMillis.collectAsState(initial = Long.MIN_VALUE)
    if (hasMedicines && lastBackup == null) NoBackupCard(onOpenDataAndBackup)
}

@Composable
internal fun NoBackupCard(onBackUp: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.backup_none_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.backup_none_body), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onBackUp) { Text(stringResource(R.string.backup_none_action)) }
        }
    }
}
