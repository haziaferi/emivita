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
import com.hazia.emivita.ui.theme.EmivitaTheme
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.stacked
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
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
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.pluralStringResource
import com.hazia.emivita.core.format.LocaleFormats

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

/** How the chart meets the width (§9 #34): every day of the window fitted to
 *  it, no zoom, no scroll. Pinned as values so a refactor that drops one of
 *  them fails `AdherenceChartFitTest`, not the owner's eye on the phone. */
internal object AdherenceChartFit {
    const val ZOOM_ENABLED = false
    val INITIAL_ZOOM: Zoom = Zoom.Content
    const val SCROLL_ENABLED = false
}

/**
 * Stacked daily bar chart of taken/skipped/missed per day across the visible
 * History window, because the data is a trend over time, which a pie cannot
 * show (spec §9 #6/T5), and the pinned `com.patrykandpatrick.vico:compose-m3:2.1.0`
 * has no pie layer anyway (Vico 2.x has line/column/candlestick only).
 *
 * Missed uses `attention`, NOT `error`: louder than a deliberate Skip but a
 * fact about last Tuesday, not an emergency, while Skip is drawn with
 * near-achromatic `outline` (§7 R76), which also holds the series apart under
 * colour-vision deficiency (see Color.kt).
 *
 * **Colour is not load-bearing here (spec §7 R54/R55)**: with colour-vision
 * deficiency simulated the taken/missed pair falls under the categorical floor
 * in 4 of 16 registers, so each series also carries a [SeriesPattern], the
 * legend teaches the pattern, the stack order is stated, and any one of
 * texture, position or colour alone identifies a series.
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
    val skippedColor = MaterialTheme.colorScheme.outline
    val missedColor = EmivitaTheme.colors.attention
    val surfaceColor = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current.density
    // Tiles are rebuilt only when a colour or the density actually changes --
    // allocating three bitmaps per recomposition would be a real cost on a
    // screen that also scrolls a list.
    val takenFill = rememberPatternFill(SeriesPattern.SOLID, takenColor, surfaceColor, density)
    val skippedFill = rememberPatternFill(SeriesPattern.DIAGONAL, skippedColor, surfaceColor, density)
    val missedFill = rememberPatternFill(SeriesPattern.CROSS_HATCH, missedColor, surfaceColor, density)
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisLineColor = MaterialTheme.colorScheme.outlineVariant

    val dateFormatter = remember(counts) {
        CartesianValueFormatter { _, x, _ ->
            counts.getOrNull(x.toInt())?.date?.let { LocaleFormats.numericMonthDay(it) }.orEmpty()
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.adherence_adherence_trend), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // The chart draws to a canvas, so a screen reader gets nothing
            // from it. This is what a sighted reader takes from it at a
            // glance; per-day detail is what the Table view is for.
            val days = counts.size
            val chartDescription = stringResource(
                R.string.a11y_adherence_chart,
                pluralStringResource(
                    R.plurals.history_counts_summary, days,
                    counts.sumOf { it.taken }, counts.sumOf { it.skipped }, counts.sumOf { it.missed }, days,
                ),
            )
            CartesianChartHost(
                modifier = Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = chartDescription },
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
                // Fit all thirty days to the width and do not scroll (§9 #34).
                // Vico's default is a layer wider than the screen opened at its
                // *start*, so the recent days -- the ones with any bars -- sat
                // off-screen to the right on every phone since §7 R55, and the
                // owner's "chart texture" check could never have seen one.
                zoomState = rememberVicoZoomState(zoomEnabled = AdherenceChartFit.ZOOM_ENABLED, initialZoom = AdherenceChartFit.INITIAL_ZOOM),
                scrollState = rememberVicoScrollState(scrollEnabled = AdherenceChartFit.SCROLL_ENABLED),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendSwatch(SeriesPattern.SOLID, takenColor, surfaceColor, density, stringResource(R.string.status_taken))
                LegendSwatch(SeriesPattern.DIAGONAL, skippedColor, surfaceColor, density, stringResource(R.string.status_skipped))
                LegendSwatch(SeriesPattern.CROSS_HATCH, missedColor, surfaceColor, density, stringResource(R.string.status_missed))
            }
            Spacer(Modifier.height(6.dp))
            // The third channel, said out loud. The stack order is fixed, so
            // position identifies a series even where a 6dp bar is too narrow
            // for its texture to read.
            Text(
                stringResource(R.string.adherence_each_bar_stacks_taken_at_the_bottom_then),
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
