package com.hazia.emivita.ui.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.ResolverStyle

/** The most lines a free-text field grows to before it scrolls inside (§7 R126). */
const val FreeTextMaxLines = 6

/**
 * The one field pattern (§7 R124, C3): the title always small inside the frame, one line inside
 * the box, help or error below at the field's text inset, one line at font 1.0 (§7 R130). [value] stays the caller's
 * String state; a field that can show an error and has no help keeps its line empty ([reserve]),
 * and an empty [label] draws none (§7 R125). [freeText], text that can be long (allergies, an
 * address, notes), is one line when empty and grows a line at a time as it is typed, up to
 * [FreeTextMaxLines], then scrolls inside, its title still one line (§7 R126).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    help: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    reserve: Boolean = false,
    onDone: (() -> Unit)? = null,
    flagError: Boolean = false,
    freeText: Boolean = false,
) {
    val state = rememberTextFieldState(value)
    val report by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(value)
    // The caller's value wins (a value set from outside shows); every edit is reported back.
    LaunchedEffect(value) { if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value) }
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { if (it != current) report(it) } }
    val below = error ?: help
    val support: (@Composable () -> Unit)? = when {
        // Help and error show in full: one line at font 1.0, as C3 holds them, more at a large font (§7 R130).
        below != null -> { { Text(below) } }
        reserve -> { { Text("", maxLines = 1) } }
        else -> null
    }
    OutlinedTextField(
        state = state,
        // Full width unless the caller gives it a share of a row (FieldPair's weight).
        modifier = Modifier.fillMaxWidth().then(modifier),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = MaterialTheme.typography.bodyLarge,
        labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
        label = if (label.isEmpty()) null else { { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        suffix = suffix?.let { s -> { Text(s, maxLines = 1) } },
        trailingIcon = trailingIcon,
        supportingText = support,
        isError = error != null || flagError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        onKeyboardAction = onDone?.let { done -> KeyboardActionHandler { done() } },
        lineLimits = if (freeText) TextFieldLineLimits.MultiLine(1, FreeTextMaxLines) else TextFieldLineLimits.SingleLine,
    )
}

/** A password in the one field pattern: the text masked, the label small inside the frame (§7 R125). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSecureTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null) {
    val state = rememberTextFieldState(value)
    val report by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(value)
    LaunchedEffect(value) { if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value) }
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { if (it != current) report(it) } }
    OutlinedSecureTextField(
        state = state,
        modifier = Modifier.fillMaxWidth().then(modifier),
        textStyle = MaterialTheme.typography.bodyLarge,
        labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingText = error?.let { e -> { Text(e, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        isError = error != null,
    )
}

private fun formatTimeOfDay(h: Int, m: Int) = "%02d:%02d".format(h, m)

/** "8:00", "08:00", "08.00", "0800", "800": a time of day, or null. */
fun parseTimeOfDay(text: String): Pair<Int, Int>? {
    val m = Regex("^(\\d{1,2})[:.]?(\\d{2})$").find(text.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    return if (h in 0..23 && mi in 0..59) h to mi else null
}

/** A time of day as a writable field; the clock icon at its end opens the time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormTimeField(
    label: String,
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier,
    help: String? = null,
    onError: (Boolean) -> Unit = {},
    onChange: (hour: Int, minute: Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(formatTimeOfDay(hour, minute)) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(hour, minute) { if (parseTimeOfDay(text) != (hour to minute)) text = formatTimeOfDay(hour, minute) }
    val bad = parseTimeOfDay(text) == null
    LaunchedEffect(bad) { onError(bad) }
    FormTextField(
        value = text,
        onValueChange = { t -> text = t; parseTimeOfDay(t)?.let { (h, m) -> if (h != hour || m != minute) onChange(h, m) } },
        label = label,
        modifier = modifier,
        help = help,
        error = if (bad) stringResource(R.string.form_time_invalid) else null,
        keyboardType = KeyboardType.Number,
        reserve = true,
        trailingIcon = {
            IconButton(onClick = { picking = true }) { Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.form_pick_time, label)) }
        },
    )
    if (picking) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                DialogActionPair(stringResource(R.string.common_ok), onConfirm = {
                    onChange(state.hour, state.minute); text = formatTimeOfDay(state.hour, state.minute); picking = false
                }, onDismiss = { picking = false })
            },
        )
    }
}

private fun shortDatePattern(locale: java.util.Locale) =
    DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale).replace(Regex("y+"), "uuuu")

/** The locale's short numeric date with a four-digit year ("dd/MM/uuuu" in Italian), as shown. */
fun numericDateFormatter(locale: java.util.Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(shortDatePattern(locale), locale).withResolverStyle(ResolverStyle.STRICT)

/** A typed date in the locale's numeric order, one or two digits for day and month: blank is no date, the unreadable a failure. */
fun parseNumericDate(text: String, locale: java.util.Locale): Result<Long?> {
    if (text.isBlank()) return Result.success(null)
    val pattern = shortDatePattern(locale).replace(Regex("d+"), "d").replace(Regex("M+"), "M")
    val parser = DateTimeFormatter.ofPattern(pattern, locale).withResolverStyle(ResolverStyle.STRICT)
    return runCatching { LocalDate.parse(text.trim(), parser).toEpochDay() }
}

/** A date as a writable field in the locale's numeric form: empty is no date, the icon opens the picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDateField(
    label: String,
    epochDay: Long?,
    modifier: Modifier = Modifier,
    help: String? = null,
    error: String? = null,
    onError: (Boolean) -> Unit = {},
    onChange: (Long?) -> Unit,
) {
    val locale = Locale.current.platformLocale
    val formatter = remember(locale) { numericDateFormatter(locale) }
    fun show(d: Long?) = d?.let { LocalDate.ofEpochDay(it).format(formatter) } ?: ""
    var text by rememberSaveable { mutableStateOf(show(epochDay)) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(epochDay) {
        val typed = parseNumericDate(text, locale)
        if (typed.isFailure || typed.getOrNull() != epochDay) text = show(epochDay)
    }
    val bad = parseNumericDate(text, locale).isFailure
    LaunchedEffect(bad) { onError(bad) }
    FormTextField(
        value = text,
        onValueChange = { t -> text = t; parseNumericDate(t, locale).onSuccess { d -> if (d != epochDay) onChange(d) } },
        label = label,
        modifier = modifier,
        help = help,
        error = if (bad) stringResource(R.string.form_date_invalid) else error,
        keyboardType = KeyboardType.Number,
        reserve = true,
        trailingIcon = {
            IconButton(onClick = { picking = true }) { Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.form_pick_date, label)) }
        },
    )
    if (picking) {
        val initialMillis = epochDay?.let { LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(label) },
            text = { DatePicker(state = state) },
            confirmButton = {
                DialogActionPair(stringResource(R.string.common_ok), onConfirm = {
                    val d = state.selectedDateMillis?.let { it / 86_400_000L }
                    onChange(d); text = show(d); picking = false
                }, onDismiss = { picking = false })
            },
        )
    }
}

/**
 * Two fields side by side when every title and help line in [texts] fits half the width on one
 * line, else one above the other at full width (a large font). [iconDp] is a trailing icon's room.
 */
@Composable
fun FieldPair(texts: List<String>, iconDp: Dp = 0.dp, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val half = (maxWidth - gap) / 2
        val widest = texts.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
        val need = with(density) { widest.toDp() } + 32.dp + iconDp
        if (need <= half) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                first(Modifier.weight(1f)); second(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                first(Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); second(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A help line under a group of fields, at the fields' supporting-text inset (16 dp in, 4 dp below).
 * [error] takes the help's place, in the error colour, so nothing below moves (§7 R125).
 */
@Composable
fun FieldHelpLine(text: String, error: String? = null) {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 16.dp)) {
        Text(
            error ?: text, style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
