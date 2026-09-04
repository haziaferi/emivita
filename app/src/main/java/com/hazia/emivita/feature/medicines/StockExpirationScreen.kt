package com.hazia.emivita.feature.medicines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.DEFAULT_ALERT_HOUR
import com.hazia.emivita.core.data.config.DEFAULT_ALERT_MINUTE
import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.format.formatDecimal
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.ui.components.DateField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val RUN_OUT_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * Stock & Expiration (spec §6/§9 #1): "one screen, three visually zoned
 * sections -- Current stock (qty/unit/computed runs-out date), Low-stock
 * alert (threshold/frequency), Expiration (date/lead time). One entry
 * point, no duplicated fields elsewhere." That last clause is why
 * `LowStockAlert`/`ExpirationAlert` don't get their own cards in the
 * generic Add-Reminder type picker (see AddReminderScreen.kt) -- this
 * screen IS their one real entry point, both for the underlying
 * `Medicine.stockInfo` fields and for creating/editing the medicine's
 * `LowStockAlert`/`ExpirationAlert` reminder rows.
 *
 * T5 (§6/§7, resolved here): a status-board tone distinct from History's
 * calmer trend-list treatment -- each zoned section gets a tonal container
 * color (primary/tertiary/error-adjacent) rather than a plain list row,
 * since this screen's data is threshold/countdown-driven, not
 * trend-over-time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockExpirationScreen(
    medicineId: Long,
    medicineRepository: MedicineRepository,
    reminderRepository: ReminderRepository,
    doseEventRepository: DoseEventRepository,
    occurrenceProjectionCache: OccurrenceProjectionCache,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }

    // Current stock
    var quantity by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var refillQuantity by remember { mutableStateOf("") }

    // Low-stock alert (mirrors a LowStockAlert Reminder for this medicine)
    var lowStockReminderId by remember { mutableStateOf<Long?>(null) }
    var lowStockEnabled by remember { mutableStateOf(false) }
    var threshold by remember { mutableStateOf("") }
    var triggerMode by remember { mutableStateOf(LowStockTriggerMode.ONCE_WHEN_BELOW) }

    // Expiration alert (mirrors an ExpirationAlert Reminder for this medicine)
    var expirationReminderId by remember { mutableStateOf<Long?>(null) }
    var expirationEnabled by remember { mutableStateOf(false) }
    var expirationDateEpochDay by remember { mutableStateOf<Long?>(null) }
    var leadDays by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(ExpirationTriggerFrequency.ONCE) }

    var saveStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(medicineId) {
        val medicine = medicineRepository.getById(medicineId) ?: return@LaunchedEffect
        quantity = formatDecimal(medicine.stockInfo.quantity)
        unit = medicine.stockInfo.unit ?: ""
        refillQuantity = medicine.stockInfo.refillQuantity?.let { formatDecimal(it) } ?: ""
        expirationDateEpochDay = medicine.stockInfo.expirationDateEpochDay

        val reminders = reminderRepository.observeForMedicine(medicineId).first()
        reminders.find { it.type == ReminderType.LOW_STOCK_ALERT }?.let { reminder ->
            lowStockReminderId = reminder.id
            lowStockEnabled = reminder.enabled
            val config = reminderRepository.decodeConfig(reminder) as? ReminderConfig.LowStockAlert
            if (config != null) {
                threshold = formatDecimal(config.threshold)
                triggerMode = config.triggerMode
            }
        }
        reminders.find { it.type == ReminderType.EXPIRATION_ALERT }?.let { reminder ->
            expirationReminderId = reminder.id
            expirationEnabled = reminder.enabled
            val config = reminderRepository.decodeConfig(reminder) as? ReminderConfig.ExpirationAlert
            if (config != null) {
                leadDays = config.leadDays.toString()
                frequency = config.frequency
            }
        }
        loaded = true
    }

    val runOutDate by occurrenceProjectionCache.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text("Stock & Expiration", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(20.dp))

        if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        // ---- Zone 1: Current stock ------------------------------------
        ZonedSection(title = "Current stock", color = MaterialTheme.colorScheme.primaryContainer) {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text("Quantity") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text("Unit (e.g. tablet, mL)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = refillQuantity,
                onValueChange = { refillQuantity = it },
                label = { Text("Refill amount (one-tap restock)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val runOut = runOutDate.stockRunOutDates[medicineId]
            Text(
                if (runOut != null) "Runs out: ${runOut.format(RUN_OUT_FORMAT)}" else "Runs out: not tracked yet",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = refillQuantity.toDoubleOrNull()?.let { it > 0 } == true,
                onClick = {
                    val amount = refillQuantity.toDoubleOrNull() ?: return@OutlinedButton
                    scope.launch {
                        doseEventRepository.logRefill(medicineId, amount, System.currentTimeMillis())
                        val fresh = medicineRepository.getById(medicineId)
                        quantity = formatDecimal(fresh?.stockInfo?.quantity ?: 0.0)
                        saveStatus = "Refilled."
                    }
                },
            ) { Text("Refill now (+$refillQuantity)") }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Zone 2: Low-stock alert ------------------------------------
        ZonedSection(title = "Low-stock alert", color = MaterialTheme.colorScheme.tertiaryContainer) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Notify when stock runs low", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = lowStockEnabled, onCheckedChange = { lowStockEnabled = it })
            }
            if (lowStockEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it },
                    label = { Text("Threshold") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Notify", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = triggerMode == LowStockTriggerMode.ONCE_WHEN_BELOW,
                        onClick = { triggerMode = LowStockTriggerMode.ONCE_WHEN_BELOW },
                        label = { Text("Once") },
                    )
                    FilterChip(
                        selected = triggerMode == LowStockTriggerMode.DAILY_WHEN_BELOW,
                        onClick = { triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW },
                        label = { Text("Daily") },
                    )
                    FilterChip(
                        selected = triggerMode == LowStockTriggerMode.ALWAYS_WHEN_BELOW,
                        onClick = { triggerMode = LowStockTriggerMode.ALWAYS_WHEN_BELOW },
                        label = { Text("Always") },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Zone 3: Expiration ------------------------------------
        ZonedSection(title = "Expiration", color = MaterialTheme.colorScheme.errorContainer) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Notify as expiration nears", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = expirationEnabled, onCheckedChange = { expirationEnabled = it })
            }
            Spacer(Modifier.height(8.dp))
            DateField("Expiration date", expirationDateEpochDay) { expirationDateEpochDay = it }
            if (expirationEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = leadDays,
                    onValueChange = { leadDays = it },
                    label = { Text("Lead days (notify this many days before)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Notify", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = frequency == ExpirationTriggerFrequency.ONCE,
                        onClick = { frequency = ExpirationTriggerFrequency.ONCE },
                        label = { Text("Once") },
                    )
                    FilterChip(
                        selected = frequency == ExpirationTriggerFrequency.DAILY,
                        onClick = { frequency = ExpirationTriggerFrequency.DAILY },
                        label = { Text("Daily") },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            scope.launch {
                val medicine = medicineRepository.getById(medicineId) ?: return@launch
                medicineRepository.update(
                    medicine.copy(
                        stockInfo = StockInfo(
                            quantity = quantity.toDoubleOrNull() ?: 0.0,
                            unit = unit.ifBlank { null },
                            refillQuantity = refillQuantity.toDoubleOrNull(),
                            productionDateEpochDay = medicine.stockInfo.productionDateEpochDay,
                            expirationDateEpochDay = expirationDateEpochDay,
                        )
                    )
                )

                val thresholdValue = threshold.toDoubleOrNull()
                if (lowStockEnabled && thresholdValue != null) {
                    val existingId = lowStockReminderId
                    val existingReminder = existingId?.let { reminderRepository.getById(it) }
                    // Carry the existing alert time forward rather than rebuilding
                    // the config from this screen alone. This screen owns the
                    // threshold and the trigger mode; it does not own hour/minute,
                    // which spec §7 R37 added and which the reminder's own edit
                    // form sets. Constructing a fresh config here and handing it to
                    // updateConfig -- a full-row write -- silently reset a user's
                    // 20:00 alert to the 09:00 default every time they adjusted the
                    // threshold. Same defect shape as §7 R23 and R27: a partial
                    // object, rebuilt by a screen that does not own all its fields,
                    // overwriting the ones it cannot see.
                    val priorLowStock = existingReminder
                        ?.let { reminderRepository.decodeConfig(it) as? ReminderConfig.LowStockAlert }
                    val config = ReminderConfig.LowStockAlert(
                        threshold = thresholdValue,
                        triggerMode = triggerMode,
                        hour = priorLowStock?.hour ?: DEFAULT_ALERT_HOUR,
                        minute = priorLowStock?.minute ?: DEFAULT_ALERT_MINUTE,
                    )
                    if (existingId != null) {
                        val reminder = existingReminder
                        if (reminder != null) {
                            // One write, not updateConfig + setEnabled: both are
                            // full-row Room @Updates built from the same pre-edit
                            // `reminder`, so the second reverted the first's
                            // configJson (docs/SPEC.md §7 R23).
                            reminderRepository.updateConfig(reminder.copy(enabled = true), config)
                        }
                    } else {
                        lowStockReminderId = reminderRepository.create(medicineId, null, ReminderType.LOW_STOCK_ALERT, config)
                    }
                } else {
                    lowStockReminderId?.let { id ->
                        reminderRepository.getById(id)?.let { reminderRepository.setEnabled(it, false) }
                    }
                }

                val leadDaysValue = leadDays.toIntOrNull()
                if (expirationEnabled && leadDaysValue != null) {
                    val existingId = expirationReminderId
                    val existingReminder = existingId?.let { reminderRepository.getById(it) }
                    // Same reasoning as the low-stock block above.
                    val priorExpiration = existingReminder
                        ?.let { reminderRepository.decodeConfig(it) as? ReminderConfig.ExpirationAlert }
                    val config = ReminderConfig.ExpirationAlert(
                        leadDays = leadDaysValue,
                        frequency = frequency,
                        hour = priorExpiration?.hour ?: DEFAULT_ALERT_HOUR,
                        minute = priorExpiration?.minute ?: DEFAULT_ALERT_MINUTE,
                    )
                    if (existingId != null) {
                        val reminder = existingReminder
                        if (reminder != null) {
                            // One write, not updateConfig + setEnabled: both are
                            // full-row Room @Updates built from the same pre-edit
                            // `reminder`, so the second reverted the first's
                            // configJson (docs/SPEC.md §7 R23).
                            reminderRepository.updateConfig(reminder.copy(enabled = true), config)
                        }
                    } else {
                        expirationReminderId = reminderRepository.create(medicineId, null, ReminderType.EXPIRATION_ALERT, config)
                    }
                } else {
                    expirationReminderId?.let { id ->
                        reminderRepository.getById(id)?.let { reminderRepository.setEnabled(it, false) }
                    }
                }

                onBack()
            }
        }) { Text("Save") }
        saveStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ZonedSection(title: String, color: Color, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = color, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
