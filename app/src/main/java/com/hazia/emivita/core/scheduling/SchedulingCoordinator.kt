package com.hazia.emivita.core.scheduling

/**
 * The single interface between the data layer and the scheduling engine.
 * No caller outside this package should touch [AlarmScheduler] or
 * [SchedulingEngine] directly -- everything goes through here, so there's
 * one place that decides "does this change require touching an alarm".
 *
 * Five methods rather than one because dose resolution isn't the only thing
 * that can move a reminder's next alarm -- creating, editing, enabling and
 * deleting a reminder all can too, and a device reboot clears every exact
 * alarm AlarmManager was holding.
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

    /** Called after a change to a medicine's stock quantity or expiry date.
     *  Those two are inputs to LOW_STOCK_ALERT and EXPIRATION_ALERT scheduling
     *  (spec §7 R37), so a refill, a dose, or an edit to the expiry field can
     *  each move -- or cancel, or create -- an alert reminder's next alarm,
     *  with nothing about the reminder row itself having changed.
     *
     *  Scoped to that medicine's alert reminders on purpose. Its dose reminders
     *  do not depend on stock, and re-arming them here would duplicate what
     *  [onDoseResolved] already does on the path that actually moved them. */
    suspend fun onMedicineChanged(medicineId: Long)

    /** Recomputes and re-schedules every enabled reminder's next alarm.
     *  Called after boot / app update, since AlarmManager forgets every
     *  exact alarm across both. */
    suspend fun rescheduleAll()

    /** Called by DoseEventRepository.resolve() -- a dose that was being
     *  nagged about (spec §3/§4 `repeatOnUnanswered`) just got a real
     *  answer, so any pending nag re-fire for *that dose* should stop. Keyed
     *  by dose event since §7 R60: a reminder can have two outstanding doses. */
    suspend fun cancelNag(reminderId: Long, doseEventId: Long)

    /** A pending dose moved to a chosen time (§9 #26; medTimer's RESCHEDULE):
     *  re-notify at that instant through the snooze slot. Default no-op so
     *  the test fakes need not care. */
    suspend fun onDoseRescheduled(reminderId: Long, doseEventId: Long, atEpochMillis: Long) {}
}
