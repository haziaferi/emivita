package com.hazia.emivita.core.scheduling

import com.hazia.emivita.core.data.config.ExpirationTriggerFrequency
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
 * LowStockAlert/ExpirationAlert triggering (spec §3/§9 #8) -- evaluated as
 * the last step of [OccurrenceProjectionCache]'s recompute pass rather than
 * a separate periodic job, per spec: "No separate WorkManager polling job;
 * the cache's existing DB-change and alarm-fired triggers keep it current on
 * their own." Reuses the medicine+reminder snapshot the cache already
 * fetched for its own projection, so this adds no extra query.
 *
 * **Trigger-mode semantics** (not spelled out beyond the mode names in spec,
 * resolved here consistently with how the cache itself is triggered -- by
 * real data-changing events, not a clock):
 *  - `ALWAYS_WHEN_BELOW` / no `DAILY`-style gate for expiration: notify on
 *    every recompute this reminder is evaluated in while the condition
 *    holds. Bounded by real events (debounced at the cache's own 1s), not a
 *    timer -- it fires once per actual stock/reminder change while low,
 *    which is the point of "Always". That bound only holds because the
 *    `lastAlertNotifiedEpochDay` write below is guarded: an unconditional
 *    write is itself a `reminders` change, so it re-triggered the very
 *    recompute that called this, and "bounded by real events" quietly
 *    became a 1s loop. See the comment at that write, and §7 R23.
 *  - `ONCE_WHEN_BELOW` / `ExpirationTriggerFrequency.ONCE`: notify the first
 *    time the condition is met, then go silent. LowStockAlert specifically
 *    rearms itself once stock rises back above threshold (a real restock),
 *    so re-crossing notifies again; ExpirationAlert has no such "resolved"
 *    state to rearm from, so ONCE really means once, ever, for that reminder.
 *  - `DAILY_WHEN_BELOW` / `ExpirationTriggerFrequency.DAILY`: at most one
 *    notification per calendar day while the condition holds.
 *
 * [Reminder.lastAlertNotifiedEpochDay] is the one piece of state all three
 * modes share to implement this without a second table.
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
                    is ReminderConfig.ExpirationAlert -> evaluateExpiration(entry.medicine, reminder, config, todayEpochDay)
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
            LowStockTriggerMode.DAILY_WHEN_BELOW -> reminder.lastAlertNotifiedEpochDay == null || reminder.lastAlertNotifiedEpochDay < todayEpochDay
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

    private suspend fun evaluateExpiration(medicine: Medicine, reminder: Reminder, config: ReminderConfig.ExpirationAlert, todayEpochDay: Long) {
        val expirationEpochDay = medicine.stockInfo.expirationDateEpochDay ?: return
        val isDue = todayEpochDay >= expirationEpochDay - config.leadDays
        if (!isDue) return

        val shouldNotify = when (config.frequency) {
            ExpirationTriggerFrequency.ONCE -> reminder.lastAlertNotifiedEpochDay == null
            ExpirationTriggerFrequency.DAILY -> reminder.lastAlertNotifiedEpochDay == null || reminder.lastAlertNotifiedEpochDay < todayEpochDay
        }
        if (!shouldNotify) return

        notificationBuilder.postExpiration(medicine, reminder, expirationEpochDay)
        reminderDao.updateLastAlertNotifiedEpochDay(reminder.id, todayEpochDay)
    }
}
