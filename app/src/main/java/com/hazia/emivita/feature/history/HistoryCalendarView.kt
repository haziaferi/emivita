package com.hazia.emivita.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.kizitonwose.calendar.compose.CalendarState
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.CalendarMonth
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.OutDateStyle
import com.kizitonwose.calendar.core.daysOfWeek
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/**
 * Calendar view for History (spec §5 ADR-0001 shopping list, §9 #6 --
 * previously unbuilt, closed out this batch). Unlike the List/Table views,
 * which both read the fixed [HISTORY_LOOKBACK_DAYS] window already loaded by
 * [HistoryScreen], this view re-queries [DoseEventRepository] per visible
 * month as the user scrolls -- a real calendar needs to browse arbitrary past
 * months, not just the last 30 days, and reusing one rolling-window query for
 * both would mean either capping calendar browsing at 30 days (defeats the
 * point) or forcing every other view to load an unbounded history it doesn't
 * need. Future months are not reachable (`endMonth = currentMonth`): dose
 * events only materialize once a reminder's alarm actually fires (spec §3),
 * so a future month can never have data regardless of how far the projection
 * cache could theoretically look ahead -- History intentionally only reads
 * the direct DB query side of the medTimer ADR-0002 split (see
 * [HistoryScreen]'s own doc comment), not the cache's forward projection.
 */
@Composable
fun HistoryCalendarView(
    profileId: Long,
    zone: ZoneId,
    doseEventRepository: DoseEventRepository,
    onEntryClick: (DoseEventWithMedicine) -> Unit,
) {
    val currentMonth = remember { YearMonth.now(zone) }
    val startMonth = remember { currentMonth.minusMonths(24) }
    val daysOfWeek = remember { daysOfWeek() }
    val calendarState = rememberCalendarState(
        startMonth = startMonth,
        endMonth = currentMonth,
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = daysOfWeek.first(),
        outDateStyle = OutDateStyle.EndOfRow,
    )
    val visibleMonth = rememberVisibleMonth(calendarState)

    val monthEvents = produceState<List<DoseEventWithMedicine>?>(initialValue = null, visibleMonth.yearMonth, profileId) {
        val monthStart = visibleMonth.yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = visibleMonth.yearMonth.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        doseEventRepository.observeInRange(profileId, monthStart, monthEnd).collectLatest { value = it }
    }.value

    // REFILLED excluded from the day dots -- same reasoning as
    // dailyAdherenceCounts (AdherenceChart.kt): a restock isn't a dose
    // outcome to surface here.
    val byDay = remember(monthEvents) {
        (monthEvents ?: emptyList())
            .filter { it.doseEvent.status != DoseStatus.REFILLED }
            .groupBy { Instant.ofEpochMilli(it.doseEvent.scheduledEpochMillis).atZone(zone).toLocalDate() }
    }

    var selectedDate by remember(visibleMonth.yearMonth) { mutableStateOf<LocalDate?>(null) }

    Column(Modifier.fillMaxWidth()) {
        DaysOfWeekTitle(daysOfWeek)
        HorizontalCalendar(
            state = calendarState,
            modifier = Modifier.padding(horizontal = 8.dp),
            dayContent = { day ->
                CalendarDayCell(
                    day = day,
                    statuses = byDay[day.date]?.map { it.doseEvent.status }.orEmpty(),
                    selected = day.date == selectedDate,
                    onClick = { selectedDate = if (selectedDate == day.date) null else day.date },
                )
            },
        )
        Spacer(Modifier.height(4.dp))
        CalendarSelectedDayPanel(
            selectedDate = selectedDate,
            entries = selectedDate?.let { byDay[it].orEmpty().sortedByDescending { row -> row.doseEvent.scheduledEpochMillis } },
            monthLoading = monthEvents == null,
            onEntryClick = onEntryClick,
        )
    }
}

@Composable
private fun CalendarSelectedDayPanel(
    selectedDate: LocalDate?,
    entries: List<DoseEventWithMedicine>?,
    monthLoading: Boolean,
    onEntryClick: (DoseEventWithMedicine) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        when {
            selectedDate == null -> Text(
                "Tap a day to see what happened.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            monthLoading -> Text(
                "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            entries.isNullOrEmpty() -> Text(
                "Nothing recorded on ${selectedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            else -> {
                Text(
                    selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                entries.forEach { row ->
                    HistoryEntryRow(row, onClick = {
                        if (row.doseEvent.status == DoseStatus.TAKEN || row.doseEvent.status == DoseStatus.SKIPPED) onEntryClick(row)
                    })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DaysOfWeekTitle(daysOfWeek: List<DayOfWeek>) {
    // LocalConfiguration, not Locale.getDefault(): the latter isn't observable
    // Compose state, so these headers would keep rendering the old language
    // after a system locale change until something else forced recomposition.
    val locale = LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        for (dayOfWeek in daysOfWeek) {
            Text(
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                text = dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun CalendarDayCell(day: CalendarDay, statuses: List<DoseStatus>, selected: Boolean, onClick: () -> Unit) {
    val inMonth = day.position == DayPosition.MonthDate
    val today = remember { LocalDate.now() }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .let { if (inMonth) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal,
                color = if (!inMonth) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (statuses.contains(DoseStatus.TAKEN)) DayDot(MaterialTheme.colorScheme.primary)
                if (statuses.contains(DoseStatus.SKIPPED)) DayDot(MaterialTheme.colorScheme.tertiary)
                if (statuses.contains(DoseStatus.PENDING)) DayDot(MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DayDot(color: Color) {
    Spacer(Modifier.size(5.dp).background(color, CircleShape))
}

/** Kizitonwose's own state doesn't expose "settled visible month" as a
 *  single value directly usable in a `remember` key -- `firstVisibleMonth`
 *  updates continuously mid-scroll, which would refire the month query on
 *  every frame. Debounce to "scroll just stopped" instead, the same idiom
 *  the library's own sample app uses. */
@Composable
private fun rememberVisibleMonth(state: CalendarState): CalendarMonth {
    var visibleMonth by remember(state) { mutableStateOf(state.firstVisibleMonth) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .filter { scrolling -> !scrolling }
            .collectLatest { visibleMonth = state.firstVisibleMonth }
    }
    return visibleMonth
}
