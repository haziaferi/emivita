package com.hazia.emivita.core.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hazia.emivita.core.data.config.ReminderType
import kotlinx.serialization.Serializable

/** Nag-until-answered behavior for an unresolved dose. Embedded (not part of the
 *  per-type ReminderConfig JSON) because it's cross-cutting -- every reminder
 *  type can opt into it, independent of its schedule shape (spec §3). Null =
 *  disabled; all-columns-null is how Room persists a null @Embedded object. Not
 *  yet read by SchedulingEngine (§9 #-adjacent) -- schema only, this batch. */
@Serializable
data class RepeatConfig(
    val delayMinutes: Int,
    val maxRepeats: Int,
)

@Serializable
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(entity = Medicine::class, parentColumns = ["id"], childColumns = ["medicineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DoseGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.SET_NULL),
        // Self-referencing: a Low-Stock/Expiration alert reminder (once built,
        // §9 follow-up) can be spawned from and tied to the medicine's primary
        // dosing reminder. SET_NULL, not CASCADE -- deleting the parent reminder
        // shouldn't delete the alert, just detach it (spec §3).
        ForeignKey(entity = Reminder::class, parentColumns = ["id"], childColumns = ["parentReminderId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("medicineId"), Index("groupId"), Index("parentReminderId")],
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val groupId: Long? = null, // null = not part of any confirmed group
    val parentReminderId: Long? = null,
    val type: ReminderType,
    val configJson: String, // serialized ReminderConfig matching `type`
    val enabled: Boolean = true,
    // Auto-resolve a fired dose as Taken without a user tap (e.g. a passive
    // supplement reminder). Not read by SchedulingEngine yet -- schema only,
    // same as RepeatConfig above (spec §3).
    val autoResolve: Boolean = false,
    // Inclusive epoch-day bounds clamping when this reminder is considered
    // active at all (e.g. a course of antibiotics) -- independent of, and
    // narrower than, whatever its per-type schedule config computes. Null =
    // unbounded on that side. Not yet consulted by nextOccurrenceFor() (spec §3,
    // §9 follow-up) -- schema only, this batch.
    val activeFromEpochDay: Long? = null,
    val activeUntilEpochDay: Long? = null,
    @Embedded(prefix = "repeat_") val repeatOnUnanswered: RepeatConfig? = null,
    // LOW_STOCK_ALERT / EXPIRATION_ALERT only (spec §9 #8) -- the epoch day this
    // alert last posted a notification, so Once/Daily trigger modes don't
    // re-notify on every cache recompute. Null = never notified (or, for
    // LowStockAlert, restocked back above threshold since the last notify --
    // see LowStockExpirationAlertEvaluator for the reset rule). Unused by the
    // other three reminder types.
    val lastAlertNotifiedEpochDay: Long? = null,
    val createdAtEpochMillis: Long,
)
