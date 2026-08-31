package com.hazia.emivita.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.stacked
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One calendar day's worth of resolved-dose counts, feeding [AdherenceChart].
 *  Refills are excluded entirely -- a restock isn't a dose outcome, same
 *  exclusion [AdherenceSummary]'s taken/skipped ratio already makes. */
data class DayAdherenceCounts(
    val date: LocalDate,
    val taken: Int,
    val skipped: Int,
    val missed: Int,
) {
    val total: Int get() = taken + skipped + missed
}

/**
 * Stacked daily bar chart -- taken/skipped/missed per day across the visible
 * History window. Chosen over a pie for the reason spec §9 #6/T5 already
 * names: this data is "trend-over-time," which a single-window pie can't
 * show and a stacked bar can. It's also the only chart shape the pinned
 * `com.patrykandpatrick.vico:compose-m3:2.1.0` dependency actually supports --
 * Vico 2.x's cartesian module has line/column/candlestick layers only, no
 * pie layer (that existed in Vico 1.x and was dropped); confirmed by
 * inspecting the resolved AAR directly, not assumed from the library's name.
 *
 * Missed uses `error`, not `tertiary` -- a fired-and-never-resolved dose is
 * the one outcome here that's worse than a deliberate Skip, so it's the one
 * that earns the app's reserved alarm-adjacent color; Skip gets `tertiary`
 * instead of also reading as an error state.
 */
@Composable
fun AdherenceChart(counts: List<DayAdherenceCounts>) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(counts) {
        modelProducer.runTransaction {
            columnSeries {
                series(counts.map { it.taken })
                series(counts.map { it.skipped })
                series(counts.map { it.missed })
            }
        }
    }

    val takenColor = MaterialTheme.colorScheme.primary
    val skippedColor = MaterialTheme.colorScheme.tertiary
    val missedColor = MaterialTheme.colorScheme.error
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    val dateFormatter = remember(counts) {
        CartesianValueFormatter { _, x, _ ->
            counts.getOrNull(x.toInt())?.date?.format(DateTimeFormatter.ofPattern("M/d")).orEmpty()
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Adherence trend", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberColumnCartesianLayer(
                        columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                            rememberLineComponent(fill = fill(takenColor), thickness = 6.dp),
                            rememberLineComponent(fill = fill(skippedColor), thickness = 6.dp),
                            rememberLineComponent(fill = fill(missedColor), thickness = 6.dp),
                        ),
                        mergeMode = { ColumnCartesianLayer.MergeMode.stacked() },
                    ),
                    startAxis = VerticalAxis.rememberStart(
                        label = rememberTextComponent(color = axisLabelColor),
                        line = rememberLineComponent(fill = fill(axisLineColor), thickness = 1.dp),
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        label = rememberTextComponent(color = axisLabelColor),
                        line = rememberLineComponent(fill = fill(axisLineColor), thickness = 1.dp),
                        valueFormatter = dateFormatter,
                    ),
                ),
                modelProducer = modelProducer,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(takenColor, "Taken")
                LegendDot(skippedColor, "Skipped")
                LegendDot(missedColor, "Missed")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
