package com.hazia.emivita.feature.medicines

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
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
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.core.format.parseDecimal
import com.hazia.emivita.core.scheduling.OccurrenceProjectionCache
import com.hazia.emivita.ui.components.AttentionGlyph
import com.hazia.emivita.ui.components.MonthYearField
import com.hazia.emivita.ui.components.isEndOfMonth
import com.hazia.emivita.ui.components.rememberDirty
import com.hazia.emivita.ui.components.unsavedChangesGuard
import com.hazia.emivita.ui.form.FieldPair
import com.hazia.emivita.ui.form.FormDateField
import com.hazia.emivita.ui.form.FormScaffold
import com.hazia.emivita.ui.form.FormSwitchRow
import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.ui.form.GridToggleCell
import com.hazia.emivita.ui.form.SecondaryControlHeight
import com.hazia.emivita.ui.form.SpanGrid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
 * The three zones are titled groups on the shared form parts (§7 R125),
 * where R76 had them on tonal cards.
 */
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
    // The editable fields are saveable (§7 R83 (a)); once filled from the
    // store they are not refilled, so a rotation keeps the edits. Ids and
    // saved-state facts are re-read every time.
    var filled by rememberSaveable { mutableStateOf(false) }

    // Current stock
    var quantity by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    // Whether the SAVED row has a unit -- the tracking switch (§3), and what
    // "Refill now" is gated on (§7 R60 C F-2): a refill applied before the
    // unit was saved used to insert a history row and change nothing.
    var trackingSaved by remember { mutableStateOf(false) }
    var refillQuantity by rememberSaveable { mutableStateOf("") }

    // Low-stock alert (mirrors a LowStockAlert Reminder for this medicine)
    var lowStockReminderId by remember { mutableStateOf<Long?>(null) }
    var lowStockEnabled by rememberSaveable { mutableStateOf(false) }
    var threshold by rememberSaveable { mutableStateOf("") }
    var triggerMode by rememberSaveable { mutableStateOf(LowStockTriggerMode.ONCE_WHEN_BELOW) }

    // Expiration alert (mirrors an ExpirationAlert Reminder for this medicine)
    var expirationReminderId by remember { mutableStateOf<Long?>(null) }
    var expirationEnabled by rememberSaveable { mutableStateOf(false) }
    var expirationDateEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    // A column no screen could set (§7 R60 C F-5), the shape R59's rule 18
    // names; carried forward unchanged on every save until now.
    var productionDateEpochDay by remember { mutableStateOf<Long?>(null) }
    // §9 #27: the pack's "EXP 03/2027" by default, an exact day on request;
    // opened-on plus "use within N days" makes the effective expiry the
    // earlier of the two.
    var exactExpiryDay by rememberSaveable { mutableStateOf(false) }
    var openedDateEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var useWithinDays by rememberSaveable { mutableStateOf("") }
    var leadDays by rememberSaveable { mutableStateOf("") }
    var frequency by rememberSaveable { mutableStateOf(ExpirationTriggerFrequency.ONCE) }

    var saveStatus by remember { mutableStateOf<String?>(null) }
    // LocalResources, not LocalContext, for strings resolved inside a
    // callback: Compose's own lint (LocalContextGetResourceValueCall) flags
    // context.getString() on a captured context because it will not see a
    // configuration change; this one does (§9 #19).
    val resources = LocalResources.current

    LaunchedEffect(medicineId) {
        val medicine = medicineRepository.getById(medicineId) ?: return@LaunchedEffect
        if (!filled) quantity = formatDecimalLocalized(medicine.stockInfo.quantity)
        if (!filled) unit = medicine.stockInfo.unit ?: ""
        trackingSaved = medicine.stockInfo.unit != null
        if (!filled) refillQuantity = medicine.stockInfo.refillQuantity?.let { formatDecimalLocalized(it) } ?: ""
        if (!filled) expirationDateEpochDay = medicine.stockInfo.expirationDateEpochDay
        if (!filled) exactExpiryDay = medicine.stockInfo.expirationDateEpochDay?.let { !isEndOfMonth(it) } == true
        productionDateEpochDay = medicine.stockInfo.productionDateEpochDay
        if (!filled) openedDateEpochDay = medicine.stockInfo.openedDateEpochDay
        if (!filled) useWithinDays = medicine.stockInfo.useWithinDaysAfterOpening?.toString() ?: ""

        val reminders = reminderRepository.observeForMedicine(medicineId).first()
        reminders.find { it.type == ReminderType.LOW_STOCK_ALERT }?.let { reminder ->
            lowStockReminderId = reminder.id
            if (!filled) lowStockEnabled = reminder.enabled
            val config = reminderRepository.decodeConfig(reminder) as? ReminderConfig.LowStockAlert
            if (config != null) {
                if (!filled) threshold = formatDecimalLocalized(config.threshold)
                if (!filled) triggerMode = config.triggerMode
            }
        }
        reminders.find { it.type == ReminderType.EXPIRATION_ALERT }?.let { reminder ->
            expirationReminderId = reminder.id
            if (!filled) expirationEnabled = reminder.enabled
            val config = reminderRepository.decodeConfig(reminder) as? ReminderConfig.ExpirationAlert
            if (config != null) {
                if (!filled) leadDays = config.leadDays.toString()
                if (!filled) frequency = config.frequency
            }
        }
        filled = true
        loaded = true
    }

    val runOutDate by occurrenceProjectionCache.state.collectAsState()

    // Back asks before dropping an edit (§9 #29's guard, extended in §7 R84).
    // The quantity has its own baseline, reset by a refill, which writes it.
    var refills by rememberSaveable { mutableIntStateOf(0) }
    val quantityDirty = rememberDirty(filled to refills, quantity)
    val restDirty = rememberDirty(
        filled, unit, refillQuantity, lowStockEnabled, threshold, triggerMode, expirationEnabled,
        expirationDateEpochDay, exactExpiryDay, openedDateEpochDay, useWithinDays, leadDays, frequency,
    )
    val back = unsavedChangesGuard(quantityDirty || restDirty, onBack)

    // A typed date that does not read keeps Salva off (§7 R124's date fields).
    var badDates by remember { mutableStateOf(setOf<String>()) }
    fun dateError(key: String): (Boolean) -> Unit = { bad -> badDates = if (bad) badDates + key else badDates - key }

    fun save() {
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
                // Carry the existing alert time forward: this screen owns the
                // threshold and trigger mode but not hour/minute (spec §7 R37),
                // and updateConfig is a full-row write, so a fresh config would
                // reset a 20:00 alert to the 09:00 default (the §7 R23/R27 shape).
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
    }

    // On the shared form parts (§7 R125): Salva saves every field below and leaves; "Refill now"
    // writes at once, as before. Annulla is Back, asking when edited.
    FormScaffold(
        title = stringResource(R.string.common_stock_expiration),
        saveLabel = stringResource(R.string.common_save),
        saveEnabled = loaded && badDates.isEmpty(),
        onSave = ::save,
        onCancel = back,
        onBack = back,
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@FormScaffold
        }

        // ---- Current stock ------------------------------------
        SectionTitle(stringResource(R.string.stock_section_current))
        val quantityLabel = stringResource(R.string.stock_expiration_quantity)
        val unitLabel = stringResource(R.string.stock_expiration_unit_e_g_tablet_ml)
        FieldPair(
            listOf(quantityLabel, unitLabel),
            first = { m -> FormTextField(quantity, { quantity = it }, quantityLabel, m, keyboardType = KeyboardType.Decimal) },
            second = { m -> FormTextField(unit, { unit = it }, unitLabel, m) },
        )
        Spacer(Modifier.height(8.dp))
        val runOut = runOutDate.stockRunOutDates[medicineId]
        FormTextField(
            refillQuantity, { refillQuantity = it }, stringResource(R.string.stock_expiration_refill_amount_one_tap_restock),
            keyboardType = KeyboardType.Decimal,
            help = if (runOut != null) stringResource(R.string.stock_runs_out, LocaleFormats.monthDayYear(runOut)) else stringResource(R.string.stock_runs_out_not_tracked),
        )
        Spacer(Modifier.height(8.dp))
        val refillAmount = parseDecimal(refillQuantity)?.takeIf { it > 0 }
        FilledTonalButton(
            enabled = refillAmount != null && trackingSaved,
            modifier = Modifier.fillMaxWidth().height(SecondaryControlHeight),
            onClick = {
                val amount = refillAmount ?: return@FilledTonalButton
                scope.launch {
                    val logged = doseEventRepository.logRefill(medicineId, amount, System.currentTimeMillis())
                    val fresh = medicineRepository.getById(medicineId)
                    quantity = formatDecimalLocalized(fresh?.stockInfo?.quantity ?: 0.0)
                    refills++
                    saveStatus = resources.getString(
                        if (logged != null) R.string.stock_refilled_status else R.string.stock_refill_needs_unit,
                    )
                }
            },
        ) { Text(stringResource(R.string.stock_refill_now, refillQuantity), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (!trackingSaved) {
            Text(
                stringResource(R.string.stock_refill_needs_unit),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        saveStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium) }

        // ---- Low-stock alert ------------------------------------
        Spacer(Modifier.height(24.dp))
        SectionTitle(stringResource(R.string.stock_section_low_stock), warns = true)
        FormSwitchRow(stringResource(R.string.stock_expiration_notify_when_stock_runs_low), null, lowStockEnabled) { lowStockEnabled = it }
        if (lowStockEnabled) {
            Spacer(Modifier.height(8.dp))
            FormTextField(threshold, { threshold = it }, stringResource(R.string.stock_expiration_threshold), keyboardType = KeyboardType.Decimal)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.common_notify), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            SpanGrid {
                for ((mode, label) in listOf(
                    LowStockTriggerMode.ONCE_WHEN_BELOW to R.string.common_once,
                    LowStockTriggerMode.DAILY_WHEN_BELOW to R.string.common_daily,
                    LowStockTriggerMode.ALWAYS_WHEN_BELOW to R.string.stock_expiration_always,
                )) {
                    GridToggleCell(stringResource(label), triggerMode == mode) { triggerMode = mode }
                }
            }
        }

        // ---- Expiration ------------------------------------
        Spacer(Modifier.height(24.dp))
        SectionTitle(stringResource(R.string.stock_section_expiration), warns = true)
        FormSwitchRow(stringResource(R.string.stock_expiration_notify_as_expiration_nears), null, expirationEnabled) { expirationEnabled = it }
        Spacer(Modifier.height(8.dp))
        FormDateField(stringResource(R.string.stock_production_date), productionDateEpochDay, onError = dateError("production")) { productionDateEpochDay = it }
        Spacer(Modifier.height(8.dp))
        if (exactExpiryDay) {
            FormDateField(stringResource(R.string.stock_expiration_expiration_date), expirationDateEpochDay, onError = dateError("expiry")) { expirationDateEpochDay = it }
        } else {
            MonthYearField(stringResource(R.string.stock_expiration_expiration_date), expirationDateEpochDay) { expirationDateEpochDay = it }
        }
        FormSwitchRow(stringResource(R.string.stock_expiry_exact_day), null, exactExpiryDay) { exactExpiryDay = it; if (!it) badDates = badDates - "expiry" }
        Spacer(Modifier.height(8.dp))
        FormDateField(stringResource(R.string.stock_opened_on), openedDateEpochDay, onError = dateError("opened")) { openedDateEpochDay = it }
        Spacer(Modifier.height(8.dp))
        val effective = StockInfo(
            expirationDateEpochDay = expirationDateEpochDay,
            openedDateEpochDay = openedDateEpochDay,
            useWithinDaysAfterOpening = useWithinDays.toIntOrNull()?.takeIf { it > 0 },
        ).effectiveExpirationEpochDay
        FormTextField(
            useWithinDays, { useWithinDays = it }, stringResource(R.string.stock_use_within_days), keyboardType = KeyboardType.Number,
            help = if (effective != null && effective != expirationDateEpochDay) {
                stringResource(R.string.stock_effective_expiry, LocaleFormats.monthDayYear(java.time.LocalDate.ofEpochDay(effective)))
            } else {
                null
            },
            reserve = true,
        )
        if (expirationEnabled) {
            Spacer(Modifier.height(8.dp))
            FormTextField(leadDays, { leadDays = it }, stringResource(R.string.stock_expiration_lead_days_notify_this_many_days_before), keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.common_notify), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            SpanGrid {
                for ((f, label) in listOf(ExpirationTriggerFrequency.ONCE to R.string.common_once, ExpirationTriggerFrequency.DAILY to R.string.common_daily)) {
                    GridToggleCell(stringResource(label), frequency == f) { frequency = f }
                }
            }
        }
    }
}

/** A section's title on the form's grid (§7 R125: the zones are titled groups, not cards, so their
 *  fields stand on the form's one column and the foot's tonal surface is the only container). The
 *  two that warn carry the attention glyph beside it (§7 R76). */
@Composable
private fun SectionTitle(title: String, warns: Boolean = false) {
    // One node from the glyph to the title, so the title row starts on the form's left edge.
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        if (warns) {
            AttentionGlyph()
            Spacer(Modifier.width(6.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(8.dp))
}
