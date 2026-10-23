package com.hazia.emivita.feature.alarm

import com.hazia.emivita.ui.form.FormTextField
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import com.hazia.emivita.ui.form.DialogActionPair
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.notification.ReminderNotifications
import androidx.lifecycle.lifecycleScope
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.R
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import com.hazia.emivita.ui.theme.SavedEmivitaTheme
import kotlinx.coroutines.launch
import android.content.Context
import com.hazia.emivita.core.locale.AppLocale

/**
 * "Snooze for how long?" (spec §9 #25, medTimer's custom snooze), opened by
 * the notification's Snooze action when the Alarms & Snooze setting says
 * "ask each time", as a dialog-themed activity because a notification action
 * cannot show a dialog itself. Quick choices plus a free field, and the pick
 * is the same [com.hazia.emivita.core.scheduling.AlarmScheduler.scheduleSnooze]
 * call the one-tap path makes.
 */
class SnoozePickerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val doseEventId = intent.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L)
        if (doseEventId < 0) { finish(); return }
        val container = (application as EmivitaApplication).container
        setContent {
            SavedEmivitaTheme(container.settingsRepository) {
                val choices = listOf(5, 15, 30, 60)
                fun snooze(minutes: Int) {
                    lifecycleScope.launch {
                        val event = container.doseEventRepository.getById(doseEventId)
                        val reminderId = event?.reminderId
                        if (event != null && reminderId != null) {
                            container.alarmScheduler.scheduleSnooze(
                                reminderId = reminderId,
                                doseEventId = doseEventId,
                                originalScheduledEpochMillis = event.scheduledEpochMillis,
                                fireAtEpochMillis = System.currentTimeMillis() + minutes * 60_000L,
                            )
                        }
                        ReminderNotifications.dismiss(this@SnoozePickerActivity, doseEventId)
                        finish()
                    }
                }
                SnoozePickerDialog(choices, onSnooze = ::snooze, onDismiss = { finish() })
            }
        }
    }
}

/** The picker itself (§7 R125: its own composable, so the dialog pair check can show it): quick
 *  choices, a free field, and the Snooze/Annulla pair. */
@androidx.compose.runtime.Composable
internal fun SnoozePickerDialog(choices: List<Int>, onSnooze: (Int) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.snooze_picker_title)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { m ->
                        AssistChip(onClick = { onSnooze(m) }, label = { Text(stringResource(R.string.snooze_picker_minutes, m)) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                FormTextField(custom, { custom = it }, stringResource(R.string.snooze_picker_custom), keyboardType = KeyboardType.Number)
            }
        },
        confirmButton = {
            val minutes = custom.toIntOrNull()?.takeIf { it > 0 }
            DialogActionPair(stringResource(R.string.common_snooze), onConfirm = { minutes?.let(onSnooze) }, onDismiss = onDismiss, confirmEnabled = minutes != null)
        },
    )
}
