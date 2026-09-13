package com.hazia.emivita.core.scheduling

import android.util.Log
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Doses the app never fired become Missed rows (spec §9 #26, the owner's
 * choice; Appendix F DS-01, Dosage's `addMissedItems`). A phone left off for
 * three days, a force-stop, an alarm the OS dropped: until now the slots
 * that passed meanwhile were absent from History and from the adherence
 * figure, so a bad week looked like a quiet one.
 *
 * Window: from the last moment the app is known to have been alive
 * ([SettingsRepository.appLastSeenEpochMillis], stamped on every launch,
 * boot and alarm fire) to a minute ago, capped at [MAX_LOOKBACK_MILLIS]. A
 * reminder paused and resumed while the app was alive leaves no gap, which
 * is what the stamp is for. Only history-independent types are projected --
 * FixedTime and ON_REMINDED intervals and windows; an ON_RESOLVED chain and
 * a LinkedFollowing child depend on answers nobody gave, so they get no
 * invented slots. Rows are inserted PENDING through the same idempotent
 * materialisation the alarm path uses, so a slot the alarm did reach is
 * never doubled. Nothing is notified here: [com.hazia.emivita.core.notification.UnansweredDoseReposter]
 * decides, with its own day-long window, which of these deserve a card.
 */
class MissedDoseBackfiller(
    private val reminderDao: ReminderDao,
    private val schedulingEngine: SchedulingEngine,
    private val doseEventRepository: DoseEventRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun backfill(nowEpochMillis: Long = System.currentTimeMillis()): Int {
        val lastSeen = settingsRepository.appLastSeenEpochMillis.first()
        val from = maxOf(lastSeen ?: (nowEpochMillis - MAX_LOOKBACK_MILLIS), nowEpochMillis - MAX_LOOKBACK_MILLIS)
        val to = nowEpochMillis - GRACE_MILLIS
        var inserted = 0
        if (to > from) {
            val weekend = schedulingEngine.readWeekendMode()
            for (reminder in reminderDao.getAllEnabled()) {
                val config = runCatching { decodeReminderConfig(reminder.configJson) }.getOrNull() ?: continue
                if (!config.reschedulesImmediatelyOnFire()) continue
                if (config.doseAmountOrNull() == null) continue
                val start = maxOf(from, reminder.createdAtEpochMillis)
                if (to <= start) continue
                for (occurrence in schedulingEngine.occurrencesFor(reminder, start, to, weekendMode = weekend)) {
                    val id = runCatching {
                        doseEventRepository.materializeIfAbsent(reminder.id, reminder.medicineId, occurrence.epochMillis, occurrence.doseAmount)
                    }.onFailure { Log.w(TAG, "Backfill for reminder ${reminder.id} at ${occurrence.epochMillis} failed", it) }.getOrNull()
                    if (id != null) inserted++
                }
            }
        }
        settingsRepository.setAppLastSeenEpochMillis(nowEpochMillis)
        return inserted
    }

    companion object {
        const val MAX_LOOKBACK_MILLIS = 60L * 24 * 60 * 60 * 1000
        /** Slots inside the last minute belong to the alarm path, which may
         *  be firing them right now. */
        const val GRACE_MILLIS = 60_000L
        private const val TAG = "MissedDoseBackfiller"
    }
}
