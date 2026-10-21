package com.hazia.emivita.feature.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.settings.TodayLayout
import com.hazia.emivita.core.format.formatDoseWithUnit
import com.hazia.emivita.ui.components.MedicineTile
import com.hazia.emivita.ui.components.StateDisc
import com.hazia.emivita.ui.components.TonalActionChip
import java.time.Instant
import java.time.ZoneId

/**
 * The four Today layouts (§9 #37; the owner's `emivita-overview-mockups.html`
 * of 2026-08-11). The rows are the same objects in every one -- an entry, its
 * state disc, its tile, Prendi and Salta through [EntryHost] -- and only the
 * arrangement changes:
 *
 *  - TIMELINE: the rail with a disc per instant and a card per entry (R59);
 *  - GROUPED: the day in three bands, morning / afternoon / evening, each
 *    with an eyebrow header, the disc and the time inside the card;
 *  - COMPACT: a register of hairline rows with a 28 dp disc, where a tap on a
 *    due row opens its actions beneath it and a second tap closes them, so no
 *    single tap ever changes a dose (the mockup's cycling circle, declined);
 *  - HERO: the next dose in a card of the pale container with its actions,
 *    and the rest of the day as a compact register beneath.
 */
internal fun LazyListScope.todayItems(layout: TodayLayout, nodes: List<TimelineNode>, deps: TodayDeps, zone: ZoneId) {
    when (layout) {
        TodayLayout.TIMELINE -> items(nodes, key = { it.epochMillis }) { node ->
            TimelineNodeItem(node, deps)
            Spacer(Modifier.height(12.dp))
        }
        TodayLayout.GROUPED -> {
            for ((band, pairs) in groupByBand(nodes, zone)) {
                item(key = "band-" + band.name) { BandHeader(band) }
                items(pairs, key = { (node, entry) -> entryKey(node, entry) }) { (node, entry) ->
                    EntryHost(node, entry, deps) { canSkip, onResolve, onClick ->
                        TimelineEntryRow(
                            entry = entry, canSkip = canSkip, onResolve = onResolve, onClick = onClick,
                            leading = { StateDisc(discStateOf(listOf(entry.status)), size = 34.dp) },
                            timeLabel = nodeTimeLabel(node),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        TodayLayout.COMPACT -> {
            val pairs = flatten(nodes)
            items(pairs, key = { (node, entry) -> entryKey(node, entry) }) { (node, entry) ->
                EntryHost(node, entry, deps) { canSkip, onResolve, onClick -> CompactRow(node, entry, canSkip, onResolve, onClick) }
            }
        }
        TodayLayout.HERO -> {
            val pairs = flatten(nodes)
            val hero = nextDose(pairs)
            item(key = "hero") {
                if (hero != null) {
                    EntryHost(hero.first, hero.second, deps) { canSkip, onResolve, _ -> HeroCard(hero.first, hero.second, canSkip, onResolve) }
                } else {
                    HeroCard(null, null, canSkip = false, onResolve = {})
                }
                Spacer(Modifier.height(12.dp))
            }
            val rest = pairs.filter { it != hero }
            if (rest.isNotEmpty()) {
                item(key = "rest-header") {
                    Text(stringResource(R.string.today_rest_of_day), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                }
                items(rest, key = { (node, entry) -> entryKey(node, entry) }) { (node, entry) ->
                    EntryHost(node, entry, deps) { canSkip, onResolve, onClick -> CompactRow(node, entry, canSkip, onResolve, onClick) }
                }
            }
        }
    }
}

/** The three bands of the day, at the mockup's thresholds: before 11:00,
 *  11:00 to 16:59, from 17:00. Its "Pranzo" is "Pomeriggio" here because the
 *  band runs to 17:00. */
internal enum class DayBand(@StringRes val labelRes: Int) {
    MORNING(R.string.today_band_morning),
    AFTERNOON(R.string.today_band_afternoon),
    EVENING(R.string.today_band_evening),
}

internal fun bandOf(epochMillis: Long, zone: ZoneId): DayBand {
    val hour = Instant.ofEpochMilli(epochMillis).atZone(zone).hour
    return when {
        hour < 11 -> DayBand.MORNING
        hour < 17 -> DayBand.AFTERNOON
        else -> DayBand.EVENING
    }
}

internal fun flatten(nodes: List<TimelineNode>): List<Pair<TimelineNode, TimelineEntry>> =
    nodes.flatMap { node -> node.entries.map { node to it } }

/** Bands in day order, empty ones omitted; the rows inside keep the timeline's order. */
internal fun groupByBand(nodes: List<TimelineNode>, zone: ZoneId): Map<DayBand, List<Pair<TimelineNode, TimelineEntry>>> =
    flatten(nodes).groupBy { (node, _) -> bandOf(node.epochMillis, zone) }.toSortedMap()

/** The hero: the first entry that is due, else the first still to come, else none. */
internal fun nextDose(pairs: List<Pair<TimelineNode, TimelineEntry>>): Pair<TimelineNode, TimelineEntry>? =
    pairs.firstOrNull { it.second.status == TimelineStatus.PENDING } ?: pairs.firstOrNull { it.second.status == TimelineStatus.UPCOMING }

private fun entryKey(node: TimelineNode, entry: TimelineEntry) = "${node.epochMillis}-${entry.medicine?.id}-${entry.reminderId}-${entry.doseEventId}"

@Composable
private fun BandHeader(band: DayBand) {
    Row(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(6.dp)) {}
        Spacer(Modifier.width(8.dp))
        Text(stringResource(band.labelRes).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntrySummary(entry: TimelineEntry) {
    Text(
        stringResource(R.string.today_entry_summary, stringResource(statusLabelRes(entry)), formatDoseWithUnit(entry.doseAmount, entry.medicine?.doseUnit, LocalResources.current)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EntryActions(entry: TimelineEntry, canSkip: Boolean, onResolve: (DoseStatus) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
        when (entry.status) {
            TimelineStatus.PENDING -> {
                TonalActionChip(onClick = { onResolve(DoseStatus.TAKEN) }, label = stringResource(R.string.common_take), icon = Icons.Filled.Check)
                if (canSkip) {
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = { onResolve(DoseStatus.SKIPPED) }, label = { Text(stringResource(R.string.common_skip)) }, leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) })
                }
            }
            TimelineStatus.UPCOMING -> if (entry.reminderId != null) {
                TonalActionChip(onClick = { onResolve(DoseStatus.TAKEN) }, label = stringResource(R.string.today_take_now), icon = Icons.Filled.Check)
            }
            else -> {}
        }
    }
}

/** A register row. Tapping a row that is due or still to come opens its
 *  actions beneath it; tapping an answered one opens the edit dialog. */
@Composable
internal fun CompactRow(node: TimelineNode, entry: TimelineEntry, canSkip: Boolean, onResolve: (DoseStatus) -> Unit, onClick: () -> Unit) {
    var open by remember(entryKey(node, entry)) { mutableStateOf(false) }
    val actionable = entry.status == TimelineStatus.PENDING || (entry.status == TimelineStatus.UPCOMING && entry.reminderId != null)
    val statusLabel = stringResource(statusLabelRes(entry))
    val editLabel = stringResource(R.string.a11y_edit_dose)
    val openLabel = stringResource(R.string.a11y_show_actions)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (actionable) openLabel else editLabel) { if (actionable) open = !open else onClick() }
            .semantics(mergeDescendants = true) { stateDescription = statusLabel }
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDisc(discStateOf(listOf(entry.status)), size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(nodeTimeLabel(node), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                EntrySummary(entry)
            }
            Spacer(Modifier.width(10.dp))
            MedicineTile(entry.medicine, size = 28.dp)
        }
        AnimatedVisibility(visible = open && actionable) { EntryActions(entry, canSkip, onResolve) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The next dose, in the pale container with its actions; with nothing
 *  due or to come, a line saying so. */
@Composable
internal fun HeroCard(node: TimelineNode?, entry: TimelineEntry?, canSkip: Boolean, onResolve: (DoseStatus) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.today_next_dose).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (node == null || entry == null) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.today_hero_none), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                return@Column
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        nodeTimeLabel(node) + " · " + formatDoseWithUnit(entry.doseAmount, entry.medicine?.doseUnit, LocalResources.current),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    entry.instructions?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(12.dp))
                MedicineTile(entry.medicine, size = 44.dp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResolve(DoseStatus.TAKEN) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (entry.status == TimelineStatus.PENDING) R.string.common_take else R.string.today_take_now))
                }
                if (entry.status == TimelineStatus.PENDING && canSkip) {
                    OutlinedButton(onClick = { onResolve(DoseStatus.SKIPPED) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_skip))
                    }
                }
            }
        }
    }
}
