package com.hazia.emivita.feature.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.lifecycleScope
import com.hazia.emivita.EmivitaApplication
import com.hazia.emivita.R
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.core.format.maskedMedicineName
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.locale.AppLocale
import com.hazia.emivita.core.notification.EXTRA_DOSE_EVENT_ID
import com.hazia.emivita.core.notification.ReminderNotifications
import com.hazia.emivita.ui.theme.SavedEmivitaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "How much did you take?" from a reminder whose variable-amount flag is on
 * (§7 R109, Appendix E B-45, medTimer's `VariableAmountActivity`), dialog-themed
 * like [SnoozePickerActivity] because a notification action cannot show a
 * dialog. The planned amount is filled in; Take records the typed one through
 * `takeWithAmount`, and leaving the dialog records nothing, so the dose stays due.
 */
class TakeAmountActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val doseEventId = intent.getLongExtra(EXTRA_DOSE_EVENT_ID, -1L)
        if (doseEventId < 0) { finish(); return }
        val container = (application as EmivitaApplication).container
        lifecycleScope.launch {
            val event = container.doseEventRepository.getById(doseEventId)
            val medicine = event?.let { container.medicineRepository.getById(it.medicineId) }
            if (event == null || medicine == null || event.status != DoseStatus.PENDING) {
                // Answered elsewhere meanwhile: nothing to ask, and nothing left to show.
                if (event != null) ReminderNotifications.dismiss(this@TakeAmountActivity, doseEventId)
                finish()
                return@launch
            }
            val hide = container.settingsRepository.hideMedicineNames.first()
            setContent {
                SavedEmivitaTheme(container.settingsRepository) {
                    TakeAmountDialog(maskedMedicineName(medicine.name, hide), event.doseAmount, medicine.stockInfo.unit, onTake = { amount ->
                        lifecycleScope.launch {
                            container.doseEventRepository.takeWithAmount(doseEventId, amount)
                            ReminderNotifications.dismiss(this@TakeAmountActivity, doseEventId)
                            container.notificationBuilder.updateGroupSummary()
                            finish()
                        }
                    }, onDismiss = { finish() })
                }
            }
        }
    }

    companion object {
        fun intent(context: Context, doseEventId: Long): Intent = Intent(context, TakeAmountActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DOSE_EVENT_ID, doseEventId)
        }
    }
}

@Composable
internal fun TakeAmountDialog(name: String, planned: Double, unit: String?, onTake: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(formatDecimalLocalized(planned)) }
    val amount = parseDecimal(text.trim())?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.notif_amount_taken)) },
                suffix = unit?.let { u -> { Text(u) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = amount != null, onClick = { amount?.let(onTake) }) { Text(stringResource(R.string.common_take)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
