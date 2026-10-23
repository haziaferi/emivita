package com.hazia.emivita.feature.medicines

import android.content.res.Resources
import com.hazia.emivita.R
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.RepeatPattern
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.format.LocaleFormats
import com.hazia.emivita.core.format.formatDecimalLocalized
import com.hazia.emivita.core.format.formatDoseWithUnit
import com.hazia.emivita.core.format.labelRes
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** The parts of a reminder row's main line are joined as the notification joins its own. */
private const val PART_SEPARATOR = " · "

/**
 * A reminder row's main line on the medicine page (§7 R120, the owner's pick of
 * 2026-10-23, as medTimer's reminder row reads): when · how often · how much,
 * "08:00 · Daily · 1 drops", where six reminders all read "Fixed Time". Null
 * for an alert reminder, whose row keeps its kind as its text.
 *
 * [parent] is the reminder a following dose follows, named by its time when it
 * has one and by its kind otherwise; [doseUnit] is the medicine's.
 */
fun reminderRowText(reminder: Reminder, doseUnit: String?, parent: Reminder?, resources: Resources): String? {
    fun dose(amount: Double) = formatDoseWithUnit(amount, doseUnit, resources)
    fun everyHours(minutes: Int) = resources.getString(R.string.reminder_row_every_hours, formatDecimalLocalized(minutes / 60.0))
    val parts = when (val config = decodeReminderConfig(reminder.configJson)) {
        is ReminderConfig.FixedTime -> listOf(LocaleFormats.timeOfDay(config.hour, config.minute), repeatText(config.repeatPattern, resources), dose(config.dose))
        is ReminderConfig.RepeatingInterval -> listOf(everyHours(config.intervalMinutes), dose(config.dose))
        is ReminderConfig.DailyWindow -> listOf(
            resources.getString(
                R.string.reminder_label_at,
                LocaleFormats.timeOfDay(config.windowStartHour, config.windowStartMinute),
                LocaleFormats.timeOfDay(config.windowEndHour, config.windowEndMinute),
            ),
            everyHours(config.intervalMinutes),
            dose(config.dose),
        )
        is ReminderConfig.LinkedFollowing -> {
            val minutes = resources.getString(R.string.snooze_picker_minutes, config.delayMinutes)
            val after = parent?.let { resources.getString(R.string.reminder_row_after_parent, minutes, parentName(it, resources)) } ?: minutes
            listOf(after, dose(config.dose))
        }
        is ReminderConfig.LowStockAlert, is ReminderConfig.ExpirationAlert -> return null
    }
    return parts.joinToString(PART_SEPARATOR)
}

/** The add-reminder form's own words for each pattern, with its number filled in. */
private fun repeatText(pattern: RepeatPattern, resources: Resources): String = when (pattern) {
    RepeatPattern.Daily -> resources.getString(R.string.common_daily)
    is RepeatPattern.SpecificDaysOfWeek ->
        if (pattern.days.isEmpty()) resources.getString(R.string.add_reminder_specific_days)
        else pattern.days.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    is RepeatPattern.EveryNDays -> resources.getQuantityString(R.plurals.reminder_row_every_n_days, pattern.n, pattern.n)
    // "21 days on, 7 off" (§7 R121, the owner's "Add counts").
    is RepeatPattern.CyclicActivePause -> resources.getQuantityString(R.plurals.reminder_row_cycle_days, pattern.activeDays, pattern.activeDays, pattern.pauseDays)
}

private fun parentName(parent: Reminder, resources: Resources): String =
    when (val config = decodeReminderConfig(parent.configJson)) {
        is ReminderConfig.FixedTime -> LocaleFormats.timeOfDay(config.hour, config.minute)
        else -> resources.getString(parent.type.labelRes())
    }
