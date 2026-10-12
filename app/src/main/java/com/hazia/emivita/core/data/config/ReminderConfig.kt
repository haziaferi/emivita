package com.hazia.emivita.core.data.config

import kotlinx.serialization.Serializable

// @Serializable here specifically: Reminder.type is a raw column, not
// JSON-encoded the way configJson is, so the backup/restore snapshot (spec
// §9 #9) round-trips it directly. LowStockTriggerMode and
// ExpirationTriggerFrequency below don't carry the annotation -- they only
// ever appear nested inside an already-@Serializable ReminderConfig, and
// kotlinx generates enum serializers for that case automatically.
@Serializable
enum class ReminderType { FIXED_TIME, REPEATING_INTERVAL, DAILY_WINDOW, LOW_STOCK_ALERT, EXPIRATION_ALERT, LINKED_FOLLOWING }

/** How a REPEATING_INTERVAL or DAILY_WINDOW reminder's clock resets. Mirrors the
 *  reference app's "L'intervallo inizia quando il farmaco viene ricordato" vs.
 *  "...viene preso o saltato" choice. */
@Serializable
enum class IntervalStartBehavior { ON_REMINDED, ON_RESOLVED }

enum class LowStockTriggerMode { ONCE_WHEN_BELOW, ALWAYS_WHEN_BELOW, DAILY_WHEN_BELOW }

/** Default time of day for a newly-created alert reminder. Only a default:
 *  both alert configs carry their own [ReminderConfig.LowStockAlert.hour]/
 *  [ReminderConfig.LowStockAlert.minute], editable per reminder. */
const val DEFAULT_ALERT_HOUR = 9
const val DEFAULT_ALERT_MINUTE = 0
enum class ExpirationTriggerFrequency { ONCE, DAILY }

/** How a FIXED_TIME reminder repeats across days. */
@Serializable
sealed interface RepeatPattern {
    @Serializable data object Daily : RepeatPattern
    @Serializable data class SpecificDaysOfWeek(val days: Set<Int>) : RepeatPattern // 1=Mon..7=Sun
    @Serializable data class EveryNDays(val n: Int, val startEpochDay: Long) : RepeatPattern

    /** Birth-control-style cyclic reminders (spec §3/§9 #14) -- a 4th case on
     *  the existing pattern, not a new top-level ReminderConfig, since they are
     *  still "a time of day + a day-selection rule". Periodic in *both*
     *  directions, active before [firstCycleStartEpochDay] too, a deliberate
     *  divergence from medTimer (§7 R60) pinned by `MedTimerCalculatorParityTest`,
     *  because a user who enters a past cycle start expects the rhythm to hold
     *  on either side of it. See [isActiveOn] for the rule. */
    @Serializable
    data class CyclicActivePause(
        val firstCycleStartEpochDay: Long,
        val activeDays: Int,
        val pauseDays: Int,
    ) : RepeatPattern
}

/** A date is active when `floorMod(date.epochDay - firstCycleStartEpochDay,
 *  activeDays + pauseDays) < activeDays` -- floor-mod specifically (not `%`,
 *  which returns negative remainders for dates before the anchor in
 *  Kotlin/Java), per spec §3. Pulled out as its own function (rather than
 *  inlined in the calculator) so it's independently testable. */
fun RepeatPattern.CyclicActivePause.isActiveOn(epochDay: Long): Boolean {
    require(activeDays >= 1 && pauseDays >= 0) { "CyclicActivePause needs activeDays >= 1 and pauseDays >= 0" }
    val cycleLength = activeDays + pauseDays
    return Math.floorMod(epochDay - firstCycleStartEpochDay, cycleLength.toLong()) < activeDays
}

/** Type-specific reminder configuration. One of these, serialized to JSON, lives in
 *  Reminder.configJson -- see the architecture-review note on why a flexible column
 *  fits this app's actual query patterns better than 5 separate tables. */
@Serializable
sealed interface ReminderConfig {

    @Serializable
    data class FixedTime(
        val hour: Int,
        val minute: Int,
        val dose: Double,
        val repeatPattern: RepeatPattern = RepeatPattern.Daily,
    ) : ReminderConfig

    @Serializable
    data class RepeatingInterval(
        val intervalMinutes: Int,
        val dose: Double,
        val startBehavior: IntervalStartBehavior,
        val anchorEpochMillis: Long, // when the interval clock first started
    ) : ReminderConfig

    @Serializable
    data class DailyWindow(
        val intervalMinutes: Int,
        val dose: Double,
        val windowStartHour: Int,
        val windowStartMinute: Int,
        val windowEndHour: Int,
        val windowEndMinute: Int,
        val startBehavior: IntervalStartBehavior,
    ) : ReminderConfig {
        /** True when the window runs past midnight: an end strictly before the
         *  start (§9 #31, A-20; medTimer's rule). 22:00-06:00 is overnight, and
         *  so is 22:00-00:00; 08:00-08:00 is a same-day window with one slot. */
        val isOvernight: Boolean
            get() = windowEndHour * 60 + windowEndMinute < windowStartHour * 60 + windowStartMinute
    }

    /** [hour]/[minute] is the time of day the alert fires on a day it is due
     *  (spec §7 R37), as medTimer's OUT_OF_STOCK and EXPIRATION_DATE reminders
     *  carry a time that makes them ordinary schedulable alarms.
     *
     *  Defaulted rather than required, so rows written before the field
     *  existed decode unchanged (kotlinx fills an absent key from the default,
     *  so no database migration). 09:00 because an alert is a chore, not a
     *  dose, and wants a civil hour. */
    @Serializable
    data class LowStockAlert(
        val threshold: Double,
        val triggerMode: LowStockTriggerMode,
        val hour: Int = DEFAULT_ALERT_HOUR,
        val minute: Int = DEFAULT_ALERT_MINUTE,
    ) : ReminderConfig

    /** See [LowStockAlert] for why [hour]/[minute] are defaulted. */
    @Serializable
    data class ExpirationAlert(
        val leadDays: Int,
        val frequency: ExpirationTriggerFrequency,
        val hour: Int = DEFAULT_ALERT_HOUR,
        val minute: Int = DEFAULT_ALERT_MINUTE,
    ) : ReminderConfig

    /** Chained "following dose" reminders (spec §3/§9 #14; cyclic ones stay a
     *  RepeatPattern case, R8, spec §7).
     *  [Reminder.parentReminderId] carries which reminder this follows, as a
     *  real FK column rather than inside this config so the engine can query
     *  "children of reminder X" without deserializing every reminder's JSON.
     *  Next occurrence = the parent's most recent event time (fired-at for
     *  ON_REMINDED, resolved-at for ON_RESOLVED) + [delayMinutes] -- see
     *  SchedulingEngine's LinkedFollowing branch and the cascade-reschedule
     *  trigger points in ReminderAlarmReceiver/RealSchedulingCoordinator. */
    @Serializable
    data class LinkedFollowing(
        val delayMinutes: Int, // 1..1440 (24h), matching medTimer exactly
        val dose: Double,
        val startBehavior: IntervalStartBehavior,
    ) : ReminderConfig
}
