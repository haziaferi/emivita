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
 * §7 R37), run as the last step of [OccurrenceProjectionCache]'s recompute on
 * the snapshot it already fetched, and limited to `ONCE_WHEN_BELOW` and
 * `ALWAYS_WHEN_BELOW`, where "the instant it changes" beats "tomorrow
 * morning" (medTimer draws the same line), while DAILY and expiration alerts
 * are scheduled reminders with real alarms.
 *
 * `ALWAYS_WHEN_BELOW` notifies on every recompute while low, which stays
 * bounded by real data changes only because the `lastAlertNotifiedEpochDay`
 * write below is guarded: an unconditional write is itself a `reminders`
 * change and turns the recompute into a 1s loop (§7 R23).
 * `ONCE_WHEN_BELOW` goes silent after the first notice and rearms on a
 * restock, and because the calculators in ReminderScheduling.kt read the same
 * [Reminder.lastAlertNotifiedEpochDay], that rearm re-arms the DAILY alarm too.
 */
class LowStockExpirationAlertEvaluator(
    private val reminderDao: ReminderDao,
    private val notificationBuilder: AlertNotificationBuilder,
    private val fixedZone: ZoneId? = null,
    private val today: () -> LocalDate = { LocalDate.now(fixedZone ?: ZoneId.systemDefault()) },
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
