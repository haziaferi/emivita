package com.hazia.emivita.core.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

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
    /** Inert since §7 R121: R119's "Missed after the last repeat" deadline,
     *  which nothing writes or reads any more. The column stays (no migration)
     *  and backups carry it, so files from schema-13 builds still decode. */
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

/** Missed, for the figures only (§7 R121, the owner's rule of 2026-10-23): a
 *  skipped dose, or one still unanswered after the midnight that ends its
 *  scheduled day in [zone]. On screen an unanswered dose always reads Due. */
fun DoseEvent.isMissedInFiguresAt(nowEpochMillis: Long, zone: ZoneId): Boolean = when (status) {
    DoseStatus.SKIPPED -> true
    DoseStatus.PENDING -> nowEpochMillis >= Instant.ofEpochMilli(scheduledEpochMillis).atZone(zone)
        .toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    else -> false
}
