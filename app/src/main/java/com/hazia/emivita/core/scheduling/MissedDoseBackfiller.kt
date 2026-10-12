package com.hazia.emivita.core.scheduling

import android.util.Log
import com.hazia.emivita.core.data.config.decodeReminderConfig
import com.hazia.emivita.core.data.dao.ReminderDao
import com.hazia.emivita.core.data.repository.DoseEventRepository
import com.hazia.emivita.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Doses the app never fired (phone off, force-stop, a dropped alarm) become
 * Missed rows, so a bad week no longer looks like a quiet one (spec §9 #26,
 * Appendix F DS-01, Dosage's `addMissedItems`). The window runs from
 * [SettingsRepository.appLastSeenEpochMillis] to a minute ago, capped at
 * [MAX_LOOKBACK_MILLIS], and only FixedTime and ON_REMINDED intervals and
 * windows are projected, since ON_RESOLVED chains and LinkedFollowing children
 * depend on answers nobody gave. Rows go in PENDING through the alarm path's
 * idempotent materialisation, so nothing is doubled, and
 * [com.hazia.emivita.core.notification.UnansweredDoseReposter] decides which
 * deserve a card.
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
