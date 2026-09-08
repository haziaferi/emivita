package com.hazia.emivita.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R

/** Reusable "HH:MM" field backed by Material3's native TimePicker in a dialog --
 *  used by both FixedTime's time-of-day and DailyWindow's start/end fields
 *  (spec §6). A label distinguishes multiple instances on the same form
 *  (e.g. "Window start" vs "Window end"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOfDayField(
    label: String,
    hour: Int,
    minute: Int,
    onChange: (hour: Int, minute: Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { showDialog = true }) {
        Text(
            stringResource(
                R.string.field_label_value,
                label,
                "${"%02d".format(hour)}:${"%02d".format(minute)}",
            ),
        )
    }

    if (showDialog) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour, state.minute)
                    showDialog = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
