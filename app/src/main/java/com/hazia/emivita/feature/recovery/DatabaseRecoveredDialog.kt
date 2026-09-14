package com.hazia.emivita.feature.recovery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.hazia.emivita.R

/**
 * Shown once, before the lock, when the launch had to set an unopenable
 * database aside and start empty (spec §9 #32). Cannot be dismissed by a
 * tap outside or the back gesture: an app that silently starts empty is the
 * failure this exists to prevent, so the person answers it. Two answers,
 * both clear the notice: go to Data & Backup to restore, or carry on empty.
 */
@Composable
fun DatabaseRecoveredDialog(onRestore: () -> Unit, onContinueEmpty: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.recovery_title)) },
        text = {
            Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.recovery_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onRestore) { Text(stringResource(R.string.recovery_restore)) } },
        dismissButton = { TextButton(onClick = onContinueEmpty) { Text(stringResource(R.string.recovery_continue_empty)) } },
    )
}
