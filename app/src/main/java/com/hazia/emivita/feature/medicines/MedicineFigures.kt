package com.hazia.emivita.feature.medicines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.core.data.dao.DoseEventWithMedicine
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.feature.history.DayAdherenceCounts
import com.hazia.emivita.feature.history.SeriesPattern
import com.hazia.emivita.feature.history.dailyAdherenceCounts
import com.hazia.emivita.feature.history.seriesPatternTile
import com.hazia.emivita.ui.components.rememberCurrentDate
import com.hazia.emivita.ui.theme.EmivitaTheme
import java.time.Instant
import java.time.ZoneId

internal const val MEDICINE_FIGURES_TAG = "medicine-figures"
internal const val MEDICINE_STRIP_TAG = "medicine-figures-strip"
internal const val MEDICINE_FIGURES_DAYS = 30

/** One medicine's last [MEDICINE_FIGURES_DAYS] days (§7 R135): a day each, oldest first, and its latest Taken. */
internal data class MedicineFiguresData(val days: List<DayAdherenceCounts>, val lastTakenAt: Long?) {
    val taken: Int get() = days.sumOf { it.taken }
    val counted: Int get() = days.sumOf { it.total }
}

/** History's own day counts (§7 R121) cut to one medicine, so the page and History never disagree. */
internal fun medicineFigures(events: List<DoseEventWithMedicine>, medicineId: Long, zone: ZoneId, now: Long): MedicineFiguresData {
    val mine = events.filter { it.doseEvent.medicineId == medicineId }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return MedicineFiguresData(
        days = dailyAdherenceCounts(mine, zone, today, now).takeLast(MEDICINE_FIGURES_DAYS),
        lastTakenAt = mine.filter { it.doseEvent.status == DoseStatus.TAKEN }.mapNotNull { it.doseEvent.resolvedAtEpochMillis }.filter { it <= now }.maxOrNull(),
    )
}

/**
 * "27 di 30 prese", the last Taken, and a one-row strip of the days (§7 R135, the owner's pick: about 20 dp tall, so
 * the reminders stay on the first screen). A missed day wears the chart's cross-hatch as well as its colour, so the
 * strip never carries state by hue alone. Nothing is drawn for a medicine with nothing to count in the window.
 */
@Composable
internal fun MedicineFigures(medicine: Medicine, doseEventRepository: DoseEventRepository) {
    val zone = remember { ZoneId.systemDefault() }
    val today = rememberCurrentDate(zone)
    val events by produceState<List<DoseEventWithMedicine>?>(null, medicine.profileId, today) {
        val start = today.minusDays(MEDICINE_FIGURES_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        doseEventRepository.observeInRange(medicine.profileId, start, end).collect { value = it }
    }
    val now = System.currentTimeMillis()
    val figures = events?.let { medicineFigures(it, medicine.id, zone, now) } ?: return
    if (figures.counted == 0 && figures.lastTakenAt == null) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(MEDICINE_FIGURES_TAG).semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.medicine_figures_window, MEDICINE_FIGURES_DAYS),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (figures.counted > 0) {
                Text(
                    pluralStringResource(R.plurals.doses_taken_of, figures.counted, figures.taken, figures.counted),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            figures.lastTakenAt?.let { at ->
                val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                val time = LocaleFormats.time(at, zone)
                Text(
                    when (day) {
                        today -> stringResource(R.string.medicine_figures_last_today, time)
                        today.minusDays(1) -> stringResource(R.string.medicine_figures_last_yesterday, time)
                        else -> stringResource(R.string.medicine_figures_last_date, LocaleFormats.monthDay(day), time)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (figures.counted > 0) {
                Spacer(Modifier.height(12.dp))
                DayStrip(figures.days)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** The days side by side, oldest first: taken solid, missed cross-hatched, a day with nothing counted left pale. */
@Composable
private fun DayStrip(days: List<DayAdherenceCounts>) {
    val taken = MaterialTheme.colorScheme.primary
    val missed = EmivitaTheme.colors.attention
    val card = MaterialTheme.colorScheme.surfaceContainer
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val density = LocalDensity.current.density
    val hatch = remember(missed, card, density) {
        ShaderBrush(ImageShader(seriesPatternTile(SeriesPattern.CROSS_HATCH, missed, card, density).asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    val shape = RoundedCornerShape(2.dp)
    Row(Modifier.fillMaxWidth().height(20.dp).testTag(MEDICINE_STRIP_TAG), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        days.forEach { day ->
            val cell = Modifier.weight(1f).height(20.dp)
            Spacer(
                when {
                    day.total == 0 -> cell.background(empty, shape)
                    day.missed > 0 -> cell.background(hatch, shape)
                    else -> cell.background(taken, shape)
                },
            )
        }
    }
}
