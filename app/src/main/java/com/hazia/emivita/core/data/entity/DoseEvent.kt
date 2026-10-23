package com.hazia.emivita.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
/** DELETED (§9 #26): a soft delete. The row stays -- its slot cannot be
 *  re-materialised, its stock effect was reversed -- but it is out of every
 *  list, count and export. medTimer's `DELETED` has the same shape. */
enum class DoseStatus { PENDING, TAKEN, SKIPPED, REFILLED, DELETED, STOCK_ALERT_SEEN, EXPIRY_ALERT_SEEN }

/** A History row that is not a dose: a refill, or a stock or expiry alert swiped
 *  away (§7 R105, census B-44, as medTimer records it). Out of adherence counts,
 *  the calendar, Today and dose editing, the way REFILLED always was. */
val DoseStatus.isRecordOnly: Boolean
    get() = this == DoseStatus.REFILLED || isAlertSeen

/** The two seen alerts: no amount to show. */
val DoseStatus.isAlertSeen: Boolean
    get() = this == DoseStatus.STOCK_ALERT_SEEN || this == DoseStatus.EXPIRY_ALERT_SEEN

@Serializable
@Entity(
    tableName = "dose_events",
    foreignKeys = [
        // SET NULL, not CASCADE (§7 R118): deleting a reminder keeps the doses
        // it scheduled, as its dialog promises ("Its past doses stay in
        // History"); the phone check found a fired dose gone with its reminder.
        // [planned] keeps saying the dose was scheduled once reminderId is null.
        ForeignKey(entity = Reminder::class, parentColumns = ["id"], childColumns = ["reminderId"], onDelete = ForeignKey.SET_NULL),
        // A one-off dose (§7 R111, C-15) has no medicine to take a profile from,
        // so it names its own, and goes with the profile as its medicines do.
        ForeignKey(entity = Profile::class, parentColumns = ["id"], childColumns = ["customProfileId"], onDelete = ForeignKey.CASCADE),
    ],
    // The compound unique index is what actually makes materializeIfAbsent()/
    // insert()'s OnConflictStrategy.IGNORE idempotent -- without it, two
    // concurrent materialization passes for the same reminder+time (e.g. a
    // WorkManager pass racing an alarm receiver) could each pass the
    // check-then-insert race and create duplicate dose events. SQLite treats each
    // NULL in a unique index as distinct from every other NULL, so this still
    // allows any number of PRN events (reminderId = null) at the same instant --
    // correct, since PRN logging isn't racing a materialization pass at all.
    indices = [
        Index(value = ["reminderId", "scheduledEpochMillis"], unique = true),
        Index("scheduledEpochMillis"), // supports observeInRange()'s range scan
        Index("medicineId"),
        Index("customProfileId"),
    ],
)
data class DoseEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Nullable -- null marks a manually-logged (PRN) dose not tied to any scheduled
    // occurrence. See DoseEventRepository.logAdhocDose (spec §3/§9 #2). Also
    // null on a dose whose reminder was deleted (§7 R118); [planned] tells the two apart.
    val reminderId: Long? = null,
    // Denormalized, copied from the reminder at materialization time and stored
    // directly on a PRN event, which has no reminder to derive it from. Null only
    // on a one-off dose (§7 R111, C-15), which carries [customName] and
    // [customProfileId] instead: one subject per dose, checked on every insert.
    val medicineId: Long?,
    val scheduledEpochMillis: Long,
    // Snapshotted at materialization time so editing a reminder's dose later
    // doesn't retroactively change historical records -- mirrors the reference
    // app's "color change only affects future events" behavior.
    val doseAmount: Double,
    val status: DoseStatus = DoseStatus.PENDING,
    val resolvedAtEpochMillis: Long? = null,
    // Sentinel, not nullable -- same reasoning as medTimer's ADR-0004 (spec §3):
    // Room AutoMigration handles non-null defaulted columns more predictably than
    // nullable Double, and "was stock tracked for this event" collapses to one
    // check (stockBefore != stockAfter) that also correctly covers old migrated
    // rows and un-tracked medicines. Written by resolve()/logAdhocDose()/
    // logRefill(), and read back by switchStatus(), which uses the pair as a
    // *difference* -- how much this dose actually consumed -- and applies it
    // relative to current stock. Writing the pair back as an absolute
    // quantity is what §7 R27 fixed: it discarded every stock change made
    // between the resolution and the switch (§9 #3).
    val stockBefore: Double = -1.0,
    val stockAfter: Double = -1.0,
    // Incremented per "nag" fire, capped at Reminder.repeatOnUnanswered.maxRepeats
    // (spec §3/§4). See ReminderAlarmReceiver.handleNagFire.
    val repeatCount: Int = 0,
    /** Free text on one dose (§9 #26; MediTrak/medTimer event notes):
     *  "took it late, was travelling". Exported in the CSV. */
    val notes: String? = null,
    val createdAtEpochMillis: Long,
    /** A dose of a medicine not on the list, logged once (§7 R111, C-15): its
     *  name and its profile, with no medicine row behind it. */
    val customName: String? = null,
    val customProfileId: Long? = null,
    /** A reminder scheduled this dose (§7 R118): what adherence counts. Set by
     *  the one path that materialises scheduled doses and kept when the
     *  reminder is deleted and [reminderId] goes null. The default derives it
     *  for a row written without it, a backup older than the field included,
     *  the way MIGRATION_12_13 backfills it. */
    val planned: Boolean = reminderId != null,
    /** When this dose, still PENDING, stops being due and reads Missed (§7 R119):
     *  the last repeat of its notification, written when it fires, is snoozed
     *  or is moved. Null reads as [scheduledEpochMillis]: missed at once. */
    val missedAfterEpochMillis: Long? = null,
)

