package com.hazia.emivita.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.ui.theme.EmivitaTheme

/**
 * The affirming action as the reference app draws it (§7 R76): a chip filled
 * with the pale `primaryContainer`, no border. Prendi and "Take now" are
 * this; Salta stays a plain outlined [AssistChip], so the two are told apart
 * by fill and not by colour alone.
 */
@Composable
fun TonalActionChip(onClick: () -> Unit, label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = null,
        modifier = modifier,
    )
}

/**
 * The warning glyph (§7 R76): `attention` beside the text it qualifies -- a
 * missed dose, low stock, an expiry -- and never a fill. Reads to a screen
 * reader as "attention"; the sentence beside it says what about.
 */
@Composable
fun AttentionGlyph(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.Warning,
        contentDescription = stringResource(R.string.a11y_attention),
        tint = EmivitaTheme.colors.attention,
        modifier = modifier.size(16.dp),
    )
}
