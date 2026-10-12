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
 * database aside and start empty (spec §9 #32), and not dismissable by a tap
 * outside or back, since silently starting empty is the failure it prevents.
 * Both answers (restore from Data & Backup, or carry on empty) clear the notice.
 * A lost key gets its own words (§7 R83 (f)), because that file can never be
 * read and "set aside, not deleted" would promise too much.
 */
@Composable
fun DatabaseRecoveredDialog(keyLost: Boolean, onRestore: () -> Unit, onContinueEmpty: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(if (keyLost) R.string.recovery_key_lost_title else R.string.recovery_title)) },
        text = {
            Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(if (keyLost) R.string.recovery_key_lost_body else R.string.recovery_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onRestore) { Text(stringResource(R.string.recovery_restore)) } },
        dismissButton = { TextButton(onClick = onContinueEmpty) { Text(stringResource(R.string.recovery_continue_empty)) } },
    )
}
