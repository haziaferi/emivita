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
import com.hazia.emivita.core.data.entity.isMissedInFiguresAt
import com.hazia.emivita.ui.components.rememberNowEpochMillis
import com.hazia.emivita.ui.theme.EmivitaTheme
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
import com.hazia.emivita.core.data.entity.isRecordOnly
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.ui.components.rememberCurrentDate
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
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.intl.Locale
import com.hazia.emivita.core.format.LocaleFormats

/**
 * Calendar view for History (spec §5 ADR-0001 shopping list, §9 #6).
 * Unlike the List/Table views,
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
    // Hoisted here rather than read inside CalendarDayCell: one date source
    // for the whole grid, and it keeps tracking after midnight so the
    // today-marker doesn't stay stuck on the day the screen opened.
    val today = rememberCurrentDate(zone)
    val now = rememberNowEpochMillis()
    val currentMonth = remember(today, zone) { YearMonth.now(zone) }
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
            .filter { !it.doseEvent.status.isRecordOnly }
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
                    today = today,
                    statuses = byDay[day.date]?.map { it.doseEvent.status }.orEmpty(),
                    missed = byDay[day.date].orEmpty().count {
                        it.doseEvent.status == DoseStatus.PENDING && it.doseEvent.isMissedInFiguresAt(now, zone)
                    },
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
                stringResource(R.string.history_calendar_tap_a_day_to_see_what_happened),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            monthLoading -> Text(
                stringResource(R.string.history_calendar_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.isNullOrEmpty() -> Text(
                stringResource(
                    R.string.history_nothing_recorded_on,
                    LocaleFormats.weekdayMonthDay(selectedDate),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                Text(
                    LocaleFormats.longWeekdayMonthDay(selectedDate),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                entries.forEach { row ->
                    // Gate lives in HistoryEntryRow itself (§7 R47) -- this
                    // callback used to repeat it, which is how the row's own
                    // stricter allow-list went unnoticed here.
                    HistoryEntryRow(row, onClick = { onEntryClick(row) })
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CalendarDayCell(day: CalendarDay, today: LocalDate, statuses: List<DoseStatus>, selected: Boolean, onClick: () -> Unit, missed: Int = 0) {
    val inMonth = day.position == DayPosition.MonthDate
    // Accessibility (spec §9 #19 Stage B; R55): the dots below carry state by
    // colour alone, so the description names the date and counts each status
    // for a screen reader.
    val description = calendarDayDescription(day.date, statuses, missed)
    val showDoses = stringResource(R.string.a11y_show_doses)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .let { if (inMonth) it.clickable(onClickLabel = showDoses, role = Role.Button, onClick = onClick) else it }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (inMonth) this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal,
                color = if (!inMonth) {
                    // The one surviving alpha-muted colour in the app, and
                    // deliberately not migrated to onSurfaceVariant with the
                    // other 26 (§7 R47): an out-of-month day sits in a cell
                    // that is not clickable (see `inMonth` above), so it is an
                    // inactive component, which WCAG 1.4.3 exempts from the
                    // 4.5:1 rule. Dimming it is what tells a reader the cell is
                    // dead; onSurfaceVariant would make it look live.
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (statuses.contains(DoseStatus.TAKEN)) DayDot(MaterialTheme.colorScheme.primary)
                // An unanswered dose reads Due on screen (§7 R121); once its
                // midnight has passed it counts as missed in the figures, and
                // the day shows the missed dot for it (the owner, 2026-10-23).
                if (statuses.contains(DoseStatus.SKIPPED)) DayDot(MaterialTheme.colorScheme.outline)
                if (missed > 0) DayDot(EmivitaTheme.colors.attention)
            }
        }
    }
}

/**
 * "Tuesday 9 September, 2 Taken, 1 Due" -- or "..., no doses". Full weekday
 * and month because a screen reader user has no grid to infer them from.
 * Internal so the Compose test can pin the wording.
 */
@Composable
internal fun calendarDayDescription(date: LocalDate, statuses: List<DoseStatus>, missed: Int = 0): String {
    val dateText = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.current.platformLocale))
    // The missed dot is spoken as Missed, so it never carries state by colour
    // alone (§7 R55); the rest of the day's unanswered doses read Due.
    val counts = listOf(
        statuses.count { it == DoseStatus.TAKEN } to R.string.status_taken,
        statuses.count { it == DoseStatus.SKIPPED } to R.string.status_skipped,
        statuses.count { it == DoseStatus.PENDING } - missed to R.string.status_due,
        missed to R.string.status_missed,
    )
    val parts = counts.mapNotNull { (n, label) ->
        if (n <= 0) null else stringResource(R.string.a11y_count_status, n, stringResource(label))
    }
    val summary = if (parts.isEmpty()) stringResource(R.string.a11y_no_doses) else parts.joinToString(", ")
    return stringResource(R.string.a11y_calendar_day, dateText, summary)
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
