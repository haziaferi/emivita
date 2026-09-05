package com.hazia.emivita.feature.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
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
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.shader.ShaderProvider
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
 *
 * **Colour is no longer load-bearing here (spec §7 R54/R55.)** This was the one
 * surface in the app where state was conveyed by hue alone -- stacked bars read
 * through a legend, no icon or label beside them -- and measured against the
 * candidate palettes the taken/missed pair falls under the categorical floor in
 * 4 of 16 registers once colour-vision deficiency is simulated. Each series now
 * carries a [SeriesPattern] as well, the legend teaches the pattern rather than
 * a bare dot, and the stack order is stated. Three channels: texture, position,
 * colour. Any one of them alone identifies a series.
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
    val surfaceColor = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current.density
    // Tiles are rebuilt only when a colour or the density actually changes --
    // allocating three bitmaps per recomposition would be a real cost on a
    // screen that also scrolls a list.
    val takenFill = rememberPatternFill(SeriesPattern.SOLID, takenColor, surfaceColor, density)
    val skippedFill = rememberPatternFill(SeriesPattern.DIAGONAL, skippedColor, surfaceColor, density)
    val missedFill = rememberPatternFill(SeriesPattern.CROSS_HATCH, missedColor, surfaceColor, density)
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
                            rememberLineComponent(fill = takenFill, thickness = 6.dp),
                            rememberLineComponent(fill = skippedFill, thickness = 6.dp),
                            rememberLineComponent(fill = missedFill, thickness = 6.dp),
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
                LegendSwatch(SeriesPattern.SOLID, takenColor, surfaceColor, density, "Taken")
                LegendSwatch(SeriesPattern.DIAGONAL, skippedColor, surfaceColor, density, "Skipped")
                LegendSwatch(SeriesPattern.CROSS_HATCH, missedColor, surfaceColor, density, "Missed")
            }
            Spacer(Modifier.height(6.dp))
            // The third channel, said out loud. The stack order is fixed, so
            // position identifies a series even where a 6dp bar is too narrow
            // for its texture to read.
            Text(
                "Each bar stacks taken at the bottom, then skipped, then missed.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The tile as a Vico [Fill], rebuilt only when an input changes. */
@Composable
private fun rememberPatternFill(
    pattern: SeriesPattern,
    seriesColor: Color,
    surfaceColor: Color,
    density: Float,
): Fill = remember(pattern, seriesColor, surfaceColor, density) {
    Fill(ShaderProvider.bitmap(seriesPatternTile(pattern, seriesColor, surfaceColor, density)))
}

/**
 * A legend entry showing the series' actual texture, not a plain dot. A dot
 * teaches the colour and nothing else, which is what left the chart relying on
 * hue; this teaches the pattern the bars are drawn with.
 */
@Composable
private fun LegendSwatch(
    pattern: SeriesPattern,
    color: Color,
    surfaceColor: Color,
    density: Float,
    label: String,
) {
    val tile = remember(pattern, color, surfaceColor, density) {
        seriesPatternTile(pattern, color, surfaceColor, density).asImageBitmap()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            bitmap = tile,
            contentDescription = null,
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
