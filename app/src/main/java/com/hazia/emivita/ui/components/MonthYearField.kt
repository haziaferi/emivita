package com.hazia.emivita.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hazia.emivita.R
import com.hazia.emivita.ui.form.DialogActionPair
import com.hazia.emivita.ui.form.FormTextField
import com.hazia.emivita.core.format.LocaleFormats
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * "EXP 03/2027" as the pack prints it (§9 #27; HM-09): a month column and a
 * year column, one radio node per option, storing the **last day** of that
 * month as an epoch day -- so "expired" flips on the first of the month
 * after, which is what the print means. The schema keeps day granularity
 * ([com.hazia.emivita.core.data.entity.StockInfo.expirationDateEpochDay]),
 * and a stored day that is not a month end displays as a full date, so
 * switching modes never silently rounds a date the person chose on purpose;
 * drawn in the one field pattern (§7 R125), its calendar icon opens the lists.
 */
@Composable
fun MonthYearField(label: String, epochDay: Long?, modifier: Modifier = Modifier, yearsAhead: Int = 10, onChange: (Long?) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    FormTextField(
        value = epochDay?.let { formatMonthYearOrDate(it) } ?: "",
        onValueChange = {},
        label = label,
        modifier = modifier,
        readOnly = true,
        trailingIcon = {
            IconButton(onClick = { showDialog = true }) { Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.form_pick_date, label)) }
        },
    )
    if (showDialog) {
        val initial = epochDay?.let { YearMonth.from(LocalDate.ofEpochDay(it)) } ?: YearMonth.now()
        var month by remember { mutableIntStateOf(initial.monthValue) }
        var year by remember { mutableIntStateOf(initial.year) }
        val thisYear = YearMonth.now().year
        val years = (thisYear - 1)..(thisYear + yearsAhead)
        // Observable locale (Compose lint NonObservableLocale): month names follow a language change.
        val locale = LocalConfiguration.current.locales[0]
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        (1..12).forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = month == m, role = Role.RadioButton) { month = m }.padding(vertical = 2.dp),
                            ) {
                                RadioButton(selected = month == m, onClick = null)
                                Text(java.time.Month.of(m).getDisplayName(TextStyle.SHORT, locale))
                            }
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        years.forEach { y ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = year == y, role = Role.RadioButton) { year = y }.padding(vertical = 2.dp),
                            ) {
                                RadioButton(selected = year == y, onClick = null)
                                Text(y.toString())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                DialogActionPair(
                    stringResource(R.string.common_ok), onConfirm = { onChange(endOfMonthEpochDay(year, month)); showDialog = false },
                    onDismiss = { showDialog = false },
                    tertiary = stringResource(R.string.common_clear), tertiaryDestructive = false, onTertiary = { onChange(null); showDialog = false },
                )
            },
        )
    }
}

/** The last day of [year]-[month] as an epoch day -- what a "MM/YYYY" print means. */
fun endOfMonthEpochDay(year: Int, month: Int): Long = YearMonth.of(year, month).atEndOfMonth().toEpochDay()

/** True when [epochDay] is the last day of its month -- the only days the
 *  month picker ever writes, and the only ones it shows as a month. */
fun isEndOfMonth(epochDay: Long): Boolean {
    val date = LocalDate.ofEpochDay(epochDay)
    return date.dayOfMonth == YearMonth.from(date).lengthOfMonth()
}

/** "03/2027" for a month end, the full localized date otherwise. */
fun formatMonthYearOrDate(epochDay: Long, locale: Locale = Locale.getDefault()): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return if (isEndOfMonth(epochDay)) "%02d/%d".format(locale, date.monthValue, date.year) else LocaleFormats.monthDayYear(date, locale)
}