/** A one-off dose (§7 R111): not scheduled, so it never counts as missed, and
 *  out of adherence counts, which measure the list's medicines. */
val DoseEvent.isOneOff: Boolean
    get() = customName != null

/** Exactly one subject (§7 R111): a medicine, or a one-off name with its profile. */
val DoseEvent.hasOneSubject: Boolean
    get() = if (medicineId != null) customName == null && customProfileId == null
    else !customName.isNullOrBlank() && customProfileId != null

/** The instant a PENDING dose becomes Missed (§7 R119); see [DoseEvent.missedAfterEpochMillis]. */
val DoseEvent.missedFromEpochMillis: Long
    get() = missedAfterEpochMillis ?: scheduledEpochMillis

/** The owner's rule (§7 R119, 2026-10-23): a PENDING dose is Due while its
 *  notification is up or will repeat, and Missed after the last repeat. The
 *  one test History, its chart and counts, the PDF and the CSV all read. */
fun DoseEvent.isMissedAt(nowEpochMillis: Long): Boolean =
    status == DoseStatus.PENDING && nowEpochMillis >= missedFromEpochMillis

/** The other half of [isMissedAt]: counted neither taken nor missed. */
fun DoseEvent.isDueAt(nowEpochMillis: Long): Boolean =
    status == DoseStatus.PENDING && nowEpochMillis < missedFromEpochMillis

/** The deadline a notification shown at [shownAtEpochMillis] sets (§7 R119):
 *  the repeats still owed under [repeat] after [repeatCount], [RepeatConfig.delayMinutes]
 *  apart; with none configured or none left, the moment it is shown. */
fun missedAfterShowing(shownAtEpochMillis: Long, repeat: RepeatConfig?, repeatCount: Int): Long {
    val left = repeat?.let { (it.maxRepeats - repeatCount).coerceAtLeast(0) } ?: 0
    return shownAtEpochMillis + left * (repeat?.delayMinutes ?: 0) * 60_000L
}
