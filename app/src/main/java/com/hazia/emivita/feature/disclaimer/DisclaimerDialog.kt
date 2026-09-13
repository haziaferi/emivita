package com.hazia.emivita.feature.disclaimer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.hazia.emivita.R

/**
 * The medical disclaimer, once, before anything else (§9 #29; Appendix F
 * MT-21): not a medical device, not a substitute for the prescriber, and a
 * reminder can be missed on any phone. Cannot be dismissed; Continue needs
 * the checkbox. One node per control (the Stage B rule).
 */
@Composable
fun DisclaimerDialog(onAccept: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.disclaimer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().toggleable(value = understood, role = Role.Checkbox) { understood = it }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Spacer(Modifier.height(0.dp))
                    Text(stringResource(R.string.disclaimer_understood), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(enabled = understood, onClick = onAccept) { Text(stringResource(R.string.disclaimer_continue)) } },
    )
}
