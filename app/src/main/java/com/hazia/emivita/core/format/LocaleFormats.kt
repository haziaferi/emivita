package com.hazia.emivita.core.format

import android.text.format.DateFormat
import java.text.NumberFormat
import java.text.ParsePosition
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Locale-aware rendering for the screens, the widgets and the notification
 * (spec §7 R60 D F3 / E F7 / F 2), while the exports keep invariant formats on
 * purpose, since a CSV column and a clinician's PDF are data. Dates and times
 * are skeletons through [DateFormat.getBestDateTimePattern], never literal
 * patterns, which the pseudolocale sweep cannot see. Time uses `Hm` / `hm` when
 * the app or the phone has chosen 24- or 12-hour and `jm` otherwise (§9 #28), so
 * a US-locale phone set to 24-hour does not read "8:05 PM".
 */
object LocaleFormats {
    /** The user's choice (TimeFormat.H12/H24 → false/true), null for System;
     *  mirrored from the DataStore by AppContainer, the AlarmScheduler.useAlarmClock shape. */
    @Volatile var hour24Override: Boolean? = null
    /** The phone's own 24-hour switch, installed by AppContainer as
     *  `DateFormat.is24HourFormat(context)`; null (the default, and the test
     *  default) means "ask the locale". */
    @Volatile var systemHour24: () -> Boolean? = { null }

    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val hour24 = hour24Override ?: systemHour24()
        val skeletonName = when (hour24) { true -> "Hm"; false -> "hm"; null -> "jm" }
        return Instant.ofEpochMilli(epochMillis).atZone(zone).format(skeleton(skeletonName, locale))
    }

    /** "Fri, Sep 12" / "ven 12 set". */
    fun weekdayMonthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(skeleton("EEEMMMd", locale))

    /** "Friday, Sep 12". */
    fun longWeekdayMonthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(skeleton("EEEEMMMd", locale))

    /** "Sep 12" / "12 set". */
    fun monthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(skeleton("MMMd", locale))

    /** "9/12" / "12/9" -- the chart axis, where space is the constraint. */
    fun numericMonthDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(skeleton("Md", locale))

    /** "Sep 12, 2026" / "12 set 2026". */
    fun monthDayYear(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(skeleton("MMMdyyyy", locale))

    /** "Sep 12, 08:30" -- the History table's compact cell. */
    fun monthDayTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return zoned.toLocalDate().format(skeleton("MMMd", locale)) + ", " + time(epochMillis, zone, locale)
    }

    private fun skeleton(skeleton: String, locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

/** [formatDecimal] for a person rather than a file: `0,5` under it/de,
 *  `0.5` under en. Up to three fraction digits, no grouping (a dose is never
 *  in the thousands and "1.000" would read as one in half of Europe). */
fun formatDecimalLocalized(value: Double, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 3
    }.format(value)

/**
 * The inverse for a text field (§7 R60 E F6). `toDoubleOrNull()` rejected
 * `0,5`, which is what the Decimal keyboard types under an Italian or German
 * locale, so the field went invalid and Save stayed disabled. The invariant
 * form is tried first so `0.5` means a half everywhere regardless of what
 * keyboard produced it; then the locale's own separator, and only when the
 * whole string was consumed -- `NumberFormat.parse` would otherwise read
 * "1a" as 1.
 */
fun parseDecimal(text: String, locale: Locale = Locale.getDefault()): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    t.toDoubleOrNull()?.let { return it }
    val position = ParsePosition(0)
    val parsed = NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = false }.parse(t, position) ?: return null
    return if (position.index == t.length) parsed.toDouble() else null
}
