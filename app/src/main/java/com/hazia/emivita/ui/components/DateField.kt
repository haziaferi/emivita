package com.hazia.emivita.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R

private val DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Epoch-day date picker, the [com.hazia.emivita.ui.components.TimeOfDayField]
 *  pattern applied to a date instead of a time -- same "button that opens an
 *  AlertDialog-wrapped Material3 picker" shape. Works in epoch days (matches
 *  every date field in the schema -- StockInfo.expirationDateEpochDay,
 *  Reminder.activeFromEpochDay/activeUntilEpochDay, CyclicActivePause's
 *  firstCycleStartEpochDay) rather than epoch millis, so callers never need
 *  their own UTC-noon/timezone conversion at the call site. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, epochDay: Long?, onChange: (Long?) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { showDialog = true }) {
        val display = epochDay?.let { LocalDate.ofEpochDay(it).format(DATE_LABEL_FORMAT) } ?: stringResource(R.string.common_not_set)
        Text(stringResource(R.string.field_label_value, label, display))
    }

    if (showDialog) {
        // DatePicker works in UTC-midnight millis, not epoch days directly --
        // convert at the dialog boundary only, so [epochDay] itself never
        // carries a timezone assumption.
        val initialMillis = epochDay?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = { DatePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    onChange(millis?.let { it / 86_400_000L })
                    showDialog = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
