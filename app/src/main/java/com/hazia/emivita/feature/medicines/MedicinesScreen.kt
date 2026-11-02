package com.hazia.emivita.feature.medicines

import com.hazia.emivita.ui.form.DialogActionPair
import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hazia.emivita.R
import com.hazia.emivita.ui.components.TabHeader
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.core.data.settings.SettingsRepository
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.ui.components.rememberCurrentDate
import com.hazia.emivita.ui.components.rememberNowEpochMillis
import kotlinx.coroutines.launch
import java.time.ZoneId
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.ExperimentalFoundationApi
import com.hazia.emivita.ui.components.MedicineTile
import com.hazia.emivita.ui.components.AttentionGlyph

/**
 * The Medicines tab (spec §9 #24). What each card says and how the list is
 * ordered are pure functions in MedicineListState.kt; this file renders them
 * and owns the two gestures the owner asked for -- a sort menu and, in manual
 * order, drag-to-reorder by the handle. The drag is hand-rolled on
 * `detectDragGestures` (Appendix F HM-03 is the reference shape; the
 * `reorderable` library is not in the offline cache) and every handle also
 * carries "Move up" / "Move down" accessibility actions, since a screen
 * reader cannot drag.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MedicinesScreen(
    profileId: Long,
    medicineRepository: MedicineRepository,
    doseEventRepository: DoseEventRepository,
    occurrenceProjectionCache: OccurrenceProjectionCache,
    settingsRepository: SettingsRepository,
    reminderRepository: ReminderRepository,
    onAddMedicineWithBarcode: (barcode: String) -> Unit,
    onOpenMedicine: (medicineId: Long) -> Unit,
    /** "Save and add details" in the add sheet: the new medicine's full edit form (§7 R114). */
    onAddDetails: (medicineId: Long) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = rememberCurrentDate(zone)
    val todayStart = remember(today) { today.atStartOfDay(zone).toInstant().toEpochMilli() }
    val todayEnd = remember(today) { today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
    val withReminders by produceState(initialValue = emptyList(), profileId) {
        medicineRepository.observeWithRemindersForProfile(profileId).collect { value = it }
    }
    val tagsByMedicine by produceState(initialValue = emptyMap<Long, List<String>>(), profileId) {
        medicineRepository.observeForProfile(profileId).collect { list -> value = list.associate { it.medicine.id to it.tags.map { t -> t.name } } }
    }
    val todayEvents by produceState(initialValue = emptyList(), profileId, todayStart) {
        doseEventRepository.observeInRange(profileId, todayStart, todayEnd).collect { value = it.map { e -> e.doseEvent } }
    }
    val projection by occurrenceProjectionCache.state.collectAsState()
    val sortName by settingsRepository.medicinesSortName.collectAsState(initial = null)
    val hideEmpty by settingsRepository.medicinesHideEmpty.collectAsState(initial = false)
    val selectedTags by settingsRepository.medicinesTagFilter.collectAsState(initial = emptySet())
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val allTags = remember(tagsByMedicine) { tagsByMedicine.values.flatten().distinct() }
    val sort = remember(sortName) { sortName?.let { n -> MedicinesSort.entries.firstOrNull { it.name == n } } ?: MedicinesSort.MANUAL }
    val locale = Locale.current.platformLocale
    val scope = rememberCoroutineScope()
    // A swiped card's delete is the medicine page's: an archive, restorable (§7 R127, D-03).
    val archive: suspend (Medicine) -> Unit = { medicineRepository.archive(it) }

    // The minute's clock, so a card whose slot the OS is holding turns due with no pass to prompt it (§7 R143).
    val now = rememberNowEpochMillis()
    val entries = remember(withReminders, tagsByMedicine, todayEvents, projection, today, now, sort, hideEmpty, query, selectedTags, locale) {
        val built = withReminders.map { mw ->
            MedicineListEntry(
                medicine = mw.medicine,
                tagNames = tagsByMedicine[mw.medicine.id].orEmpty(),
                state = cardStateFor(
                    mw.medicine, mw.reminders, todayEvents, projection.occurrences,
                    projection.stockRunOutDates[mw.medicine.id], today, now, todayStart, todayEnd,
                ),
            )
        }.filter { !hideEmpty || !it.state.empty }
        sortEntries(filterEntries(built, query, selectedTags), sort, locale)
    }
    val sections = remember(entries, sort, locale) { if (sort == MedicinesSort.GROUP_BY_TAG) groupByTag(entries, locale) else emptyList() }

    // D-07 V3 (§7 R114): the add button opens one sheet, open across a rotation.
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding) {
        QuickAddMedicineSheet(
            otherNames = withReminders.map { it.medicine.name },
            save = { input -> saveQuickAdd(medicineRepository, profileId, input) },
            onSaved = { id, addDetails -> adding = false; if (addDetails) onAddDetails(id) },
            onDismiss = { adding = false },
        )
    }

    var notFoundCode by remember { mutableStateOf<String?>(null) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val code = result.data?.getStringExtra("SCAN_RESULT") ?: return@rememberLauncherForActivityResult
            scope.launch {
                val match = medicineRepository.getByBarcodeValue(code)
                if (match != null) onOpenMedicine(match.id) else notFoundCode = code
            }
        }
    }
    // No scanner app is news about one tap, not a state of the list (§7 R138, the owner: "Make it a snackbar"):
    // kept above the list, its three lines at font 2.0 left the medicines under half the window.
    val scanBar = remember { SnackbarHostState() }
    val scanResources = LocalResources.current
    var showSortMenu by remember { mutableStateOf(false) }

    // Local order while a drag is in flight; persisted on drop. Keyed on the
    // sorted entries so any data change resets it.
    var order by remember(entries) { mutableStateOf(entries) }
    val listState = rememberLazyListState()
    val drag = remember(listState) { DragReorderState(listState) }

    fun persistMove(fromId: Long, toIndex: Int) {
        val orders = order.map { it.medicine.sortOrder }
        val newOrder = sortOrderForMove(orders, toIndex)
        scope.launch { medicineRepository.setSortOrder(fromId, newOrder) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // The shared header (§9 #33, F-14; §7 R59): the actions float over
            // its end so they add no height, and the layout test measures it.
            TabHeader(stringResource(R.string.medicines_medicines)) {
                    // Search (§9 #27; HM-13): the field opens under the header
                    // and the list narrows as you type. A search is a moment,
                    // so it is not persisted; the tag filter beside it is.
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.medicines_search))
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.medicines_sort))
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            MedicinesSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(option.labelRes())) },
                                    leadingIcon = { RadioButton(selected = sort == option, onClick = null) },
                                    onClick = {
                                        showSortMenu = false
                                        scope.launch { settingsRepository.setMedicinesSortName(option.name) }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            try {
                                scanLauncher.launch(Intent("com.google.zxing.client.android.SCAN"))
                            } catch (_: ActivityNotFoundException) {
                                scope.launch {
                                    scanBar.currentSnackbarData?.dismiss()
                                    scanBar.showSnackbar(
                                        scanResources.getString(R.string.common_no_barcode_scanner_app_installed_e_g_binary),
                                        duration = SnackbarDuration.Long,
                                    )
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.medicines_scan_to_find))
                    }
                    // Pause or resume every medicine's reminders (§7 R127, C-29 V2).
                    PauseAllMenu(withReminders, reminderRepository, settingsRepository)
            }
            MedicinesFilterBar(
                searching = searching,
                query = query,
                onQuery = { query = it },
                tags = allTags,
                selectedTags = selectedTags,
                onToggleTag = { tag ->
                    scope.launch { settingsRepository.setMedicinesTagFilter(if (tag in selectedTags) selectedTags - tag else selectedTags + tag) }
                },
                // The hide-empty chip (HM-07) shows only when it can do
                // something, so an empty-stock-free list has no dead chip.
                hideEmptyOffered = withReminders.any { it.medicine.stockInfo.unit != null && it.medicine.stockInfo.quantity <= 0.0 },
                hideEmpty = hideEmpty,
                onHideEmpty = { scope.launch { settingsRepository.setMedicinesHideEmpty(!hideEmpty) } },
            )
            AllPausedLine(withReminders, reminderRepository, settingsRepository)
            if (withReminders.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.medicines_no_medicines_yet_tap_to_add_your_first_one), style = MaterialTheme.typography.bodyMedium)
                }
            } else if (entries.isEmpty()) {
                // A filter or a search left nothing -- say that, not "no medicines".
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.medicines_nothing_matches), style = MaterialTheme.typography.bodyMedium)
                }
            } else if (sort == MedicinesSort.GROUP_BY_TAG) {
                // Tags as kits (HM-01): a sticky header per section, no drag
                // handle -- a move across sections is not a defined operation.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    sections.forEach { section ->
                        stickyHeader(key = "tag-" + (section.name ?: "")) {
                            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    section.name ?: stringResource(R.string.medicines_untagged),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(vertical = 6.dp).semantics { heading() },
                                )
                            }
                        }
                        items(section.entries, key = { (section.name ?: "") + "/" + it.medicine.id }) { entry ->
                            SwipeToDelete(entry.medicine, archive) {
                                MedicineCard(entry = entry, onClick = { onOpenMedicine(entry.medicine.id) }, dragHandle = null)
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(order, key = { _, e -> e.medicine.id }) { index, entry ->
                        val dragging = drag.draggingIndex == index
                        Box(
                            Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = if (dragging) drag.offsetY else 0f },
                        ) {
                            SwipeToDelete(entry.medicine, archive) {
                            MedicineCard(
                                entry = entry,
                                onClick = { onOpenMedicine(entry.medicine.id) },
                                dragHandle = if (sort == MedicinesSort.MANUAL && order.size > 1) {
                                    {
                                        DragHandle(
                                            itemKey = entry.medicine.id,
                                            index = index,
                                            count = order.size,
                                            drag = drag,
                                            onMoveBy = { delta ->
                                                val to = (index + delta).coerceIn(0, order.lastIndex)
                                                if (to != index) {
                                                    order = order.toMutableList().also { it.add(to, it.removeAt(index)) }
                                                    persistMove(entry.medicine.id, to)
                                                }
                                            },
                                            onReorder = { from, to ->
                                                order = order.toMutableList().also { it.add(to, it.removeAt(from)) }
                                            },
                                            onDrop = { finalIndex -> persistMove(entry.medicine.id, finalIndex) },
                                        )
                                    }
                                } else null,
                            )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.medicines_add_medicine))
        }
        // Above the add button, as Today's undo bar is.
        SnackbarHost(scanBar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    val pendingCode = notFoundCode
    if (pendingCode != null) {
        AlertDialog(
            onDismissRequest = { notFoundCode = null },
            title = { Text(stringResource(R.string.medicines_not_found)) },
            text = { Text(stringResource(R.string.medicines_no_medicine_matches_that_code_yet_add_it_as)) },
            confirmButton = {
                DialogActionPair(stringResource(R.string.common_confirm), onConfirm = { notFoundCode = null; onAddMedicineWithBarcode(pendingCode) }, onDismiss = { notFoundCode = null })
            },
        )
    }
}

fun MedicinesSort.labelRes(): Int = when (this) {
    MedicinesSort.MANUAL -> R.string.medicines_sort_manual
    MedicinesSort.NAME -> R.string.medicines_sort_name
    MedicinesSort.NEXT_DOSE -> R.string.medicines_sort_next_dose
    MedicinesSort.EXPIRY_STOCK -> R.string.medicines_sort_expiry_stock
    MedicinesSort.GROUP_BY_TAG -> R.string.medicines_sort_group_by_tag
}

/**
 * The search field, the tag chips and the hide-empty chip (§9 #27). One
 * node per chip (the Stage B rule); the search field carries its label as
 * its content description so a screen reader finds "Search medicines" and
 * not an unlabelled edit box. The badge on the count of selected tags is
 * plain text beside the chips, which is what a badge reads as anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MedicinesFilterBar(
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    tags: List<String>,
    selectedTags: Set<String>,
    onToggleTag: (String) -> Unit,
    hideEmptyOffered: Boolean,
    hideEmpty: Boolean,
    onHideEmpty: () -> Unit,
) {
    if (searching) {
        val label = stringResource(R.string.medicines_search)
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(label) },
            singleLine = true,
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.medicines_search_clear)) } }
            } else null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics { contentDescription = label },
        )
        Spacer(Modifier.height(4.dp))
    }
    if (tags.isNotEmpty() || hideEmptyOffered) {
        Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (selectedTags.isNotEmpty()) {
                BadgedBox(badge = { Badge { Text(selectedTags.size.toString()) } }) {
                    Icon(Icons.Filled.FilterList, contentDescription = pluralStringResource(R.plurals.medicines_filter_active, selectedTags.size, selectedTags.size))
                }
                Spacer(Modifier.width(10.dp))
            }
            tags.forEach { tag ->
                FilterChip(selected = tag in selectedTags, onClick = { onToggleTag(tag) }, label = { Text(tag) })
                Spacer(Modifier.width(6.dp))
            }
            if (hideEmptyOffered) {
                FilterChip(selected = hideEmpty, onClick = onHideEmpty, label = { Text(stringResource(R.string.medicines_hide_empty)) })
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Which item is being dragged and how far; the list re-orders as the
 *  dragged card's centre crosses a neighbour, the way a user expects. */
internal class DragReorderState(private val listState: LazyListState) {
    var draggingIndex by mutableStateOf<Int?>(null)
    var offsetY by mutableFloatStateOf(0f)

    fun start(index: Int) { draggingIndex = index; offsetY = 0f }

    /** Returns the new index if the drag crossed a neighbour, else null. */
    fun drag(deltaY: Float): Pair<Int, Int>? {
        val from = draggingIndex ?: return null
        offsetY += deltaY
        val items = listState.layoutInfo.visibleItemsInfo
        val me = items.firstOrNull { it.index == from } ?: return null
        val centre = me.offset + offsetY + me.size / 2f
        val target = items.firstOrNull { it.index != from && centre >= it.offset && centre < it.offset + it.size } ?: return null
        offsetY -= (target.index - from) * me.size
        draggingIndex = target.index
        return from to target.index
    }

    fun end(): Int? = draggingIndex.also { draggingIndex = null; offsetY = 0f }
}

@Composable
internal fun DragHandle(
    itemKey: Any,
    index: Int,
    count: Int,
    drag: DragReorderState,
    onMoveBy: (Int) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    onDrop: (finalIndex: Int) -> Unit,
) {
    val moveUp = stringResource(R.string.medicines_move_up)
    val moveDown = stringResource(R.string.medicines_move_down)
    val label = stringResource(R.string.medicines_drag_to_reorder)
    val position = stringResource(R.string.medicines_position_of, index + 1, count)
    val currentIndex by rememberUpdatedState(index)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnDrop by rememberUpdatedState(onDrop)
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(28.dp)
            .semantics {
                contentDescription = label
                stateDescription = position
                customActions = listOfNotNull(
                    if (index > 0) CustomAccessibilityAction(moveUp) { onMoveBy(-1); true } else null,
                    if (index < count - 1) CustomAccessibilityAction(moveDown) { onMoveBy(1); true } else null,
                )
            }
            // Keyed on the item, not its index: the drag moves the index, and a
            // key that changes mid-gesture tears the gesture down (§7 R83).
            .pointerInput(itemKey) {
                detectDragGestures(
                    onDragStart = { drag.start(currentIndex) },
                    onDrag = { change, delta ->
                        change.consume()
                        drag.drag(delta.y)?.let { (from, to) -> currentOnReorder(from, to) }
                    },
                    onDragEnd = { drag.end()?.let(currentOnDrop) },
                    // The list already shows the new place, so a cancelled drag
                    // keeps it rather than leaving the screen and the store apart.
                    onDragCancel = { drag.end()?.let(currentOnDrop) },
                )
            },
    )
}

