package com.hazia.emivita.feature.history

import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The three Y-tick candidates the owner had scored (§7 R95): whole-number
 * steps, Vico 3's automatic step, and Vico 2's look. Vico 3 runs the same step
 * rule as Vico 2 but measures a taller label, so it fits fewer; Vico 2's look
 * is therefore its rule with the ten labels Vico 2 fitted on this chart.
 */
internal enum class YTicks { WHOLE, AUTO, VICO2 }

/** The chart's rule (§7 R95, the owner's choice after scoring): a dose count is never 0,4. */
internal val DEFAULT_Y_TICKS = YTicks.WHOLE

/** What the axis drew, for the measuring test: values, the axis height and the label height Vico measured. */
internal data class TickProbe(val values: List<Double>, val minY: Double, val maxY: Double, val axisHeight: Float, val labelHeight: Float)

internal fun yTickPlacer(ticks: YTicks, dataMax: Double, probe: ((TickProbe) -> Unit)? = null): VerticalAxis.ItemPlacer {
    val placer = when (ticks) {
        YTicks.WHOLE -> VerticalAxis.ItemPlacer.step({ 1.0 })
        YTicks.AUTO -> VerticalAxis.ItemPlacer.step()
        YTicks.VICO2 -> FixedValues(VerticalAxis.ItemPlacer.step(), vico2Values(dataMax))
    }
    return if (probe == null) placer else Recording(placer, probe)
}

/** Vico 2's DefaultVerticalAxisItemPlacer.Step with `labels` fitting, as in its source (2.1.0). */
internal fun vico2Values(maxY: Double, labels: Int = 10): List<Double> {
    if (maxY <= 0.0) return listOf(0.0)
    val base = 10.0.pow(floor(log10(maxY)) - 1)
    val minStep = maxY / labels
    val ratio = maxY / base
    val step = ratio.takeIf { it == floor(it) }?.toInt()
        ?.let { n -> (1 until n).filter { n % it == 0 }.firstOrNull { it * base >= minStep } }
        ?.let { it * base }
        ?: (ceil(minStep / base) * base)
    return listOf(0.0) + List((maxY / step).toInt()) { (it + 1) * step }
}

private class FixedValues(private val inner: VerticalAxis.ItemPlacer, private val values: List<Double>) : VerticalAxis.ItemPlacer by inner {
    override fun getLabelValues(context: CartesianDrawingContext, axisHeight: Float, maxLabelHeight: Float, position: Axis.Position.Vertical) = values
    override fun getWidthMeasurementLabelValues(context: CartesianMeasuringContext, axisHeight: Float, maxLabelHeight: Float, position: Axis.Position.Vertical) = values
    override fun getLineValues(context: CartesianDrawingContext, axisHeight: Float, maxLabelHeight: Float, position: Axis.Position.Vertical) = values
}

private class Recording(private val inner: VerticalAxis.ItemPlacer, private val probe: (TickProbe) -> Unit) : VerticalAxis.ItemPlacer by inner {
    override fun getLabelValues(context: CartesianDrawingContext, axisHeight: Float, maxLabelHeight: Float, position: Axis.Position.Vertical): List<Double> {
        val values = inner.getLabelValues(context, axisHeight, maxLabelHeight, position)
        val range = context.ranges.getYRange(position)
        probe(TickProbe(values, range.minY, range.maxY, axisHeight, maxLabelHeight))
        return values
    }
}
