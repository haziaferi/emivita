package com.hazia.emivita.feature.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * "Snooze for how long?" (spec §9 #25; medTimer's custom snooze). Opened by
 * the notification's Snooze action when the Alarms & Snooze setting says
 * "ask each time"; otherwise that action snoozes for the saved duration
 * without any screen. A dialog-themed activity because a notification action
 * cannot show a dialog itself. Quick choices plus a free field; the pick is
 * the same [com.hazia.emivita.core.scheduling.AlarmScheduler.scheduleSnooze]
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
                var custom by remember { mutableStateOf("") }
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
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(stringResource(R.string.snooze_picker_title)) },
                    text = {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                choices.forEach { m ->
                                    AssistChip(onClick = { snooze(m) }, label = { Text(stringResource(R.string.snooze_picker_minutes, m)) })
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = custom,
                                onValueChange = { custom = it },
                                label = { Text(stringResource(R.string.snooze_picker_custom)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        val minutes = custom.toIntOrNull()?.takeIf { it > 0 }
                        TextButton(enabled = minutes != null, onClick = { minutes?.let { snooze(it) } }) {
                            Text(stringResource(R.string.common_snooze))
                        }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
        }
    }
}