@Composable
internal fun MedicineCard(entry: MedicineListEntry, onClick: () -> Unit, dragHandle: (@Composable () -> Unit)?) {
    val medicine = entry.medicine
    val state = entry.state
    // Expired or empty puts an `attention` glyph beside the state line (HM-08;
    // §7 R76) -- the warm, non-red role §6 reserves for "look at this", never
    // `error`, and never a fill: the card stays tonal.
    val needsAttention = state.expired || state.empty

    val statusText = when (state.today) {
        TodayStatus.DUE -> stringResource(R.string.medicines_status_due)
        TodayStatus.DONE -> stringResource(R.string.medicines_status_done_today)
        TodayStatus.UPCOMING -> stringResource(R.string.medicines_status_upcoming)
        TodayStatus.NONE -> null
    }
    val stockText = when {
        state.empty -> stringResource(R.string.medicines_state_empty)
        state.runsOutInDays == 0L -> stringResource(R.string.medicines_runs_out_today)
        state.runsOutInDays == 1L -> stringResource(R.string.medicines_runs_out_tomorrow)
        state.runsOutInDays != null -> pluralStringResource(R.plurals.medicines_runs_out_in_days, state.runsOutInDays.toInt(), state.runsOutInDays.toInt())
        else -> null
    }
    val expiryText = if (state.expired) stringResource(R.string.medicines_state_expired) else null
    val courseText = when (val c = state.course) {
        is CourseState.StartsOn -> stringResource(R.string.medicines_course_starts_on, LocaleFormats.monthDay(c.date))
        is CourseState.Until -> stringResource(R.string.medicines_course_until, LocaleFormats.monthDay(c.date))
        is CourseState.CycleDay -> stringResource(R.string.medicines_course_cycle_day, c.day, c.activeDays)
        CourseState.None -> null
    }
    val stateLine = listOfNotNull(statusText, stockText, expiryText, courseText).joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            // One node for the reader: name, then the state line as its state.
            .semantics(mergeDescendants = true) { if (stateLine.isNotEmpty()) stateDescription = stateLine },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MedicineTile(medicine, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(medicine.name, style = MaterialTheme.typography.titleMedium)
                medicine.strength?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (stateLine.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (needsAttention) {
                            AttentionGlyph()
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            stateLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (entry.tagNames.isNotEmpty()) {
                    Row {
                        entry.tagNames.take(3).forEach { tag ->
                            AssistChip(onClick = {}, enabled = false, label = { Text(tag, style = MaterialTheme.typography.labelSmall) })
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }
            if (dragHandle != null) {
                Spacer(Modifier.width(8.dp))
                dragHandle()
            }
        }
    }
}
