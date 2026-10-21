package com.hazia.emivita.feature.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlin.math.ceil
import kotlin.math.max

/** The most rows a tall widget is given to fill (§7 R108, census E-38): two of Glance's columns. */
internal const val MAX_WIDGET_ROWS = 20

/** Glance draws at most ten children in a column and drops the rest without a word. */
private const val GLANCE_COLUMN_CHILDREN = 10

/** The column's padding on each side, in dp. */
private const val WIDGET_PADDING_DP = 12f

/** A row's vertical padding, top and bottom together, in dp. */
private const val ROW_PADDING_DP = 8f

/** The pill's tap area: Android's 48 dp minimum, whatever the pill draws. */
private const val PILL_HIT_DP = 48f

/**
 * The size a widget is composed at, and what its text needs there. A text is
 * laid out as the launcher's TextView lays it out (the platform's
 * [StaticLayout], default typeface, font padding, the sp size through the
 * phone's font scale), so its lines and height are the render's own.
 */
internal class WidgetMetrics(context: Context, val widthDp: Float, val heightDp: Float) {
    private val metrics = context.resources.displayMetrics

    private fun layout(text: String, sizeSp: Float, widthDp: Float, bold: Boolean = false): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val w = (widthDp * metrics.density).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setIncludePad(true).build()
    }

    /** How many lines [text] wraps to in [widthDp] at [sizeSp]. */
    fun lines(text: String, sizeSp: Float, widthDp: Float): Int = max(1, layout(text, sizeSp, widthDp).lineCount)

    fun textDp(text: String, sizeSp: Float, widthDp: Float, bold: Boolean = false) =
        layout(text, sizeSp, widthDp, bold).height / metrics.density

    /** The width a row's texts get beside a pill labelled [pill], or the whole row without one. */
    fun rowTextWidth(pill: String?): Float {
        val pillDp = pill?.let { ceil(layout(it, 12f, widthDp, bold = true).getLineWidth(0) / metrics.density) + 20f } ?: 0f
        return widthDp - 2 * WIDGET_PADDING_DP - pillDp
    }

    /** The header line, its spacer and the column's padding. */
    fun headerDp(header: String) = 2 * WIDGET_PADDING_DP + textDp(header, 14f, widthDp - 2 * WIDGET_PADDING_DP, bold = true) + 4f

    /** A row of [name] over [detail], with a pill labelled [pill] when there is one. */
    fun rowDp(name: String, detail: String, pill: String?): Float {
        val w = rowTextWidth(pill)
        val texts = textDp(name, 13f, w) + textDp(detail, 11f, w)
        return (if (pill != null) max(texts, PILL_HIT_DP) else texts) + ROW_PADDING_DP
    }

    /** How many of the rows, [rowsDp] tall in order, fit under [aboveDp]: at least one when there are any. */
    fun rowsThatFit(aboveDp: Float, rowsDp: List<Float>): Int {
        var left = heightDp - aboveDp
        var n = 0
        for (r in rowsDp) {
            if (r > left) break
            left -= r
            n++
        }
        return n.coerceIn(minOf(1, rowsDp.size), MAX_WIDGET_ROWS)
    }
}

/** The rows, ten to a column, so that none is dropped by Glance's limit. */
@Composable
internal fun <T> WidgetRows(rows: List<T>, row: @Composable (index: Int, item: T) -> Unit) {
    rows.chunked(GLANCE_COLUMN_CHILDREN).forEachIndexed { chunk, items ->
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            items.forEachIndexed { i, item -> row(chunk * GLANCE_COLUMN_CHILDREN + i, item) }
        }
    }
}

/**
 * A row's action: the pill as it was drawn before (§7 R106), inside a box
 * [PILL_HIT_DP] tall that carries the click, so the tap area meets the 48 dp
 * minimum without the pill growing.
 */
@Composable
internal fun WidgetActionPill(label: String, onClick: Action) {
    Box(
        modifier = GlanceModifier.height(PILL_HIT_DP.dp).clickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = GlanceModifier
                .background(GlanceTheme.colors.primary)
                .cornerRadius(8.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}
