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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hazia.emivita.core.data.config.DEFAULT_ALERT_HOUR
import com.hazia.emivita.core.data.config.DEFAULT_ALERT_MINUTE
import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.StockInfo
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.repository.MedicineRepository
import com.hazia.emivita.core.data.repository.ReminderRepository
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.ui.components.DateField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import com.hazia.emivita.R
import androidx.compose.ui.platform.LocalResources
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.ui.components.MonthYearField
import com.hazia.emivita.ui.components.isEndOfMonth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.hazia.emivita.ui.components.AttentionGlyph


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
    // Whether the SAVED row has a unit -- the tracking switch (§3), and what
    // "Refill now" is gated on (§7 R60 C F-2): a refill applied before the
    // unit was saved used to insert a history row and change nothing.
    var trackingSaved by remember { mutableStateOf(false) }
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
    // A column no screen could set (§7 R60 C F-5), the shape R59's rule 18
    // names; carried forward unchanged on every save until now.
    var productionDateEpochDay by remember { mutableStateOf<Long?>(null) }
    // §9 #27: the pack's "EXP 03/2027" by default, an exact day on request;
    // opened-on plus "use within N days" makes the effective expiry the
    // earlier of the two.
    var exactExpiryDay by remember { mutableStateOf(false) }
    var openedDateEpochDay by remember { mutableStateOf<Long?>(null) }
    var useWithinDays by remember { mutableStateOf("") }
    var leadDays by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(ExpirationTriggerFrequency.ONCE) }

    var saveStatus by remember { mutableStateOf<String?>(null) }
    // LocalResources, not LocalContext, for strings resolved inside a
    // callback: Compose's own lint (LocalContextGetResourceValueCall) flags
    // context.getString() on a captured context because it will not see a
    // configuration change; this one does (§9 #19).
    val resources = LocalResources.current

    LaunchedEffect(medicineId) {
        val medicine = medicineRepository.getById(medicineId) ?: return@LaunchedEffect
        quantity = formatDecimalLocalized(medicine.stockInfo.quantity)
        unit = medicine.stockInfo.unit ?: ""
        trackingSaved = medicine.stockInfo.unit != null
        refillQuantity = medicine.stockInfo.refillQuantity?.let { formatDecimalLocalized(it) } ?: ""
        expirationDateEpochDay = medicine.stockInfo.expirationDateEpochDay
        exactExpiryDay = medicine.stockInfo.expirationDateEpochDay?.let { !isEndOfMonth(it) } == true
        productionDateEpochDay = medicine.stockInfo.productionDateEpochDay
        openedDateEpochDay = medicine.stockInfo.openedDateEpochDay
        useWithinDays = medicine.stockInfo.useWithinDaysAfterOpening?.toString() ?: ""

        val reminders = reminderRepository.observeForMedicine(medicineId).first()
        reminders.find { it.type == ReminderType.LOW_STOCK_ALERT }?.let { reminder ->
            lowStockReminderId = reminder.id
            lowStockEnabled = reminder.enabled
            val config = reminderRepository.decodeConfig(reminder) as? ReminderConfig.LowStockAlert
            if (config != null) {
                threshold = formatDecimalLocalized(config.threshold)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.common_stock_expiration), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(20.dp))

        if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        // ---- Zone 1: Current stock ------------------------------------
        ZonedSection(title = stringResource(R.string.stock_section_current)) {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text(stringResource(R.string.stock_expiration_quantity)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text(stringResource(R.string.stock_expiration_unit_e_g_tablet_ml)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = refillQuantity,
                onValueChange = { refillQuantity = it },
                label = { Text(stringResource(R.string.stock_expiration_refill_amount_one_tap_restock)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val runOut = runOutDate.stockRunOutDates[medicineId]
            Text(
                if (runOut != null) stringResource(R.string.stock_runs_out, LocaleFormats.monthDayYear(runOut)) else stringResource(R.string.stock_runs_out_not_tracked),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            val refillAmount = parseDecimal(refillQuantity)?.takeIf { it > 0 }
            OutlinedButton(
                enabled = refillAmount != null && trackingSaved,
                onClick = {
                    val amount = refillAmount ?: return@OutlinedButton
                    scope.launch {
                        val logged = doseEventRepository.logRefill(medicineId, amount, System.currentTimeMillis())
                        val fresh = medicineRepository.getById(medicineId)
                        quantity = formatDecimalLocalized(fresh?.stockInfo?.quantity ?: 0.0)
                        saveStatus = resources.getString(
                            if (logged != null) R.string.stock_refilled_status else R.string.stock_refill_needs_unit,
                        )
                    }
                },
            ) { Text(stringResource(R.string.stock_refill_now, refillQuantity.toString())) }
            if (!trackingSaved) {
                Text(
                    stringResource(R.string.stock_refill_needs_unit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Zone 2: Low-stock alert ------------------------------------
        ZonedSection(title = stringResource(R.string.stock_section_low_stock), warns = true) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.stock_expiration_notify_when_stock_runs_low), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = lowStockEnabled, onCheckedChange = { lowStockEnabled = it })
            }
            if (lowStockEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it },
                    label = { Text(stringResource(R.string.stock_expiration_threshold)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.common_notify), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = triggerMode == LowStockTriggerMode.ONCE_WHEN_BELOW,
                        onClick = { triggerMode = LowStockTriggerMode.ONCE_WHEN_BELOW },
                        label = { Text(stringResource(R.string.common_once)) },
                    )
                    FilterChip(
                        selected = triggerMode == LowStockTriggerMode.DAILY_WHEN_BELOW,
                        onClick = { triggerMode = LowStockTriggerMode.DAILY_WHEN_BELOW },
                        label = { Text(stringResource(R.string.common_daily)) },
                    )
                    FilterChip(
                        selected = triggerMode == LowStockTriggerMode.ALWAYS_WHEN_BELOW,
                        onClick = { triggerMode = LowStockTriggerMode.ALWAYS_WHEN_BELOW },
                        label = { Text(stringResource(R.string.stock_expiration_always)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Zone 3: Expiration ------------------------------------
        ZonedSection(title = stringResource(R.string.stock_section_expiration), warns = true) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.stock_expiration_notify_as_expiration_nears), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = expirationEnabled, onCheckedChange = { expirationEnabled = it })
            }
            Spacer(Modifier.height(8.dp))
            DateField(stringResource(R.string.stock_production_date), productionDateEpochDay) { productionDateEpochDay = it }
            Spacer(Modifier.height(8.dp))
            if (exactExpiryDay) {
                DateField(stringResource(R.string.stock_expiration_expiration_date), expirationDateEpochDay) { expirationDateEpochDay = it }
            } else {
                MonthYearField(stringResource(R.string.stock_expiration_expiration_date), expirationDateEpochDay) { expirationDateEpochDay = it }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(value = exactExpiryDay, role = Role.Switch) { exactExpiryDay = it },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.stock_expiry_exact_day), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = exactExpiryDay, onCheckedChange = null)
            }
            Spacer(Modifier.height(8.dp))
            DateField(stringResource(R.string.stock_opened_on), openedDateEpochDay) { openedDateEpochDay = it }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = useWithinDays,
                onValueChange = { useWithinDays = it },
                label = { Text(stringResource(R.string.stock_use_within_days)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val effective = StockInfo(
                expirationDateEpochDay = expirationDateEpochDay,
                openedDateEpochDay = openedDateEpochDay,
                useWithinDaysAfterOpening = useWithinDays.toIntOrNull()?.takeIf { it > 0 },
            ).effectiveExpirationEpochDay
            if (effective != null && effective != expirationDateEpochDay) {
                Text(
                    stringResource(R.string.stock_effective_expiry, LocaleFormats.monthDayYear(java.time.LocalDate.ofEpochDay(effective))),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expirationEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = leadDays,
                    onValueChange = { leadDays = it },
                    label = { Text(stringResource(R.string.stock_expiration_lead_days_notify_this_many_days_before)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.common_notify), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = frequency == ExpirationTriggerFrequency.ONCE,
                        onClick = { frequency = ExpirationTriggerFrequency.ONCE },
                        label = { Text(stringResource(R.string.common_once)) },
                    )
                    FilterChip(
                        selected = frequency == ExpirationTriggerFrequency.DAILY,
                        onClick = { frequency = ExpirationTriggerFrequency.DAILY },
                        label = { Text(stringResource(R.string.common_daily)) },
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
                            quantity = parseDecimal(quantity) ?: 0.0,
                            unit = unit.ifBlank { null },
                            refillQuantity = parseDecimal(refillQuantity),
                            productionDateEpochDay = productionDateEpochDay,
                            expirationDateEpochDay = expirationDateEpochDay,
                            openedDateEpochDay = openedDateEpochDay,
                            useWithinDaysAfterOpening = useWithinDays.toIntOrNull()?.takeIf { it > 0 },
                        )
                    )
                )
                trackingSaved = unit.isNotBlank()

                val thresholdValue = parseDecimal(threshold)
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
        }) { Text(stringResource(R.string.common_save)) }
        saveStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** One tonal card per zone (§7 R76). Until R76 the three zones were painted
 *  `primaryContainer` / `tertiaryContainer` / `errorContainer`, which were the
 *  base roles themselves -- solid green, brown and red panels with default
 *  text on them (§7 R75). The zone's meaning is in its title: the two that
 *  warn carry the attention glyph beside it. */
@Composable
private fun ZonedSection(title: String, warns: Boolean = false, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (warns) {
                    AttentionGlyph()
                    Spacer(Modifier.width(6.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
