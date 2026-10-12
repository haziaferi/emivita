package com.hazia.emivita.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R

/**
 * Back from a half-edited form asks before dropping the edits (§9 #29,
 * Appendix F HM-40). Installs a [BackHandler] for the system gesture while
 * [dirty], and returns the callback the form's own Cancel/Back control should
 * use, so both paths ask and neither silently discards. When not dirty, the
 * returned callback is [onDiscard] itself.
 */
@Composable
fun unsavedChangesGuard(dirty: Boolean, onDiscard: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { asking = true }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_text)) },
            confirmButton = { TextButton(onClick = { asking = false; onDiscard() }) { Text(stringResource(R.string.unsaved_discard)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.unsaved_keep)) } },
        )
    }
    return if (dirty) { { asking = true } } else onDiscard
}

/** True once any of [current] differs from its value at first composition
 *  (or at the last change of [resetKey] -- for a form whose initial values
 *  arrive asynchronously). The baseline is saved with the form (§7 R83 (a)):
 *  otherwise a rotation made the restored edits the new "unchanged" and Back
 *  dropped them without asking. Compared as stable text, so it saves whatever
 *  the form holds, and a set compares by members, not by order. */
@Composable
fun rememberDirty(resetKey: Any? = Unit, vararg current: Any?): Boolean {
    val now = current.map(::stableForm)
    val initial = rememberSaveable(resetKey) { ArrayList(now) }
    return now != initial
}

internal fun stableForm(value: Any?): String = when (value) {
    is Set<*> -> value.map(::stableForm).sorted().toString()
    is Collection<*> -> value.map(::stableForm).toString()
    else -> value.toString()
}

/** A form's optional colour, saved as its ARGB (§7 R83 (a)); `Color` itself
 *  cannot go into a Bundle. */
val NullableColorSaver: Saver<Color?, Long> = Saver(
    save = { it?.toArgb()?.toLong() ?: NO_COLOR },
    restore = { if (it == NO_COLOR) null else Color(it.toInt()) },
)
private const val NO_COLOR = Long.MIN_VALUE
