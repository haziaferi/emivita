package com.hazia.emivita.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlin.math.roundToInt

/** The secondary tier (§7 R124, C2): grid cells, day toggles and Annulla. */
val SecondaryControlHeight = 42.dp

/**
 * The form's 7-column grid (§7 R124, C1/C6/C8): [cell] dp columns from the content's left edge to
 * its right edge, equal gaps. Each child takes the fewest whole columns its one line needs and a
 * row's spare columns go to its widest children, so every row, the last included, ends on the edge.
 */
@Composable
fun SpanGrid(modifier: Modifier = Modifier, columns: Int = 7, cell: Dp = SecondaryControlHeight, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { ms, c ->
        val w = c.maxWidth
        val cellPx = cell.roundToPx()
        val gap = ((w - columns * cellPx).toFloat() / (columns - 1)).coerceAtLeast(0f)
        fun left(col: Int) = (col * (cellPx + gap)).roundToInt()
        fun right(colEnd: Int) = if (colEnd == columns) w else (left(colEnd - 1) + cellPx)
        val natural = ms.map { it.maxIntrinsicWidth(cellPx) }
        val mins = natural.map { n -> (1..columns).firstOrNull { k -> right(k) - left(0) >= n } ?: columns }
        val rows = mutableListOf<MutableList<Int>>()
        var used = 0
        mins.forEachIndexed { i, k ->
            if (rows.isEmpty() || used + k > columns) { rows += mutableListOf(i); used = k } else { rows.last() += i; used += k }
        }
        val spans = mins.toMutableList()
        for (row in rows) {
            var spare = columns - row.sumOf { spans[it] }
            val order = row.sortedByDescending { natural[it] }
            var j = 0
            while (spare > 0) { spans[order[j % order.size]]++; spare--; j++ }
        }
        val rowGap = gap.roundToInt()
        val placed = rows.map { row ->
            var col = 0
            row.map { i ->
                val x0 = left(col)
                val x1 = right(col + spans[i])
                col += spans[i]
                ms[i].measure(Constraints.fixedWidth(x1 - x0)) to x0
            }
        }
        val heights = placed.map { r -> r.maxOf { it.first.height } }
        val h = heights.sum() + rowGap * (heights.size - 1).coerceAtLeast(0)
        layout(w, h) {
            var y = 0
            placed.forEachIndexed { ri, r ->
                r.forEach { (p, x) -> p.place(x, y + (heights[ri] - p.height) / 2) }
                y += heights[ri] + rowGap
            }
        }
    }
}

/**
 * A cell that toggles (a repeat kind, a weekday): FilterChip's colours and shape, 42 dp high, one
 * line, a text toggle with no checkbox. [modifier] sets its width; a day cell is a 42 dp square.
 */
@Composable
fun GridToggleCell(label: String, on: Boolean, modifier: Modifier = Modifier, padded: Boolean = true, onToggle: (Boolean) -> Unit) {
    val shape = FilterChipDefaults.shape
    Box(
        modifier
            .height(SecondaryControlHeight)
            .clip(shape)
            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(FilterChipDefaults.filterChipBorder(enabled = true, selected = on), shape)
            .toggleable(value = on, role = Role.Checkbox, onValueChange = onToggle)
            .then(if (padded) Modifier.padding(horizontal = 12.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Day toggles on the 7-column grid (C6), rows of seven: the weekdays now, the month's days later.
 * Each cell is a 42 dp square; the first column sits on the content's left edge, the last on its right.
 */
@Composable
fun DayGrid(labels: List<String>, isOn: (Int) -> Boolean, onToggle: (index: Int, on: Boolean) -> Unit) {
    SpanGrid {
        labels.forEachIndexed { i, label ->
            GridToggleCell(label, isOn(i), Modifier.size(SecondaryControlHeight), padded = false) { on -> onToggle(i, on) }
        }
    }
}

/** The weekdays Monday (1) to Sunday (7) in the locale's short names, as [DayGrid] toggles. */
@Composable
fun WeekdayGrid(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val locale = Locale.current.platformLocale
    val labels = (1..7).map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
    DayGrid(labels, isOn = { (it + 1) in selected }) { i, on -> onChange(if (on) selected + (i + 1) else selected - (i + 1)) }
}
