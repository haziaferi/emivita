package com.hazia.emivita.ui.form

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R

/** Confirm and dismiss at least this far apart (the owner's rule, §7 R124 C5; §7 R125 for dialogs). */
internal val PairGap = 16.dp

/**
 * The Salva/Annulla arrangement (C5), shared by the form's foot and every dialog: [dismiss] and
 * [confirm] the same width, side by side (§7 R126: "Shorter labels, side by side"), confirm on the
 * right edge, dismiss to its left [PairGap] apart. When side by side would cut a label (large font
 * on a narrow phone), the two stack right-aligned, confirm on top, [PairGap] apart (§7 R129: "B:
 * stack when cut"). A [tertiary] text action sits at the left edge on the same line when it fits,
 * else on a line above; children: dismiss (if [hasDismiss]), confirm, tertiary (if [hasTertiary]).
 */
@Composable
internal fun ActionPairLayout(hasDismiss: Boolean, hasTertiary: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { ms, c ->
        val gap = PairGap.roundToPx()
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val pair = ms.take(if (hasDismiss) 2 else 1)
        val natural = pair.map { it.maxIntrinsicWidth(c.maxHeight) }
        val room = if (c.hasBoundedWidth) c.maxWidth else (natural.sum() + gap)
        val half = (room - gap) / 2
        val t = if (hasTertiary) ms.last().measure(loose.copy(maxWidth = room)) else null
        if (hasDismiss && natural.max() > half) {
            val sw = minOf(natural.max(), room)
            val (dismiss, confirm) = pair.map { it.measure(loose.copy(minWidth = sw, maxWidth = sw)) }
            val above = t?.let { it.height + 8.dp.roundToPx() } ?: 0
            return@Layout layout(room, above + confirm.height + gap + dismiss.height) {
                t?.place(0, 0)
                confirm.place(room - sw, above)
                dismiss.place(room - sw, above + confirm.height + gap)
            }
        }
        val w = if (hasDismiss) minOf(natural.max(), half) else minOf(natural.single(), room)
        val ps = pair.map { it.measure(loose.copy(minWidth = w, maxWidth = w)) }
        val pairW = if (hasDismiss) 2 * w + gap else w
        val inline = t == null || t.width + gap + pairW <= room
        val pairH = ps.maxOf { it.height }
        val above = if (t != null && !inline) t.height + 8.dp.roundToPx() else 0
        val rowH = if (t != null && inline) maxOf(pairH, t.height) else pairH
        layout(room, above + rowH) {
            val confirmX = room - w
            val confirm = ps.last()
            confirm.place(confirmX, above + (rowH - confirm.height) / 2)
            if (hasDismiss) ps[0].place(confirmX - gap - w, above + (rowH - ps[0].height) / 2)
            t?.place(0, if (inline) (rowH - t.height) / 2 else 0)
        }
    }
}

/** The confirm button: the primary tier, filled; in the error colour when it destroys something. */
@Composable
internal fun ConfirmButton(label: String, enabled: Boolean, destructive: Boolean, onClick: () -> Unit) {
    val colors = if (destructive) {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(onClick = onClick, enabled = enabled, colors = colors, modifier = Modifier.heightIn(min = PrimaryControlHeight)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The dismiss button: the secondary tier, tonal, more desaturated than the confirm beside it. */
@Composable
internal fun DismissButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = SecondaryControlHeight)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A dialog's confirm/dismiss pair (§7 R125, the owner: "Yes, same order and look"): the form foot's
 * rule without the foot, passed as the AlertDialog's `confirmButton` with no `dismissButton`, so the
 * dialog keeps its own place. [onDismiss] null leaves the confirm alone; [tertiary] is a 38 dp text
 * action (a dialog's delete) at the left edge.
 */
@Composable
fun DialogActionPair(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)?,
    dismissLabel: String = stringResource(R.string.common_cancel),
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    tertiary: String? = null,
    onTertiary: () -> Unit = {},
    tertiaryEnabled: Boolean = true,
    tertiaryDestructive: Boolean = true,
) {
    ActionPairLayout(hasDismiss = onDismiss != null, hasTertiary = tertiary != null) {
        if (onDismiss != null) DismissButton(dismissLabel, enabled = true, onClick = onDismiss)
        ConfirmButton(confirmLabel, confirmEnabled, destructive, onConfirm)
        if (tertiary != null) {
            TextButton(onClick = onTertiary, enabled = tertiaryEnabled, modifier = Modifier.heightIn(min = TertiaryControlHeight)) {
                Text(
                    tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (tertiaryDestructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        }
    }
}
