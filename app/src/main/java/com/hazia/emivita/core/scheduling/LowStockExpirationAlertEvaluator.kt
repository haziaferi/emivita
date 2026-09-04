package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.LowStockTriggerMode
import com.hazia.emivita.core.data.config.ReminderConfig
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.MedicineWithReminders
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.entity.Medicine
import com.hazia.emivita.core.data.entity.Reminder
import com.hazia.emivita.core.notification.AlertNotificationBuilder
import java.time.LocalDate
import java.time.ZoneId

/**
 * The *event-driven half* of low-stock alerting (spec §3/§9 #8, narrowed by
 * §7 R37) -- evaluated as the last step of [OccurrenceProjectionCache]'s
 * recompute pass, reusing the medicine+reminder snapshot the cache already
 * fetched, so it adds no extra query.
 *
 * It used to be the *only* path for both alert types, on §9 #8's reasoning
 * that "the cache's existing DB-change and alarm-fired triggers keep it
 * current on their own". That holds for a condition a data change can create
 * -- stock crossing a threshold, which only ever happens because something
 * wrote to the database -- and not for one the calendar creates on its own.
 * Since R37 both alert types are real scheduled reminders with real alarms,
 * and this class keeps only the cases where "the instant it changes" beats
 * "tomorrow morning": `ONCE_WHEN_BELOW` and `ALWAYS_WHEN_BELOW`.
 *
 * That is the same division medTimer draws, arrived at by reading it rather
 * than by inference: `StockHandlingProcessor.checkForThreshold` fires ONCE and
 * ALWAYS inline on every stock decrement, while `OutOfStockScheduling`
 * schedules only DAILY and `ExpirationDateScheduling` schedules both of its
 * modes.
 *
 * **Trigger-mode semantics** (not spelled out beyond the mode names in spec,
 * resolved here consistently with how the cache itself is triggered -- by
 * real data-changing events, not a clock):
 *  - `ALWAYS_WHEN_BELOW`: notify on
 *    every recompute this reminder is evaluated in while the condition
 *    holds. Bounded by real events (debounced at the cache's own 1s), not a
 *    timer -- it fires once per actual stock/reminder change while low,
 *    which is the point of "Always". That bound only holds because the
 *    `lastAlertNotifiedEpochDay` write below is guarded: an unconditional
 *    write is itself a `reminders` change, so it re-triggered the very
 *    recompute that called this, and "bounded by real events" quietly
 *    became a 1s loop. See the comment at that write, and §7 R23.
 *  - `ONCE_WHEN_BELOW`: notify the first time the condition is met, then go
 *    silent, rearming once stock rises back above threshold (a real restock)
 *    so a re-crossing notifies again.
 *  - `DAILY_WHEN_BELOW`: not handled here at all -- see the branch below.
 *
 * [Reminder.lastAlertNotifiedEpochDay] is the one piece of state these modes
 * share to implement this without a second table, and the alarm-driven
 * calculators in ReminderScheduling.kt read the same column, so the restock
 * rearm below re-arms the DAILY alarm too.
 */
class LowStockExpirationAlertEvaluator(
    private val reminderDao: ReminderDao,
    private val notificationBuilder: AlertNotificationBuilder,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(zone) },
) {
    suspend fun evaluate(medicines: List<MedicineWithReminders>) {
        val todayEpochDay = today().toEpochDay()
        for (entry in medicines) {
            for (reminder in entry.reminders) {
                if (!reminder.enabled) continue
                when (val config = decodeReminderConfig(reminder.configJson)) {
                    is ReminderConfig.LowStockAlert -> evaluateLowStock(entry.medicine, reminder, config, todayEpochDay)
                    // Expiration is entirely alarm-driven since spec §7 R37.
                    // It never had an event to ride: a medicine expiring in a
                    // cupboard produces no database writes, so this evaluator
                    // could only ever notice on the next unrelated change.
                    is ReminderConfig.ExpirationAlert -> {}
                    else -> {} // FixedTime/RepeatingInterval/DailyWindow -- SchedulingEngine's concern, not this evaluator's
                }
            }
        }
    }

    private suspend fun evaluateLowStock(medicine: Medicine, reminder: Reminder, config: ReminderConfig.LowStockAlert, todayEpochDay: Long) {
        val isLow = medicine.stockInfo.unit != null && medicine.stockInfo.quantity <= config.threshold
        if (!isLow) {
            // Restocked (or never tracked) -- rearm for the next crossing rather
            // than leaving a stale "already notified" flag from before the refill.
            if (reminder.lastAlertNotifiedEpochDay != null) {
                reminderDao.updateLastAlertNotifiedEpochDay(reminder.id, null)
            }
            return
        }

        val shouldNotify = when (config.triggerMode) {
            LowStockTriggerMode.ALWAYS_WHEN_BELOW -> true
            LowStockTriggerMode.ONCE_WHEN_BELOW -> reminder.lastAlertNotifiedEpochDay == null
            // Alarm-driven since spec §7 R37, and deliberately not *also*
            // handled here: this evaluator runs on data changes, so firing
            // DAILY from both paths would announce on every stock change as
            // well as once a day, which is what ALWAYS already means. This is
            // the same split medTimer draws -- StockHandlingProcessor fires
            // ONCE and ALWAYS inline on a stock decrement, OutOfStockScheduling
            // schedules only DAILY.
            LowStockTriggerMode.DAILY_WHEN_BELOW -> false
        }
        if (!shouldNotify) return

        notificationBuilder.postLowStock(medicine, reminder)
        // Guarded like the rearm path above, and for a sharper reason than
        // avoiding a redundant write: this UPDATE hits `reminders`, which is
        // one of the two tables MedicineDao.observeAllWithReminders() re-fires
        // on, which is what invalidates the projection cache, whose recompute
        // calls back into this evaluator. Writing unconditionally made
        // ALWAYS_WHEN_BELOW self-sustaining at the cache's 1s debounce for as
        // long as a medicine stayed below threshold. See docs/SPEC.md §7 R23.
        if (reminder.lastAlertNotifiedEpochDay != todayEpochDay) {
            reminderDao.updateLastAlertNotifiedEpochDay(reminder.id, todayEpochDay)
        }
    }
}
