package com.hazia.emivita.core.scheduling

/**
 * The single interface between the data layer and the scheduling engine.
 * No caller outside this package should touch [AlarmScheduler] or
 * [SchedulingEngine] directly -- everything goes through here, so there's
 * one place that decides "does this change require touching an alarm".
 *
 * Widened from the original single-method placeholder now that the real
 * engine (this batch) exists: dose resolution isn't the only thing that
 * can change a reminder's next alarm -- creating, editing, enabling/
 * disabling, and deleting a reminder all can too, and a device reboot
 * clears every exact alarm AlarmManager was holding.
 */
interface SchedulingCoordinator {
    /** Called by DoseEventRepository after a dose event's status changes. */
    suspend fun onDoseResolved(reminderId: Long)

    /** Called by ReminderRepository after a reminder is created, its config
     *  changed, or its enabled flag flipped -- anything that could move its
     *  next occurrence. [notBeforeEpochMillis] is required (not defaulted to
     *  "now" internally) so the alarm-fired path can pass a floor strictly
     *  after the slot that just fired, rather than risking a same-slot
     *  repeat if this happens to run within the same clock millisecond. */
    suspend fun onReminderChanged(reminderId: Long, notBeforeEpochMillis: Long)

    /** Called by ReminderRepository after a reminder is deleted, to cancel
     *  any alarm still pending for it. */
    suspend fun onReminderRemoved(reminderId: Long)

    /** Recomputes and re-schedules every enabled reminder's next alarm.
     *  Called after boot / app update, since AlarmManager forgets every
     *  exact alarm across both. */
    suspend fun rescheduleAll()

    /** Called by DoseEventRepository.resolve() -- a dose that was being
     *  nagged about (spec §3/§4 `repeatOnUnanswered`) just got a real
     *  answer, so any pending nag re-fire for its reminder should stop. */
    suspend fun cancelNag(reminderId: Long)
}
